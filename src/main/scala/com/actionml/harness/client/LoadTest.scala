package com.actionml.harness.client

import io.circe.Json
import io.circe.literal._
import io.circe.parser._
import logstage._
import sttp.capabilities.WebSockets
import sttp.capabilities.zio.ZioStreams
import sttp.client3._
import sttp.client3.httpclient.zio.HttpClientZioBackend
import sttp.model.Uri
import zio._
import zio.blocking.Blocking
import zio.clock.Clock
import zio.duration._
import zio.stream.{ ZSink, ZStream }

import java.io.IOException

object LoadTest extends App {

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    import Utils._
    val appArgs = RunArgs.parse(args).getOrElse { System.exit(1); throw new RuntimeException }
    val log = IzLogger(if (appArgs.isVerbose) Debug else if (appArgs.isVVerbose) Trace else Info,
                       Seq(ConsoleSink.text(colored = true)))
    log.info(s"Started with arguments: $appArgs")

    def mkRequest(uri: Uri): Request[Either[String, String], Any] =
      basicRequest
        .readTimeout(appArgs.timeout)
        .followRedirects(true)
        .maxRedirects(3)
        .redirectToGet(false)
        .header("Content-Type", "application/json")
        .post(uri)
    val inputRequest = mkRequest(uri"${appArgs.uri}/engines/${appArgs.engineId}/events")
    val queryRequest = mkRequest(uri"${appArgs.uri}/engines/${appArgs.engineId}/queries")

    def mkHttpRequests(defaultRequest: Request[Either[String, String], Any],
                       lines: ZStream[Blocking with Clock, Nothing, String],
                       http: SttpBackend[Task, ZioStreams with WebSockets],
                       filter: String => Boolean = _ => true) =
      lines
        .filter(filter)
        .throttleShape(appArgs.maxPerSecond, 1.second)(_.length)
        .mapMPar(appArgs.nThreads) { request =>
          val start = System.currentTimeMillis()
          log.trace(s"Sending $request")
          val sendEff = defaultRequest
            .body(request)
            .send(http)
          (if (appArgs.ignoreResponses) sendEff.ignore.as((1, 0, 0, 0, 0))
           else
             sendEff
               .retry(Schedule.recurs(appArgs.nRetries))
               .map { resp =>
                 val responseTime = calcLatency(start)
                 log.trace(s"Got response $resp for $request")
                 log.debug(s"Request $request completed in $responseTime ms")
                 (if (resp.isSuccess) 1 else 0,
                  if (resp.isServerError) 1 else 0,
                  responseTime,
                  responseTime,
                  responseTime)
               })
            .foldCause(
              c => {
                c.failureOption.fold(log.error(s"Got error: ${c.prettyPrint}")) { e =>
                  log.error(s"Input event error ${e.getMessage}")
                }
                val l = calcLatency(start)
                (0, 1, l, l, l)
              },
              a => a
            )
        }

    def mkInputs(lines: ZStream[Blocking with Clock, Nothing, String]) =
      lines.zipWithIndex.collect {
        case (l, i) if ~=(i, appArgs.inputWeight) => l
      }

    def ~=(a: Double, b: Double): Boolean = {
      val c = a / b
      c - Math.floor(c) < 0.001
    }

