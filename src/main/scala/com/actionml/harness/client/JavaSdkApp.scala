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
import io.circe.{ Json, JsonObject }

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
import scala.language.postfixOps
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }

case class SearchEvent(event: String = "",
                       entityType: String = "",
                       entityId: String = "",
                       targetEntityId: String = "",
                       targetEntityType: String = "",
                       eventTime: String = "")

object JavaSdkApp {
  private def runEvents(a: RunArgs)(implicit system: ActorSystem, mat: Materializer): Future[(Int, Int)] =
    FileIO
      .fromPath(Paths.get(a.fileName))
      .via(Framing.delimiter(ByteString("\n"), 40960, allowTruncation = true).map(_.utf8String))
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
      .throttle(a.nPerSecond, 1.second, a.nPerSecond * 2, ThrottleMode.Shaping)
      .runFold((0, 0)) {
        case ((succeed, failed), (Success(resp), event)) =>
          if (resp.status != StatusCodes.CREATED) println(s"WRONG STATUS CODE for event $event")
          if ((succeed + failed) % 1000 == 0) println(succeed + failed)
          resp.discardEntityBytes()
          (succeed + 1, failed)
        case ((succeed, failed), (Failure(e), event)) =>
          println(s"ERROR ${e.getMessage} for event $event")
          e.printStackTrace()
          (succeed, failed + 1)
      }

  private def runSearch(a: RunArgs)(implicit system: ActorSystem, mat: Materializer): Future[Unit] = {
    import io.circe.generic.auto._
    import io.circe.parser._

    val mkItemQuery: Map[String, Any] => String = event =>
      s"""{"item":${event.getOrElse("targetEntityId", """ "" """)}}"""
    val mkUserQuery: Map[String, Any] => String = event => s"""{"user":${event.getOrElse("entityId", """ "" """)}}"""

    def sendQueries(mkQuery: Map[String, Any] => String, targetEntityType: String) =
      FileIO
        .fromPath(Paths.get(a.fileName))
        .via(Framing.delimiter(ByteString("\n"), 4096, allowTruncation = true).map(_.utf8String))
        .filter(s => s.contains(""""targetEntityId"""") && s.contains(""""targetEntityType""""))
        .sliding(1, step = scala.util.Random.nextInt(a.factor) + 1)
        .map(_.head)
        .mapAsyncUnordered(a.nThreads) { e =>
          for {
            event <- Future
              .fromTry(Try(decode[JsonObject](e).right.get))
              .recover {
                case e: Exception =>
                  e.printStackTrace()
                  JsonObject.empty
              }
            itemQuery = mkQuery(event.toMap.mapValues(_.noSpaces))
            r <- Marshal(itemQuery).to[RequestEntity].map { entity =>
              HttpRequest(
                method = HttpMethods.POST,
                uri = s"http://${a.harnessHost}:${a.harnessPort}/engines/${a.engineId}/queries",
                entity = entity.withContentType(`application/json`)
              ) -> e
            }
          } yield r
        }
        .throttle(a.nPerSecond, 1.second, a.nPerSecond * 2, ThrottleMode.Shaping)
        .via(Http().cachedHostConnectionPool[String](a.harnessHost, a.harnessPort))
        .withAttributes(ActorAttributes.supervisionStrategy(Supervision.resumingDecider))
        .runFold((0, 0)) {
          case ((succeed, failed), (Success(resp), query)) =>
            var goodResult = false
            Await.result(
              resp.entity.getDataBytes().asScala.runForeach { body =>
                if (resp.status != StatusCodes.CREATED) {
                  println(s"ERROR: WRONG STATUS CODE for search query $query")
                } else {
                  goodResult = true
                  println(s"${body.utf8String} is a result for search $query")
                }
              },
              1.minute
            )
            if ((succeed + failed) % 1000 == 0) println(succeed + failed)
            if (goodResult) (succeed + 1, failed) else (succeed, failed + 1)
          case ((succeed, failed), (Failure(e), query)) =>
            println(s"ERROR: ${e.getMessage} for event $query")
            e.printStackTrace()
            (succeed, failed + 1)
        }

    println("ITEM QUERIES:")
    for {
      _ <- runAndPrint(Instant.now, sendQueries(mkItemQuery))
      _ = println("USER QUERIES:")
      _ <- runAndPrint(Instant.now, sendQueries(mkUserQuery))
    } yield ()
  }

  private def runAndPrint(start: Instant, fn: Future[(Int, Int)]): Future[Unit] =
    fn.map {
        case (s, f) =>
          val dur = java.time.Duration.between(start, Instant.now).toMillis / 1000
          println(s"Sent ${s + f} requests")
          println(s"Succeeded $s")
          println(s"Failed $f")
          println(s"Took $dur seconds")
          println(s"${(s + f) / (if (dur == 0) 1 else dur)} requests per second")
      }
      .recover {
        case NonFatal(e) => e.printStackTrace()
      }

  def main(args: Array[String]): Unit = {
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
        println("INPUT EVENTS:")
        Await.result(runAndPrint(Instant.now, runEvents(a)), Duration.Inf)
        System.exit(0)
      case Some(a @ RunArgs(_, _, _, _, _, _, false, _)) =>
        println("SEARCH QUERIES:")
        Await.result(runSearch(a), Duration.Inf)
        System.exit(0)
      case _ =>
        System.exit(1)
    }
  }
}
