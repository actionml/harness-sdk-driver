package com.actionml.harness.client

import java.nio.file.Paths
import java.time.Instant
import java.util.Date
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

  case class UREvent(eventId: Option[String],
                     event: String,
                     entityType: String,
                     entityId: String,
                     targetEntityId: Option[String] = None,
                     dateProps: Map[String, Date] = Map.empty,
                     categoricalProps: Map[String, Seq[String]] = Map.empty,
                     floatProps: Map[String, Float] = Map.empty,
                     booleanProps: Map[String, Boolean] = Map.empty,
                     eventTime: Date)
      extends Event

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
            import _root_.io.circe.Decoder
            implicit val decoderEvent: Decoder[Date] = {
              Decoder[String].map { s =>
                new Date(Instant.parse(s).toEpochMilli)
              }
            }
            val j = json.hcursor
            UREvent(
              j.get[String]("eventId").toOption,
              j.get[String]("event").toOption.getOrElse(throw new RuntimeException(s"Can't parse 'event' at $s")),
              j.get[String]("entityType")
                .toOption
                .getOrElse(throw new RuntimeException(s"Can't parse 'entityType' at $s")),
              j.get[String]("entityId").toOption.getOrElse(throw new RuntimeException(s"Can't parse 'entityId' at $s")),
              j.get[String]("targetEntityId").toOption,
              j.get[Map[String, Date]]("dateProps").toOption.getOrElse(Map.empty),
              j.get[Map[String, Seq[String]]]("categoricalProps").toOption.getOrElse(Map.empty),
              j.get[Map[String, Float]]("floatProps").toOption.getOrElse(Map.empty),
              j.get[Map[String, Boolean]]("booleanProps").toOption.getOrElse(Map.empty),
              j.get[Date]("eventTime")
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
      val s = io.file
        .readAll[IO](Paths.get(args.fileName), blockingEC, 4096)
        .through(text.utf8Decode)
        .through(text.lines)
        .filter(s => !s.trim.isEmpty);

      {
        if (args.factor == 1) s
        else
          s.sliding(args.factor)
            .map(_.apply(scala.util.Random.nextInt(args.factor - 1)))
      }.through(createEvent)
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
      case Some(a @ RunArgs(_, _, _, _, _, true, _)) =>
        for {
          _ <- IO(println(s"Started 'input' with ${a.nThreads} threads"))
          status <- inputSender(a).compile.drain
            .as(ExitCode.Success)
        } yield status
      case Some(a @ RunArgs(_, _, _, _, _, false, _)) =>
        for {
          _ <- IO(println(s"Started 'query' with ${a.nThreads} threads"))
          status <- querySender(a).compile.drain
            .as(ExitCode.Success)
        } yield status
      case None =>
        IO.unit.as(ExitCode.Success)
    }
}
