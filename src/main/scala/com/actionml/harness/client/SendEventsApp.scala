package com.actionml.harness.client

import java.nio.file.Paths
import java.time.Instant
import java.util.concurrent.Executors

import cats.effect.{ ExitCode, IO, IOApp, Resource }
import cats.implicits._
import com.actionml.{ EventsClient, QueriesClient }
import com.actionml.entity.Event
import fs2.{ Stream, io, text }

import scala.concurrent.ExecutionContext

object SendEventsApp extends IOApp {
  import _root_.io.circe.generic.auto._
  import _root_.io.circe.parser._
  import _root_.io.circe.syntax._
  case class URNavHintingEvent(event: String,
                               entityType: String,
                               entityId: String,
                               targetEntityId: Option[String] = None,
                               properties: Map[String, Boolean] = Map.empty,
                               conversionId: Option[String] = None,
                               eventTime: String)
      extends Event {
    override def toString() =
      this.asJson.noSpaces

    override def getEvent          = event
    override def getEntityId       = entityId
    override def getTargetEntityId = targetEntityId.getOrElse("empty")
  }

  private def inputSender(args: RunArgs): Stream[IO, Unit] = {
    val blockingExecutionContext = Resource.make(
      IO(ExecutionContext.fromExecutorService(Executors.newWorkStealingPool(args.nThreads)))
    )(ec => IO(ec.shutdown()))

    Stream.resource(blockingExecutionContext).flatMap { blockingEC =>
      val client                        = new EventsClient(args.engineId, args.harnessHost, args.harnessPort)
      def sendQuery(e: Event): IO[Unit] = IO(client.sendEvent(e))
      def createEvent: Stream[IO, String] => Stream[IO, Event] = _.map { s =>
        parse(s) match {
          case Right(json) =>
            val j = json.hcursor
            URNavHintingEvent(
              j.get[String]("event").toOption.getOrElse(throw new RuntimeException(s"Can't parse 'event' at $s")),
              j.get[String]("entityType")
                .toOption
                .getOrElse(throw new RuntimeException(s"Can't parse 'entityType' at $s")),
              j.get[String]("entityId").toOption.getOrElse(throw new RuntimeException(s"Can't parse 'entityId' at $s")),
              j.get[String]("targetEntityId").toOption,
              j.get[Map[String, Boolean]]("properties").toOption.getOrElse(Map.empty),
              j.get[String]("conversionId").toOption.orElse(Some("false")),
              j.get[String]("eventTime")
                .toOption
                .getOrElse(throw new RuntimeException(s"Can't parse 'eventTime' at $s"))
            )
          case Left(e) =>
            e.printStackTrace
            throw e
        }
      }

      val start = Instant.now
      io.file
        .readAll[IO](Paths.get(args.fileName), blockingEC, 4096)
        .through(text.utf8Decode)
        .through(text.lines)
        .filter(s => !s.trim.isEmpty)
        .through(createEvent)
        .parEvalMapUnordered(args.nThreads)(sendQuery)
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
  }

  private def querySender(args: RunArgs): Stream[IO, Unit] = {
    val blockingExecutionContext = Resource.make(
      IO(ExecutionContext.fromExecutorService(Executors.newWorkStealingPool(args.nThreads)))
    )(ec => IO(ec.shutdown()))

    Stream.resource(blockingExecutionContext).flatMap { blockingEC =>
      val client = new QueriesClient(args.engineId, args.harnessHost, args.harnessPort)
      def sendQuery(e: (String, String)): IO[Unit] =
        for {
          _ <- IO(client.sendQuery(e._1))
          _ <- IO(client.sendQuery(e._2))
        } yield ()
      def createEvent: Stream[IO, String] => Stream[IO, (String, String)] = _.map { s =>
        parse(s) match {
          case Right(json) =>
            val j = json.hcursor
            val (item, user) = (
              j.get[String]("targetEntityId")
                .toOption
                .getOrElse(throw new RuntimeException(s"Can't parse 'targetEntityId' at $s")),
              j.get[String]("event").toOption.getOrElse(throw new RuntimeException(s"Can't parse 'event' at $s"))
            )
            (s"""{"item":"$item"}""", s"""{"user":"$user"}""")
          case Left(e) =>
            e.printStackTrace
            throw e
        }
      }

      val start = Instant.now
      io.file
        .readAll[IO](Paths.get(args.fileName), blockingEC, 4096)
        .through(text.utf8Decode)
        .through(text.lines)
        .filter(s => !s.trim.isEmpty)
        .through(createEvent)
        .parEvalMapUnordered(args.nThreads)(sendQuery)
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
  }

  def run(args: List[String]): IO[ExitCode] =
    RunArgs.parse(args) match {
      case Some(a @ RunArgs(_, _, _, _, _, true)) =>
        for {
          _ <- IO(println(s"Started 'input' with ${a.nThreads} threads"))
          status <- inputSender(a).compile.drain
            .as(ExitCode.Success)
        } yield status
      case Some(a @ RunArgs(_, _, _, _, _, false)) =>
        for {
          _ <- IO(println(s"Started 'query' with ${a.nThreads} threads"))
          status <- querySender(a).compile.drain
            .as(ExitCode.Success)
        } yield status
      case None =>
        IO.unit.as(ExitCode.Success)
    }
}
