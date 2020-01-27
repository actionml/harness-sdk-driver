package com.actionml.harness.client

import java.io.FileInputStream

import logstage._
import sttp.client._
import sttp.client.asynchttpclient.ziostreams._
import zio._
import zio.duration._
import zio.stream.{ Sink, ZSink, ZStream }

object RunAgainstElasticsearch extends App {

  override def run(args: List[String]): ZIO[ZEnv, Nothing, Int] = {
    import Utils._
    val appArgs = RunArgs.parse(args).getOrElse { System.exit(1); throw new RuntimeException }
    val log = IzLogger(if (appArgs.isVerbose) Debug else if (appArgs.isVVerbose) Trace else Info,
                       Seq(ConsoleSink.text(colored = true), DefaultFileSink("logs")))

    def runSearches: ZIO[ZEnv, Throwable, Results] =
      for {
        httpBackend <- AsyncHttpClientZioStreamsBackend(this)
        results <- linesFromPath(appArgs.fileName)
          .throttleShape(appArgs.maxPerSecond, 1.second)(_ => 1)
          .zipWith(ZStream.fromIterable(LazyList.from(0)))((s, i) => i.flatMap(n => s.map(b => (n, b))))
          .mapMParUnordered(appArgs.nThreads) {
            case (requestNumber, request) =>
              val start = System.currentTimeMillis()
              log.debug(s"Sending $requestNumber $request")
              val uri = uri"""${appArgs.uri}/${appArgs.engineId}/_search"""
              httpBackend
                .send(basicRequest.body(request).header("Content-Type", "application/json").post(uri))
                .map { resp =>
                  val responseTime = calcLatency(start)
                  log.debug(s"Request $requestNumber got response ${resp.body} in $responseTime ms")
                  Results(if (resp.isSuccess) 1 else 0, if (resp.isServerError) 1 else 0, responseTime, responseTime)
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

    def calcLatency(start: Long): Int = (System.currentTimeMillis() - start).toInt

    val start = System.currentTimeMillis()
    (for {
      results <- runSearches
      requestsPerSecond = results.succeeded / (calcLatency(start) / 1000)
      _ = log.info(
        s"$requestsPerSecond, ${results.succeeded}, ${results.failed}, ${results.maxLatency} ms, ${results.avgLatency} ms"
      )
    } yield 0).mapErrorCause { c =>
      log.error(s"Got error: ${c.prettyPrint}")
      Cause.empty
    }
  }
}
