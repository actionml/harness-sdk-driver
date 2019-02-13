package com.actionml.harness.client

import java.nio.file.Paths
import java.time.Instant
import java.util.concurrent.Executors

import cats.effect.{ ExitCode, IO, IOApp, Resource }
import cats.implicits._
import com.actionml.entity.Event
import SendEventsApp.URNavHintingEvent
import fs2.{ Stream, text }

import scala.concurrent.ExecutionContext

object MkQueries extends IOApp {

  import _root_.io.circe.parser._
  private val fileName = "/Users/andrey/Downloads/kaggle_ecom_events.json"
  private val blockingExecutionContext =
    Resource.make(IO(ExecutionContext.fromExecutorService(Executors.newWorkStealingPool)))(ec => IO(ec.shutdown()))
  private def parseEvent: Stream[IO, String] => Stream[IO, Event] = _.map { s =>
    parse(s) match {
      case Right(json) =>
        val j = json.hcursor
        URNavHintingEvent(
          j.get[String]("event").toOption.getOrElse(throw new RuntimeException(s"Can't parse 'event' at $s")),
          j.get[String]("entityType").toOption.getOrElse(throw new RuntimeException(s"Can't parse 'entityType' at $s")),
          j.get[String]("entityId").toOption.getOrElse(throw new RuntimeException(s"Can't parse 'entityId' at $s")),
          j.get[String]("targetEntityId").toOption,
          j.get[Map[String, Boolean]]("properties").toOption.getOrElse(Map.empty),
          j.get[String]("conversionId").toOption.orElse(Some("false")),
          j.get[String]("eventTime").toOption.getOrElse(throw new RuntimeException(s"Can't parse 'eventTime' at $s"))
        )
      case Left(e) =>
        e.printStackTrace
        throw e
    }
  }
  private val queryMaker: fs2.Stream[IO, Unit] = fs2.Stream.resource(blockingExecutionContext).flatMap { blockingEC =>
    val start = Instant.now
    fs2.io.file
      .readAll[IO](Paths.get(fileName), blockingEC, 4096)
      .through(text.utf8Decode)
      .through(text.lines)
      .filter(s => !s.trim.isEmpty)
      .through(parseEvent)
      .fold(Map.empty[String, Int]) {
        case (acc, e) =>
          acc.get(e.getTargetEntityId).fold(acc + (e.getTargetEntityId -> 1)) { v =>
            acc + (e.getTargetEntityId -> (v + 1))
          }
      }
      .map { m =>
        println(s"SIZE: ${m.size}")
      }
      .handleErrorWith { e =>
        e.printStackTrace
        fs2.Stream.empty
      }
      .onComplete {
        val duration = Instant.now.toEpochMilli - start.toEpochMilli
        println(s"Completed in $duration milliseconds (${duration / 1000 / 60} minutes)")
        Stream.empty
      }
  }

  override def run(args: List[String]): IO[ExitCode] =
    queryMaker.compile.drain.as(ExitCode.Success)
}