    def mkQueries(lines: ZStream[Blocking with Clock, Nothing, String]) = {
      def mkItemBasedQuery(s: String) = {
        val eType      = "targetEntityType"
        val eIdType    = "targetEntityId"
        val eTypeValue = "item"
        mkSearchString(s, eType = eType, eIdType = eIdType, eTypeValue = eTypeValue)
      }
      def mkUserBasedQuery(s: String) = {
        val eType      = "entityType"
        val eIdType    = "entityId"
        val eTypeValue = "user"
        mkSearchString(s, eType = eType, eIdType = eIdType, eTypeValue = eTypeValue)
      }

      def mkSearchString(s: String,
                         eType: String,
                         eIdType: String,
                         eTypeValue: String): zio.stream.Stream[Nothing, String] = {
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

      lines.zipWithIndex.flatMap {
        case (s, i) =>
          (if (appArgs.isItemBased && ~=(i, appArgs.itemBasedWeight)) mkItemBasedQuery(s)
           else ZStream.empty) ++
          (if (appArgs.isUserBased && ~=(i, appArgs.userBasedWeight)) mkUserBasedQuery(s)
           else ZStream.empty)
      }
    }

    def calcLatency(start: Long): Int = {
      val a = (System.currentTimeMillis() - start).toInt
      if (a == 0) 1 else a
    }

    def combineRequests(http: SttpBackend[Task, ZioStreams with WebSockets]) = {
      def lines = {
        val l = linesFromPath(appArgs.fileName).drop(scala.util.Random.nextLong(appArgs.factor * appArgs.nThreads))
        appArgs.totalTime
          .fold[ZStream[Blocking with Clock, Nothing, String]](l) { totalTime =>
            l.forever.interruptAfter(Duration.fromScala(totalTime))
          }
          .zipWithIndex
          .collect {
            case (r, i) if i % appArgs.factor == 0 => r
          }
      }

      val setsFilter: String => Boolean = s => !(appArgs.skipSets && s.contains("$set"))
      ZStream.mergeAll(2)(
        if (appArgs.isInput) mkHttpRequests(inputRequest, mkInputs(lines), http, setsFilter) else ZStream.empty,
        if (appArgs.isQuery) mkHttpRequests(queryRequest, mkQueries(lines), http) else ZStream.empty
      )
    }

    (for {
      http <- HttpClientZioBackend()
      start = System.currentTimeMillis()
      r <- combineRequests(http)
        .run(ZSink.collectAll)
        .map(_.toList)
        .map(l => l.map(r => (Results.apply _) tupled r))
      responses         = r.view
      totalSent         = responses.length
      requestsPerSecond = ((totalSent.toFloat / calcLatency(start)) * 1000).toInt
      (_, x) = responses.foldLeft((1, (0, 0, 0, 0, 0))) { (acc: (Int, (Int, Int, Int, Int, Int)), result) =>
        (acc._1 + 1,
         (
           acc._2._1 + result.succeeded,
           acc._2._2 + result.failed,
           if (acc._2._3 != 0) Math.min(acc._2._3, result.minLatency) else result.minLatency,
           Math.max(acc._2._4, result.maxLatency),
           acc._2._5 + (result.avgLatency - acc._2._5) / (acc._1 + 1)
         ))
      }
      results       = Results.apply _ tupled x
      responseTimes = responses.map(_.minLatency)
      _ = log.info(
        s"$requestsPerSecond, ${results.succeeded}, ${results.failed}, ${results.minLatency} ms, ${results.maxLatency} ms, ${results.avgLatency} ms"
      )
      numOfEvents = r.length
      perc = responseTimes
        .groupBy(a => a)
        .view
        .mapValues(_.toSeq.length)
        .toSeq
        .sortBy(_._1)
        .foldLeft(List.empty[(Int, Float)]) {
          case (acc, (responseTime, repeats)) =>
            (responseTime, repeats.toFloat / numOfEvents.toFloat + acc.map(_._2).maxOption.getOrElse(0f)) :: acc
        }
        .reverse
      _ = log.info(perc.map { case (p, i) => s"$p - ${i.toDouble}" }.mkString(", "))
      _ = println("percentile_values.csv:")
      _ = println(perc.map(_._2).mkString(","))
      _ = println(perc.map(_._1).mkString(","))
    } yield 0).exitCode
      .mapErrorCause { c =>
        log.error(s"Got error: ${c.prettyPrint}")
        Cause.empty
      }
  }
}

final case class Results(succeeded: Int, failed: Int, minLatency: Int, maxLatency: Int, avgLatency: Int) {
  def sum(r: Results): Results =
    Results(r.succeeded + succeeded,
            r.failed + failed,
            r.minLatency + minLatency,
            r.maxLatency + maxLatency,
            r.avgLatency + avgLatency)
}

object Results {
  val empty: Results = Results(0, 0, 0, 0, 0)
}
