package com.actionml.harness.client

import java.util.concurrent._

import cats.arrow.FunctionK
import cats.effect
import cats.effect.{ Blocker, Clock }
import com.actionml.harness.client.zio2cats.interop._
import io.circe.Json
import io.circe.literal._
import io.circe.parser._
import izumi.logstage.api.rendering.{ RenderingOptions, StringRenderingPolicy }
import izumi.logstage.sink.file.FileServiceImpl.RealFile
import izumi.logstage.sink.file.models.FileRotation.DisabledRotation
import izumi.logstage.sink.file.models.FileSinkConfig
import izumi.logstage.sink.file.{ FileServiceImpl, FileSink }
import logstage._
import org.http4s.circe._
import org.http4s.client.dsl.io._
import org.http4s.client.middleware.{ Logger, Metrics }
import org.http4s.client.{ Client, _ }
import org.http4s.dsl.io._
import org.http4s.metrics.prometheus.Prometheus
import org.http4s.{ EntityBody, Request, Uri }
import zio.ZIO.BracketRelease
import zio._
import zio.interop.catz._
import zio.stream.{ Sink, ZStream }

import scala.collection.IterableOnce
import scala.concurrent.ExecutionContext
import scala.io.{ BufferedSource, Source }
import scala.reflect.io.File

object LoadTest extends App {
  private val log =
    IzLogger(Log.Level.Debug, Seq(ConsoleSink.text(colored = false), DefaultFileSink("logs")))

  override def run(args: List[String]): ZIO[zio.ZEnv, Nothing, Int] = {
    def mkHttp4sClient(n: Int) = {
      val blockingEC =
        Blocker.liftExecutionContext(ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(n)))
      val httpClient              = JavaNetClientBuilder[Task](blockingEC).create
      val requestMethodClassifier = (r: Request[Task]) => Some(r.method.toString.toLowerCase)
      implicit val clock          = Clock.create[Task]

      (for {
        registry <- Prometheus.collectorRegistry[Task]
        metrics  <- Prometheus.metricsOps[Task](registry, "harness_load_test")
      } yield
        Metrics[Task](metrics, requestMethodClassifier)(Logger(logHeaders = false, logBody = false)(httpClient))).toManagedZIO
    }

    def requestMaker(uri: Uri)(body: String): Request[Task] = {
      val j = parse(body).getOrElse(throw new RuntimeException)
      POST(json"""$j""", uri).unsafeRunSync()
    }

    def mkUri(runArgs: RunArgs): Uri =
      Uri
        .fromString(
          s"""${runArgs.harnessUri}/engines/${runArgs.engineId}/${if (runArgs.input) "events" else "queries"}"""
        )
        .getOrElse(throw new RuntimeException)

    def linesFromFiles(s: String): Task[Iterable[String]] = {
      def fileSource(path: String): BracketRelease[Any, Throwable, Seq[BufferedSource]] =
        ZIO.bracket(Task {
          val fileOrDir = new java.io.File(path)
          val files     = if (fileOrDir.isDirectory) fileOrDir.listFiles().toSeq else Seq(fileOrDir)
          files.map(Source.fromFile(_, "UTF-8"))
        })(sources => URIO(sources.foreach(_.close())))

      fileSource(s).apply { ss: Seq[BufferedSource] =>
        ZIO(ss.toIterable.flatMap { s: BufferedSource =>
          s.getLines.to(Iterable)
        })
      }
    }

    def runEvents(runArgs: RunArgs, httpClient: Client[Task]): Task[Results] = {
      val mkRequest = requestMaker(mkUri(runArgs))(_)
      log.info("Starting events")
      for {
        lines <- linesFromFiles(runArgs.fileName)
        results <- zio.stream.Stream
          .fromIterable(lines)
          .zipWith(ZStream.fromIterable(LazyList.from(0)))((s, i) => i.flatMap(n => s.map(b => (n, b))))
          .mapMParUnordered(runArgs.nThreads) {
            case (requestNumber, body) =>
              val start = System.currentTimeMillis()
              log.trace(s"Sending $requestNumber $body")
              httpClient
                .expect(mkRequest(body))
                .map { _ =>
                  val responseTime = calcLatency(start)
                  log.trace(s"Got response for $requestNumber")
                  log.debug(s"Request $requestNumber got response in $responseTime ms")
                  Results(1, 0, calcLatency(start))
                }
          }
          .run(Sink.foldLeft(Results(0, 0, 0)) { (acc: Results, i: Results) =>
            acc.copy(succeeded = acc.succeeded + 1, maxLatency = Math.max(acc.maxLatency, i.maxLatency))
          })
      } yield results
    }

