package com.actionml.harness.client

import java.io.FileInputStream
import java.util.concurrent._

import cats.arrow.FunctionK
import cats.effect
import cats.effect.Blocker
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
import org.http4s.client.{ Client, _ }
import org.http4s.dsl.io._
import org.http4s.{ EntityBody, Request, Uri }
import zio._
import zio.duration._
import zio.interop.catz._
import zio.stream.{ Sink, ZSink, ZStream }

import scala.concurrent.ExecutionContext

object LoadTest extends App {

  override def run(args: List[String]): ZIO[ZEnv, Nothing, Int] = {
    val appArgs = RunArgs.parse(args).getOrElse { System.exit(1); throw new RuntimeException }
    val log = IzLogger(if (appArgs.isVerbose) Debug else if (appArgs.isVVerbose) Trace else Info,
                       Seq(ConsoleSink.text(colored = true), DefaultFileSink("logs")))
    def mkHttp4sClient: ZManaged[Any, Throwable, Client[Task]] = {
      val blockingEC =
        Blocker.liftExecutionContext(ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(appArgs.nCpus)))
      val httpClient = JavaNetClientBuilder[Task](blockingEC).create
      ZManaged.make(ZIO(httpClient))(_ => URIO.unit)
    }

    def requestMaker(uri: Uri)(body: String): Request[Task] =
      parse(body) match {
        case Right(j) =>
          POST(json"""$j""", uri).unsafeRunSync()
        case Left(f) =>
          log.error(s"Error ${f.message} on parsing $body")
          throw new RuntimeException(f.message, f.underlying)
      }

    def mkUri: Uri =
      Uri
        .fromString(
          s"""${appArgs.harnessUri}/engines/${appArgs.engineId}/${if (appArgs.input) "events" else "queries"}"""
        )
        .getOrElse(throw new RuntimeException)

    def linesFromPath(s: String): ZStream[Any, Nothing, String] = {
      val fileOrDir = new java.io.File(s)
      val files     = if (fileOrDir.isDirectory) fileOrDir.listFiles().toSeq else Seq(fileOrDir)
      files
        .map(new FileInputStream(_))
        .map(ZStream.fromInputStream(_, 512).mapErrorCause(_ => Cause.empty))
        .reduce(_ ++ _)
        .chunks
        .transduce(ZSink.utf8DecodeChunk)
        .transduce(ZSink.splitLines)
        .flatMap(ZStream.fromChunk)
    }

    def runEvents(httpClient: Client[Task]): ZIO[ZEnv, Throwable, Results] = {
      val mkRequest = requestMaker(mkUri)(_)
      log.info("Starting events")
      for {
        results <- linesFromPath(appArgs.fileName)
          .map(mkRequest)
          .zipWith(ZStream.fromIterable(LazyList.from(0)))((s, i) => i.flatMap(n => s.map(b => (n, b))))
          .filter { case (n, _) => n % appArgs.factor == 0 }
          .throttleShape(appArgs.maxPerSecond, 1.second)(_ => 1)
          .mapMParUnordered(appArgs.nThreads) {
            case (requestNumber, request) =>
              val start = System.currentTimeMillis()
              log.trace(s"Sending $requestNumber $request")
              httpClient
                .expect(request)
                .map { _ =>
                  val responseTime = calcLatency(start)
                  log.trace(s"Got response for $requestNumber")
                  log.debug(s"Request $requestNumber got response in $responseTime ms")
                  Results(1, 0, responseTime, responseTime)
                }
                .foldCause(_ => {
                  val l = calcLatency(start)
                  Results(0, 1, l, l)
                }, a => a)
          }
          .run(Sink.foldLeft((1, Results(0, 0, 0, 0))) { (acc: (Int, Results), result: Results) =>
            (acc._1 + 1,
             acc._2.copy(
               succeeded = acc._2.succeeded + result.succeeded,
               failed = acc._2.failed + result.failed,
               maxLatency = Math.max(acc._2.maxLatency, result.maxLatency),
               avgLatency = acc._2.avgLatency + (result.avgLatency - acc._2.avgLatency) / (acc._1 + 1)
             ))
          })
      } yield results._2
    }

    def runSearches(httpClient: Client[Task], user: Boolean) = {
      val eType      = if (user) "entityType" else "targetEntityType"
      val eIdType    = if (user) "entityId" else "targetEntityId"
      val eTypeValue = if (user) "user" else "item"
      def mkSearchString(s: String): zio.stream.Stream[Any, String] = {
        val j = parse(s).getOrElse(Json.Null).dropNullValues
        val entityType = j.hcursor
          .downField(eType)
          .as[String]
        val event    = j.hcursor.downField("event").as[String]
        val isTarget = entityType.contains(eTypeValue)
        if (isTarget && (appArgs.isUserBased || (appArgs.isAllItems || (event.contains(appArgs.filterByItemEvent)))))
          ZStream.fromIterable(
            j.hcursor.downField(eIdType).as[String].toOption.map(id => s"""{"$eTypeValue": "$id"}""")
          )
        else ZStream.empty
      }

      val mkRequest = requestMaker(mkUri)(_)
      for {
        results <- linesFromPath(appArgs.fileName)
          .flatMap(mkSearchString)
          .zipWith(ZStream.fromIterable(LazyList.from(0)))((s, i) => i.flatMap(n => s.map(b => (n, b))))
          .filter { case (n, _) => n % appArgs.factor == 0 }
          .map { case (n, s) => (n, mkRequest(s)) }
          .throttleShape(appArgs.maxPerSecond, 1.second)(_ => 1)
          .mapMParUnordered(appArgs.nThreads) {
            case (requestNumber, request) =>
              val start = System.currentTimeMillis()
              log.trace(s"Sending $requestNumber $request")
              httpClient
                .expect(request)
                .map { _ =>
                  val responseTime = calcLatency(start)
                  log.trace(s"Got response for $requestNumber")
                  log.debug(s"Request $requestNumber got response in $responseTime ms")
                  Results(1, 0, responseTime, responseTime)
                }
                .foldCause(_ => {
                  val l = calcLatency(start)
                  Results(0, 1, l, l)
                }, a => a)
          }
          .run(Sink.foldLeft((1, Results(0, 0, 0, 0))) { (acc: (Int, Results), result: Results) =>
            (acc._1 + 1,
             acc._2.copy(
               succeeded = acc._2.succeeded + result.succeeded,
               failed = acc._2.failed + result.failed,
               maxLatency = Math.max(acc._2.maxLatency, result.maxLatency),
               avgLatency = acc._2.avgLatency + (result.avgLatency - acc._2.avgLatency) / (acc._1 + 1)
             ))
          })
      } yield results._2
    }

    def calcLatency(start: Long): Int = (System.currentTimeMillis() - start).toInt

    mkHttp4sClient
      .use { client =>
        log.info(s"Running with arguments: $appArgs")
        val start = System.currentTimeMillis()
        for {
          results <- if (appArgs.input) runEvents(client) else runSearches(client, appArgs.isUserBased)
          requestsPerSecond = (results.succeeded + results.failed) / (calcLatency(start) / 1000)
          _ = log.info(
            s"$requestsPerSecond, ${results.succeeded}, ${results.failed}, ${results.maxLatency} ms, ${results.avgLatency} ms"
          )
        } yield 0
      }
      .mapErrorCause { c =>
        log.error(s"Got error: ${c.prettyPrint}")
        Cause.empty
      }
  }
}

final case class Results(succeeded: Int, failed: Int, maxLatency: Int, avgLatency: Int)

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
