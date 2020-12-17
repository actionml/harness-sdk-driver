package com.actionml.harness.client

import com.actionml.harness.client.RequestType.{ ItemQuery, UserQuery }
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

import scala.collection.SeqView

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
                       lines: ZStream[Blocking with Clock, Nothing, (RequestType.RequestType, String)],
                       http: SttpBackend[Task, ZioStreams with WebSockets],
                       filter: String => Boolean = _ => true): ZStream[Blocking with Clock, Nothing, Results] =
      lines
        .filter { case (_, r) => filter(r) }
        .throttleShape(appArgs.maxPerSecond, 1.second)(_.length)
        .mapMPar(appArgs.nThreads) {
          case (reqType, request) =>
            val start = System.currentTimeMillis()
            log.trace(s"Sending $request")
            val sendEff = defaultRequest
              .body(request)
              .send(http)
            (if (appArgs.ignoreResponses) sendEff.ignore.as(Results(1, 0, 0, 0, 0, reqType))
             else
               sendEff
                 .retry(Schedule.recurs(appArgs.nRetries))
                 .map { resp =>
                   val responseTime = calcLatency(start)
                   log.trace(s"Got response $resp for $request")
                   log.debug(s"Request $request completed in $responseTime ms")
                   Results(if (resp.isSuccess) 1 else 0,
                           if (resp.isServerError) 1 else 0,
                           responseTime,
                           responseTime,
                           responseTime,
                           reqType)
                 })
              .foldCause(
                c => {
                  c.failureOption.fold(log.error(s"Got error: ${c.prettyPrint}")) { e =>
                    log.error(s"Input event error ${e.getMessage}")
                  }
                  val l = calcLatency(start)
                  Results(0, 1, l, l, l, reqType)
                },
                a => a
              )
        }

    def mkInputs(
        lines: ZStream[Blocking with Clock, Nothing, String]
    ): ZStream[Blocking with Clock, Nothing, (RequestType.RequestType, String)] =
      lines.zipWithIndex.collect {
        case (l, i) if ~=(i, appArgs.inputWeight) => RequestType.Input -> l
      }

    def ~=(a: Double, b: Double): Boolean = {
      val c = a / b
      c - Math.floor(c) < 0.001
    }

    def mkQueries(
        lines: ZStream[Blocking with Clock, Nothing, String]
    ): ZStream[Blocking with Clock, Nothing, (RequestType.RequestType, String)] = {
      def mkItemBasedQuery(s: String) = {
        val eType      = "targetEntityType"
        val eIdType    = "targetEntityId"
        val eTypeValue = "item"
        mkSearchString(s, eType = eType, eIdType = eIdType, eTypeValue = eTypeValue, ItemQuery)
      }
      def mkUserBasedQuery(s: String) = {
        val eType      = "entityType"
        val eIdType    = "entityId"
        val eTypeValue = "user"
        mkSearchString(s, eType = eType, eIdType = eIdType, eTypeValue = eTypeValue, UserQuery)
      }

      def mkSearchString(
          s: String,
          eType: String,
          eIdType: String,
          eTypeValue: String,
          reqType: RequestType.RequestType
      ): zio.stream.Stream[Nothing, (RequestType.RequestType, String)] = {
        val j = parse(s).getOrElse(Json.Null).dropNullValues
        val entityType = j.hcursor
          .downField(eType)
          .as[String]
        val event    = j.hcursor.downField("event").as[String]
        val isTarget = entityType.contains(eTypeValue)
        if (isTarget && (appArgs.isAllItems || event.contains(appArgs.filterByItemEvent)))
          ZStream.fromIterable(
            j.hcursor.downField(eIdType).as[String].toOption.map(id => reqType -> s"""{"$eTypeValue": "$id"}""")
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

    def combineRequests(
        http: SttpBackend[Task, ZioStreams with WebSockets]
    ): ZStream[Blocking with Clock, Nothing, Results] = {
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

    def calcResults(responses: SeqView[Results], totalTime: Long): (Int, Results) = {
      val (totalSent, results) = responses.foldLeft((1, Results.empty)) {
        case ((i, Results(succ, fail, min, max, avg, req)), result) =>
          (i + 1,
           Results(
             succ + result.succeeded,
             fail + result.failed,
             if (min != 0) Math.min(min, result.minLatency) else result.minLatency,
             Math.max(max, result.maxLatency),
             avg + (result.avgLatency - avg) / (i + 1),
             req
           ))
      }
      val rps = ((totalSent.toFloat / totalTime) * 1000).toInt
      rps -> results
    }
    def printResults: ((Int, Results)) => Unit = {
      case (rpsValue, results) =>
        val rps = if (rpsValue < 1) "less then 1" else rpsValue.toString
        log.info(
          s"$rps, ${results.succeeded}, ${results.failed}, ${results.minLatency} ms, ${results.maxLatency} ms, ${results.avgLatency} ms"
        )
    }
    def printPercentiles(perc: List[(Int, Float)]): Unit = {
      log.info(perc.map { case (p, i) => s"$p - ${i.toDouble}" }.mkString(", "))
      println("percentile_values.csv:")
      println(perc.map(_._2).mkString(","))
      println(perc.map(_._1).mkString(","))
    }
    def calcPercentiles(responses: SeqView[Results], totalSize: Long): List[(Int, Float)] =
      responses
        .map(_.minLatency)
        .groupBy(a => a)
        .view
        .mapValues(_.toSeq.length)
        .toSeq
        .sortBy(_._1)
        .foldLeft(List.empty[(Int, Float)]) {
          case (acc, (responseTime, repeats)) =>
            (responseTime, repeats.toFloat / totalSize + acc.map(_._2).maxOption.getOrElse(0f)) :: acc
        }
        .reverse

    (for {
      http <- HttpClientZioBackend()
      start = System.currentTimeMillis()
      r <- combineRequests(http)
        .run(ZSink.collectAll)
        .map(_.toList)
      totalTime = calcLatency(start)
      responses = r.view
      _         = log.info("ALL REQUESTS:")
      _         = printResults(calcResults(responses, totalTime))
      _         = printPercentiles(calcPercentiles(responses, r.length))
      _ = if (appArgs.isInput) {
        log.info("INPUTS:")
        val inputs = r.filter(_.requestType == RequestType.Input)
        printResults(calcResults(inputs.view, totalTime))
        printPercentiles(calcPercentiles(inputs.view, inputs.length))
      }
      _ = if (appArgs.isUserBased) {
        log.info("USER-BASED QUERIES:")
        val userQueries = r.filter(_.requestType == RequestType.UserQuery)
        printResults(calcResults(userQueries.view, totalTime))
        printPercentiles(calcPercentiles(userQueries.view, userQueries.length))
      }
      _ = if (appArgs.isItemBased) {
        log.info("ITEM-BASED QUERIES:")
        val itemQueries = r.filter(_.requestType == RequestType.ItemQuery)
        printResults(calcResults(itemQueries.view, totalTime))
        printPercentiles(calcPercentiles(itemQueries.view, itemQueries.length))
      }
    } yield 0).exitCode
      .mapErrorCause { c =>
        log.error(s"Got error: ${c.prettyPrint}")
        Cause.empty
      }
  }
}

final case class Results(succeeded: Int,
                         failed: Int,
                         minLatency: Int,
                         maxLatency: Int,
                         avgLatency: Int,
                         requestType: RequestType.RequestType)

object Results {
  val empty: Results = Results(0, 0, 0, 0, 0, RequestType.Unknown)
}

object RequestType extends Enumeration {
  type RequestType = Value
  val Unknown   = Value(-1)
  val Input     = Value(0)
  val ItemQuery = Value(1)
  val UserQuery = Value(2)
}
