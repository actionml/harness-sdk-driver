package com.actionml.harness.client

import java.nio.file.Paths
import java.util.concurrent.CompletionStage

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.javadsl.model.{ HttpResponse, StatusCodes }
import akka.stream.Supervision.Directive
import akka.stream.javadsl.JavaFlowSupport.Source
import akka.stream.scaladsl.{ FileIO, Flow, Framing }
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings, Supervision }
import akka.util.ByteString
import com.actionml.EventsClient

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.java8.FuturesConvertersImpl._

object JavaSdkApp {
  def main(args: Array[String]): Unit =
    RunArgs.parse(args) match {
      case Some(a @ RunArgs(_, _, _, _, _, true, _)) =>
        val system = ActorSystem.apply("harness-load-test");
        val decider: akka.japi.function.Function[Throwable, Supervision.Directive] =
          new akka.japi.function.Function[Throwable, Supervision.Directive]() {
            override def apply(e: Throwable): Directive = {
              system.log.error(e, "Supervision error")
              Supervision.resume
            }
          }
        implicit val materializer =
          ActorMaterializer.create(ActorMaterializerSettings.create(system).withSupervisionStrategy(decider), system)

        val eventsClient = new EventsClient(a.engineId, a.harnessHost, a.harnessPort)
        FileIO
          .fromPath(Paths.get(a.fileName))
          .via(Framing.delimiter(ByteString("\n"), 2048, allowTruncation = false).map(_.utf8String))
          .via(sendFlow(eventsClient))
          .async
          .runFold(0)((acc, _) => {
            if (acc % 10000 == 0) println(acc)
            acc + 1
          })
          .foreach(println)
      case _ =>
        System.exit(1)
    }

  def sendFlow(client: EventsClient): Flow[String, String, NotUsed] =
    Flow[String]
      .flatMapConcat { s =>
        client
          .createRaw(s)
          .map {
            new akka.japi.function.Function[HttpResponse, String]() {
              override def apply(r: HttpResponse): String = {
                if (r.status != StatusCodes.CREATED) println(s"ERROR: wrong status code ${r.status}")
                s
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
