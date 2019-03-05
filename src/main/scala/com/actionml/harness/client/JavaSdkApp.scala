package com.actionml.harness.client

import java.nio.file.Paths
import java.util.concurrent.CompletionStage

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.javadsl.model.{ HttpResponse, StatusCode, StatusCodes }
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model.{ HttpMethods, HttpRequest, RequestEntity }
import akka.stream.Supervision.Directive
import akka.stream.scaladsl.{ FileIO, Flow, Framing }
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings, Supervision }
import akka.util.ByteString
import com.actionml.EventsClient

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.concurrent.java8.FuturesConvertersImpl._
import scala.util.{ Failure, Success }

object JavaSdkApp {
  def main(args: Array[String]): Unit =
    RunArgs.parse(args) match {
      case Some(a @ RunArgs(parallelism, _, _, _, _, true, _)) =>
        implicit val system = ActorSystem.apply("harness-load-test")
        val decider: akka.japi.function.Function[Throwable, Supervision.Directive] =
          new akka.japi.function.Function[Throwable, Supervision.Directive]() {
            override def apply(e: Throwable): Directive = {
              system.log.error(e, "Supervision error")
              Supervision.resume
            }
          }
        implicit val materializer =
          ActorMaterializer.create(ActorMaterializerSettings.create(system).withSupervisionStrategy(decider), system)

        FileIO
          .fromPath(Paths.get(a.fileName))
          .via(Framing.delimiter(ByteString("\n"), 4096, allowTruncation = false).map(_.utf8String))
          .mapAsync(a.nThreads)(e => {
            Marshal(e).to[RequestEntity].map { entity =>
              HttpRequest(
                method = HttpMethods.POST,
                uri = s"http://${a.harnessHost}:${a.harnessPort}/engines/${a.engineId}/events",
                entity = entity.withContentType(`application/json`)
              ) -> e
            }
          })
          .via(Http().cachedHostConnectionPool[String](a.harnessHost, a.harnessPort))
          .throttle(10, 1.second)
          .runFold(0) {
            case (acc, (Success(resp), event)) =>
              if (resp.status != StatusCodes.CREATED) println(s"WRONG STATUS CODE for event $event")
              if (acc % 100000 == 0) println(acc)
              acc + 1
            case (acc, (Failure(e), event)) =>
              println(s"ERROR ${e.getMessage} for event $event")
              e.printStackTrace
              acc + 1
          }
          .foreach(println)
      case _ =>
        System.exit(1)
    }

  def sendFlow(client: EventsClient): Flow[String, StatusCode, NotUsed] =
    Flow[String]
      .flatMapConcat { s =>
        client
          .createRaw(s)
          .map {
            new akka.japi.function.Function[HttpResponse, StatusCode]() {
              override def apply(r: HttpResponse): StatusCode = {
                if (r.status != StatusCodes.CREATED) println(s"ERROR: wrong status code ${r.status}")
                r.status
              }
            }
          }
      }

  def toScala[T](cs: CompletionStage[T]): Future[T] =
    cs match {
      case cf: CF[T] => cf.wrapped
      case _ =>
        val p = new P[T](cs)
        cs whenComplete p
        p.future
    }
}
