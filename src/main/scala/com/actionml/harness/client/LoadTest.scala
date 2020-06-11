package com.actionml.harness.client

import java.io.PrintWriter

import io.circe.Json
import io.circe.literal._
import io.circe.parser._
import logstage._
import sttp.client._
import sttp.client.asynchttpclient.ziostreams.AsyncHttpClientZioStreamsBackend
import zio._
import zio.duration._
import zio.stream.{ Sink, ZStream }

import scala.util.Using

object LoadTest extends App {

  override def run(args: List[String]): ZIO[ZEnv, Nothing, Int] = {
    import Utils._
    val appArgs = RunArgs.parse(args).getOrElse { System.exit(1); throw new RuntimeException }
    val log = IzLogger(if (appArgs.isVerbose) Debug else if (appArgs.isVVerbose) Trace else Info,
                       Seq(ConsoleSink.text(colored = true)))
    val uri = uri"""${appArgs.uri}/engines/${appArgs.engineId}/${if (appArgs.input) "events"
    else "queries"}"""

    def runEvents: ZIO[ZEnv, Throwable, (Long, Results)] =
      for {
        httpBackend <- AsyncHttpClientZioStreamsBackend(this)
        globalStart = System.currentTimeMillis()
        results <- linesFromPath(appArgs.fileName)
          .zipWith(ZStream.fromIterable(LazyList.from(0)))((s, i) => i.flatMap(n => s.map(b => (n, b))))
          .filter { case (n, _) => n % appArgs.factor == 0 }
          .throttleShape(appArgs.maxPerSecond, 1.second)(_ => 1)
          .mapMParUnordered(appArgs.nThreads) {
            case (requestNumber, request) =>
              val start = System.currentTimeMillis()
              log.trace(s"Sending $requestNumber $request")
              httpBackend
                .send(
                  basicRequest
                    .body(request)
                    .readTimeout(appArgs.timeout)
                    .followRedirects(true)
                    .maxRedirects(3)
                    .redirectToGet(false)
                    .header("Content-Type", "application/json")
                    .post(uri)
                )
                .retry(Schedule.recurs(appArgs.nRetries))
                .map { resp =>
                  val responseTime = calcLatency(start)
                  log.trace(s"Got response $resp for $requestNumber")
                  log.debug(s"Request $requestNumber got response in $responseTime ms")
                  Results(if (resp.isSuccess) 1 else 0, if (resp.isServerError) 1 else 0, responseTime, responseTime)
                }
                .foldCause(
                  c => {
                    c.failureOption.fold(log.error(s"Got error: ${c.prettyPrint}")) { e =>
                      log.error(s"Input event error ${e.getMessage}")
                    }
                    val l = calcLatency(start)
                    Results(0, 1, l, l)
                  },
                  a => a
                )
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
      } yield (globalStart, results._2)

    def runQueries(user: Boolean): ZIO[ZEnv, Throwable, (Long, Results)] = {
      val eType      = if (user) "entityType" else "targetEntityType"
      val eIdType    = if (user) "entityId" else "targetEntityId"
      val eTypeValue = if (user) "user" else "item"
      val tmpFile    = s"essearchqueries-$eTypeValue.json"
      def mkSearchString(s: String): zio.stream.Stream[Throwable, String] = {
        val j = parse(s).getOrElse(Json.Null).dropNullValues
        val entityType = j.hcursor
          .downField(eType)
          .as[String]
        val event    = j.hcursor.downField("event").as[String]
        val isTarget = entityType.contains(eTypeValue)
        if (isTarget && (appArgs.isAllItems || event.contains(appArgs.filterByItemEvent)))
          ZStream.fromIterable(
            j.hcursor.downField(eIdType).as[String].toOption.map(id => s"""{"$eTypeValue": "$id"}""")
          )
        else ZStream.empty
      }
      val tmpStart = System.currentTimeMillis()
      Using.resource(new PrintWriter(tmpFile)) { writer =>
        new DefaultRuntime {}.unsafeRun(
          linesFromPath(appArgs.fileName)
            .flatMap(mkSearchString)
            .foreach(s => ZIO.effect(writer.println(s)))
        )
      }
      log.info(s"Preparation stage took ${System.currentTimeMillis() - tmpStart} ms")

      for {
        httpBackend <- AsyncHttpClientZioStreamsBackend(this)
        globalStart = System.currentTimeMillis()
        results <- linesFromPath(tmpFile)
          .zipWith(ZStream.fromIterable(LazyList.from(0)))((s, i) => i.flatMap(n => s.map(b => (n, b))))
          .filter { case (n, _) => n % appArgs.factor == 0 }
          .throttleShape(appArgs.maxPerSecond, 1.second)(_ => 1)
          .mapMParUnordered(appArgs.nThreads) {
            case (requestNumber, request) =>
              val start = System.currentTimeMillis()
              log.trace(s"Sending $requestNumber $request")
              httpBackend
                .send(basicRequest.body(request).header("Content-Type", "application/json").post(uri))
                .map { resp =>
                  val responseTime = calcLatency(start)
                  log.debug(s"Request $requestNumber got response $resp in $responseTime ms")
                  Results(if (resp.isSuccess) 1 else 0, if (resp.isServerError) 1 else 0, responseTime, responseTime)
                }
                .foldCause(c => {
                  log.error(s"Got error: ${c.prettyPrint}")
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
      } yield (globalStart, results._2)
    }

    def calcLatency(start: Long): Int = (System.currentTimeMillis() - start).toInt

    log.info(s"Started with arguments: $appArgs")
    (for {
      (start, results) <- if (appArgs.input) runEvents else runQueries(appArgs.isUserBased)
      requestsPerSecond = (results.succeeded + results.failed) / (calcLatency(start) / 1000 + 1)
      _ = log.info(
        s"$requestsPerSecond, ${results.succeeded}, ${results.failed}, ${results.maxLatency} ms, ${results.avgLatency} ms"
      )
    } yield 0)
      .mapErrorCause { c =>
        log.error(s"Got error: ${c.prettyPrint}")
        Cause.empty
      }
  }
}

final case class Results(succeeded: Int, failed: Int, maxLatency: Int, avgLatency: Int)
