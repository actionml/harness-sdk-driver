package com.actionml.harness.client

import java.nio.channels.AsynchronousChannelGroup
import java.nio.file.Paths
import java.time.Instant
import java.util.concurrent.Executors

import cats.effect.{ ExitCode, IO, IOApp, Resource }
import cats.implicits._
import fs2.{ Stream, io, text }
import scodec.Attempt
import spinoco.fs2.http
import spinoco.fs2.http._
import spinoco.fs2.http.body.BodyEncoder
import spinoco.protocol.http.{ HttpStatusCode, Uri }
import spinoco.protocol.mime.ContentType.TextContent
import spinoco.protocol.mime.{ MIMECharset, MediaType }

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

object SendEventsApp extends IOApp {

  private def inputSender(args: RunArgs): Stream[IO, Unit] = {
    val blockingExecutionContext = Resource.make(
      IO(ExecutionContext.fromExecutorService(Executors.newWorkStealingPool(args.nThreads)))
    )(ec => IO(ec.shutdown()))

    Stream.resource(blockingExecutionContext).flatMap { blockingEC =>
      implicit val ACG = AsynchronousChannelGroup.withThreadPool(blockingEC)
      def sendEvent(client: HttpClient[IO])(s: String): IO[Unit] = {
        implicit val jsonEncoder =
          BodyEncoder.utf8String.withContentType(TextContent(MediaType.`application/json`, Some(MIMECharset.`UTF-8`)))
        val request = HttpRequest
          .post[IO, String](Uri.http(args.harnessHost, args.harnessPort, s"/engines/${args.engineId}/events"), s)
        client
          .request(request)
          .flatMap[IO, Attempt[String]] { r =>
            if (r.header.status == HttpStatusCode.Created) Stream.eval(r.bodyAsString)
            else Stream.eval(IO.raiseError(new RuntimeException("Wrong status code from server")))
          }
          .compile
          .drain
      }

      val start = Instant.now
      var count = 0L
      for {
        client <- Stream.eval(http.client[IO]().timeout(10.seconds))
        _ <- io.file
          .readAll[IO](Paths.get(args.fileName), blockingEC, 4096)
          .through(text.utf8Decode.andThen(text.lines))
          .filter(_.trim.nonEmpty)
//          .parEvalMapUnordered(args.nThreads)(sendEvent(client))
          .evalMap(sendEvent(client))
          .map(_ => count = count + 1)
          .handleErrorWith { e =>
            e.printStackTrace
            fs2.Stream.empty
          }
          .onComplete {
            val duration = Instant.now.toEpochMilli - start.toEpochMilli
            println(s"Completed in $duration milliseconds (${duration / 1000 / 60} minutes)")
            println(s"Sent $count events")
            Stream.empty
          }
      } yield ()
    }
  }

  private def querySender(args: RunArgs): Stream[IO, Unit] = {
    val blockingExecutionContext = Resource.make(
      IO(ExecutionContext.fromExecutorService(Executors.newWorkStealingPool(args.nThreads)))
    )(ec => IO(ec.shutdown()))

    Stream.resource(blockingExecutionContext).flatMap { blockingEC =>
//      def sendQuery(e: (String, String)): IO[Unit] =
      def sendQuery(e: String): IO[Unit] =
        ???
//        for {
//          _ <- IO(client.sendQuery(e._1))
//          _ <- IO(client.sendQuery(e._2))
//        } yield ()

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
      }.parEvalMapUnordered(args.nThreads)(sendQuery)
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
