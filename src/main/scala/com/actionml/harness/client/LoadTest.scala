package com.actionml.harness.client

import io.circe.Json
import io.circe.literal._
import io.circe.parser._
import logstage._
import sttp.client3._
import sttp.client3.httpclient.zio.HttpClientZioBackend
import sttp.model.Uri
import zio._
import zio.blocking.Blocking
import zio.duration._
import zio.stream.{ Sink, ZStream }

object LoadTest extends App {

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    import Utils._
    val appArgs = RunArgs.parse(args).getOrElse { System.exit(1); throw new RuntimeException }
    val log = IzLogger(if (appArgs.isVerbose) Debug else if (appArgs.isVVerbose) Trace else Info,
                       Seq(ConsoleSink.text(colored = true)))
    val inputUri = uri"${appArgs.uri}/engines/${appArgs.engineId}/events"
    val queryUri = uri"${appArgs.uri}/engines/${appArgs.engineId}/queries"

    def sendLineByLine(uri: Uri,
                       lines: ZStream[Blocking, Nothing, String],
                       filter: String => Boolean = _ => true): RIO[ZEnv, (Long, Results)] = {
      val defaultRequest = basicRequest
        .readTimeout(appArgs.timeout)
        .followRedirects(true)
        .maxRedirects(3)
        .redirectToGet(false)
        .header("Content-Type", "application/json")
        .post(uri)

      for {
        http <- HttpClientZioBackend()
        globalStart = System.currentTimeMillis()
        results <- lines
          .drop(scala.util.Random.nextLong(appArgs.factor * appArgs.nThreads))
          .filter(filter)
          .zipWithIndex
          .collect {
            case (r, i) if i % appArgs.factor == 0 => r
          }
          .throttleShape(appArgs.maxPerSecond, 1.second)(_.size)
          .mapMPar(appArgs.nThreads) { request =>
            val start = System.currentTimeMillis()
            log.trace(s"Sending $request")
            defaultRequest
              .body(request)
              .send(http)
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
              }
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
          .run(Sink.foldLeft((1, (0, 0, 0, 0, 0))) {
            (acc: (Int, (Int, Int, Int, Int, Int)), result: (Int, Int, Int, Int, Int)) =>
              (acc._1 + 1,
               (
                 acc._2._1 + result._1,
                 acc._2._2 + result._2,
                 if (acc._2._3 != 0) Math.min(acc._2._3, result._3) else result._3,
                 Math.max(acc._2._4, result._4),
                 acc._2._5 + (result._5 - acc._2._5) / (acc._1 + 1)
               ))
          })
      } yield (globalStart, (Results.apply _) tupled results._2)
    }.provideCustomLayer(HttpClientZioBackend.layer())

    def runQueries(userBased: Boolean, itemBased: Boolean): ZIO[ZEnv, Throwable, (Long, Results)] = {
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

      val lines = linesFromPath(appArgs.fileName).zipWithIndex.flatMap {
        case (s, i) =>
          (if (itemBased) mkItemBasedQuery(s) else ZStream.empty) ++
          (if (userBased && (i % (100 / appArgs.userBasedWeight) == 0)) mkUserBasedQuery(s) else ZStream.empty)
      }
      for {
        r <- sendLineByLine(queryUri, lines)
      } yield r

    }

    def calcLatency(start: Long): Int = (System.currentTimeMillis() - start + 1).toInt

    log.info(s"Started with arguments: $appArgs")
    val setsFilter: String => Boolean = s => !(appArgs.skipSets && s.contains("$set"))
    val lines                         = linesFromPath(appArgs.fileName)

    (for {
      (start, results) <- (if (!appArgs.skipInput) sendLineByLine(inputUri, lines, filter = setsFilter)
                           else ZIO.succeed(0 -> Results.empty))
        .zipPar {
          if (!appArgs.skipQuery) runQueries(userBased = appArgs.isUserBased, itemBased = appArgs.isItemBased)
          else ZIO.succeed(0 -> Results.empty)
        }
        .map { case ((al: Long, ar), (bl: Long, br)) => (Math.min(al, bl), ar.sum(br)) }
      requestsPerSecond = (results.succeeded + results.failed) / (calcLatency(start) / 1000 + 1)
      _ = log.info(
        s"$requestsPerSecond, ${results.succeeded}, ${results.failed}, ${results.minLatency} ms, ${results.maxLatency} ms, ${results.avgLatency} ms"
      )
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
