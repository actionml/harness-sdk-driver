package com.actionml.harness.client

import java.nio.file.Paths
import java.time.Instant

import akka.actor.ActorSystem
import akka.http.javadsl.model.StatusCodes
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model.{ HttpMethods, HttpRequest, RequestEntity }
import akka.stream.Supervision.Directive
import akka.stream.scaladsl.{ FileIO, Framing }
import akka.stream._
import akka.util.ByteString

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
import scala.language.postfixOps
import scala.util.{ Failure, Success, Try }

case class SearchEvent(event: String = "",
                       entityType: String = "",
                       entityId: String = "",
                       targetEntityId: String = "",
                       targetEntityType: String = "",
                       eventTime: String = "")

object JavaSdkApp {
  private def runEvents(a: RunArgs)(implicit system: ActorSystem, mat: Materializer): Unit =
    FileIO
      .fromPath(Paths.get(a.fileName))
      .via(Framing.delimiter(ByteString("\n"), 40960, allowTruncation = false).map(_.utf8String))
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
      .throttle(1, 1.second, a.nPerSecond, ThrottleMode.Shaping)
      .runFold(0) {
        case (acc, (Success(resp), event)) =>
          if (resp.status != StatusCodes.CREATED) println(s"WRONG STATUS CODE for event $event")
          if (acc % 1000 == 0) println(acc)
          resp.discardEntityBytes()
          acc + 1
        case (acc, (Failure(e), event)) =>
          println(s"ERROR ${e.getMessage} for event $event")
          e.printStackTrace()
          acc + 1
      }
      .foreach(println)

  private def runSearch(a: RunArgs)(implicit system: ActorSystem, mat: Materializer): Unit = {
    import io.circe.generic.auto._
    import io.circe.parser._

    val mkItemQuery: SearchEvent => String = event => s"""{"item":"${event.targetEntityId}"}"""
    val mkUserQuery: SearchEvent => String = event => s"""{"user":"${event.entityId}"}"""

    def sendQueries(mkQuery: SearchEvent => String) =
      FileIO
        .fromPath(Paths.get(a.fileName))
        .via(Framing.delimiter(ByteString("\n"), 4096, allowTruncation = true).map(_.utf8String))
        .filter(_.contains(""""event":"fco-view""""))
        .sliding(1, step = scala.util.Random.nextInt(a.factor))
        .map(_.head)
        .mapAsyncUnordered(a.nThreads) { e =>
          for {
            event <- Future
              .fromTry(Try(decode[SearchEvent](e).right.get))
              .recover {
                case e: Exception =>
                  e.printStackTrace()
                  SearchEvent()
              }
            itemQuery = mkQuery(event)
            r <- Marshal(itemQuery).to[RequestEntity].map { entity =>
              HttpRequest(
                method = HttpMethods.POST,
                uri = s"http://${a.harnessHost}:${a.harnessPort}/engines/${a.engineId}/queries",
                entity = entity.withContentType(`application/json`)
              ) -> e
            }
          } yield r
        }
        .throttle(if (a.nPerSecond > 1) a.nPerSecond / 2 else 1, 1.second, a.nPerSecond, ThrottleMode.Shaping)
        .via(Http().cachedHostConnectionPool[String](a.harnessHost, a.harnessPort))
        .withAttributes(ActorAttributes.supervisionStrategy(Supervision.resumingDecider))
        .runFold((0, 0)) {
          case (acc, (Success(resp), query)) =>
            var goodResult = 0
            Await.result(
              resp.entity.getDataBytes().asScala.runForeach { body =>
                if (resp.status != StatusCodes.CREATED) {
                  goodResult = 0
                  println(s"WRONG STATUS CODE for search query $query")
                } else if (body.utf8String.contains("[]")) {
                  goodResult = 0
                  println("EMPTY RESPONSE BODY")
                } else {
                  goodResult = 1
                  println(s"${body.utf8String} is a result for search $query")
                }
              },
              1.minute
            )
            if (acc._1 % 10 == 0) println(acc._1)
            (acc._1 + 1, acc._2 + goodResult)
          case (acc, (Failure(e), query)) =>
            println(s"ERROR ${e.getMessage} for event $query")
            e.printStackTrace()
            (acc._1 + 1, acc._2)
        }

    sendQueries(mkItemQuery).foreach {
      case (sent, succeed) =>
        println(s"Sent $sent item queries, succeeded $succeed [${Instant.now}]")
    }
    sendQueries(mkUserQuery).foreach {
      case (sent, succeed) =>
        println(s"Sent $sent user queries, succeeded $succeed [${Instant.now}]")
    }
  }

  def main(args: Array[String]): Unit = {
    println(Instant.now)
    implicit val system = ActorSystem.apply("harness-client")
    val decider: akka.japi.function.Function[Throwable, Supervision.Directive] =
      new akka.japi.function.Function[Throwable, Supervision.Directive]() {
        override def apply(e: Throwable): Directive = {
          e.printStackTrace()
          system.log.error(e, "Supervision error")
          Supervision.resume
        }
      }
    implicit val materializer =
      ActorMaterializer.create(ActorMaterializerSettings.create(system).withSupervisionStrategy(decider), system)
    RunArgs.parse(args) match {
      case Some(a @ RunArgs(_, _, _, _, _, _, true, _)) =>
        runEvents(a)
      case Some(a @ RunArgs(_, _, _, _, _, _, false, _)) =>
        runSearch(a)
      case _ =>
        System.exit(1)
    }
  }
}
