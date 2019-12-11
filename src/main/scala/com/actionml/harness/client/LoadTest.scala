package com.actionml.harness.client

import java.util.concurrent._

import cats.arrow.FunctionK
import cats.effect
import cats.effect.{ Blocker, Clock }
import com.actionml.harness.client.zio2cats.interop._
import io.circe.{ Json, JsonObject }
import io.circe.literal._
import io.circe.parser._
import logstage._
import org.http4s.circe._
import org.http4s.client.{ Client, _ }
import org.http4s.client.dsl.io._
import org.http4s.client.middleware.{ Logger, Metrics }
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.metrics.prometheus.Prometheus
import org.http4s.{ EntityBody, Request, Uri }
import zio._
import zio.interop.catz._
import zio.stream.{ Sink, ZStream }

import scala.concurrent.ExecutionContext
import scala.io.Source

object LoadTest extends App {
  private val log = IzLogger(Log.Level.Debug, ConsoleSink.text(colored = false))

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

    def runEvents(runArgs: RunArgs, httpClient: Client[Task]): Task[Results] = {
      val mkRequest = requestMaker(mkUri(runArgs))(_)
      val source    = Source.fromFile(runArgs.fileName, "UTF-8") // todo: make a bracket and concat all files from file path
      val iterable  = source.getLines().to(Iterable)
      log.debug("Starting events")
      val results = zio.stream.Stream
        .fromIterable(iterable)
        .zipWith(ZStream.fromIterable(LazyList.from(0)))((s, i) => i.flatMap(n => s.map(b => (n, b))))
        .mapMParUnordered(runArgs.nThreads) {
          case (requestNumber, body) =>
            val start = System.currentTimeMillis()
            log.debug(s"Sending $requestNumber $body")
            httpClient
              .expect(mkRequest(body))
              .map { _ =>
                log.debug(s"Got response for $requestNumber")
                Results(1, 0, calcLatency(start))
              }
        }
        .run(Sink.foldLeft(Results(0, 0, 0)) { (acc: Results, i: Results) =>
          acc.copy(succeeded = acc.succeeded + 1, maxLatency = Math.max(acc.maxLatency, i.maxLatency))
        })
      source.close
      results
    }

    def runSearches(runArgs: RunArgs, httpClient: Client[Task], targetEntityType: String) = {
      def mkSearchString(s: String): zio.stream.Stream[Any, String] = {
        val j = parse(s).getOrElse(Json.Null).dropNullValues
        log.trace(s"top level $j")
        val entityType = j.hcursor
          .downField("entityType")
          .as[String]
        val isTarget = entityType.contains(targetEntityType)
        log.trace(s"$entityType")
        log.trace(s"$isTarget")
        ZStream.fromIterable(
          j.hcursor.downField("entityId").as[String].toOption.map(id => s"""{"$targetEntityType": "$id"}""")
        )
      }

      val mkRequest = requestMaker(mkUri(runArgs))(_)
      val source    = Source.fromFile(runArgs.fileName, "UTF-8")
      val iterable  = source.getLines().to(Iterable)
      log.debug(s"Starting search queries for $targetEntityType")
      val results = zio.stream.Stream
        .fromIterable(iterable)
        .flatMap(mkSearchString)
        .zipWith(ZStream.fromIterable(LazyList.from(0)))((s, i) => i.flatMap(n => s.map(b => (n, b))))
        .mapMParUnordered(runArgs.nThreads) {
          case (requestNumber, body) =>
            val start = System.currentTimeMillis()
            log.debug(s"Sending $requestNumber $body")
            httpClient
              .expect(mkRequest(body))
              .map { _ =>
                log.debug(s"Got response for $requestNumber")
                Results(1, 0, calcLatency(start))
              }
        }
        .run(Sink.foldLeft(Results(0, 0, 0)) { (acc: Results, i: Results) =>
          acc.copy(succeeded = acc.succeeded + 1, maxLatency = Math.max(acc.maxLatency, i.maxLatency))
        })
      source.close
      results
    }

    def calcLatency(start: Long): Int = (System.currentTimeMillis() - start).toInt

    mkHttp4sClient(4)
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