    def runSearches(runArgs: RunArgs, httpClient: Client[Task], targetEntityType: String) = {
      def mkSearchString(s: String): zio.stream.Stream[Any, String] = {
        val j = parse(s).getOrElse(Json.Null).dropNullValues
        val entityType = j.hcursor
          .downField("entityType")
          .as[String]
        val isTarget = entityType.contains(targetEntityType)
        ZStream.fromIterable(
          j.hcursor.downField("entityId").as[String].toOption.map(id => s"""{"$targetEntityType": "$id"}""")
        )
      }

      val mkRequest = requestMaker(mkUri(runArgs))(_)
      val iterable  = Source.fromFile(runArgs.fileName, "UTF-8").getLines.to(Iterable)
      log.debug(s"Starting search queries for $targetEntityType")
      val results = zio.stream.Stream
        .fromIterable(iterable)
        .flatMap(mkSearchString)
        .zipWith(ZStream.fromIterable(LazyList.from(0)))((s, i) => i.flatMap(n => s.map(b => (n, b))))
        .mapMParUnordered(runArgs.nThreads) {
          case (requestNumber, body) =>
            val start = System.currentTimeMillis()
            log.trace(s"Sending $requestNumber $body")
            httpClient
              .expect(mkRequest(body))
              .map { _ =>
                val responseTime = calcLatency(start)
                log.trace(s"Got response for $requestNumber")
                log.debug(s"Request $requestNumber got response in $responseTime ms")
                Results(1, 0, responseTime)
              }
        }
        .run(Sink.foldLeft(Results(0, 0, 0)) { (acc: Results, i: Results) =>
          acc.copy(succeeded = acc.succeeded + 1, maxLatency = Math.max(acc.maxLatency, i.maxLatency))
        })
      results
    }

    def calcLatency(start: Long): Int = (System.currentTimeMillis() - start).toInt

    mkHttp4sClient(8)
      .use { client =>
        for {
          runArgs <- UIO(RunArgs.parse(args)).flatMap(_.map(UIO(_)).getOrElse(ZIO.interrupt))
          _     = log.info(s"Running with arguments: $runArgs")
          start = System.currentTimeMillis()
          results <- if (runArgs.input) runEvents(runArgs, client) else runSearches(runArgs, client, "user")
          requestsPerSecond = (results.succeeded + results.failed) / (calcLatency(start) / 1000)
          _ = log.info(
            s"$requestsPerSecond, ${results.succeeded}, ${results.failed}, ${results.maxLatency} ms"
          )
        } yield 0
      }
      .mapErrorCause { c =>
        log.error(s"Got error: ${c.prettyPrint}")
        Cause.empty
      }
  }
}

final case class Results(succeeded: Int, failed: Int, maxLatency: Int)

object zio2cats {
  object interop {
    implicit def ioBody2task: EntityBody[cats.effect.IO] => EntityBody[Task] = body => {
      body.translate {
        new FunctionK[cats.effect.IO, Task] {
          override def apply[A](fa: effect.IO[A]): Task[A] =
            Task(fa.unsafeRunSync())
        }
      }
    }
    implicit def ioRequest2task(r: Request[cats.effect.IO]): Request[Task] = new Request[Task](
      method = r.method,
      uri = r.uri,
      headers = r.headers,
      body = r.body
    )
  }
}

object DefaultFileSink {
  private val policy                      = new StringRenderingPolicy(RenderingOptions(withExceptions = true, colored = false))
  private def fileService(logDir: String) = new FileServiceImpl(logDir)
  private val rotation                    = DisabledRotation
  private val config                      = FileSinkConfig.inBytes(10 * 1024 * 1024)
  def apply(logDir: String): FileSink[RealFile] =
    new FileSink(policy, fileService(logDir), rotation, config) {
      override def recoverOnFail(e: String): Unit = System.err.println(s"ERROR: $e")
    }
}
