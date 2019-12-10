package com.actionml.harness.client

import cats.arrow.FunctionK
import cats.effect
import cats.effect.{ Blocker, Clock }
import io.circe.Json
import org.http4s.{ EntityBody, EntityDecoder, EntityEncoder, Request }
import org.http4s.client.Client
import zio._

import scala.io.Source

object LoadTest extends App {
  import console._
  import zio._
  import zio.stream._

  override def run(args: List[String]): ZIO[zio.ZEnv, Nothing, Int] = {
    def mkHttp4sClient() = {
      import java.util.concurrent._

      import org.http4s.client._
      import org.http4s.client.middleware.Metrics
      import org.http4s.metrics.prometheus.Prometheus
      import zio.interop.catz._

      import scala.concurrent.ExecutionContext

      val blockingEC =
        Blocker.liftExecutionContext(ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(5)))
      val httpClient = JavaNetClientBuilder[Task](blockingEC).create

      val requestMethodClassifier = (r: Request[Task]) => Some(r.method.toString.toLowerCase)

      implicit val clock = Clock.create[Task]
      val a = (for {
        registry <- Prometheus.collectorRegistry[Task]
        metrics  <- Prometheus.metricsOps[Task](registry, "harness_load_test")
      } yield Metrics[Task](metrics, requestMethodClassifier)(httpClient))
      a.toManagedZIO
    }
    def runEvents(runArgs: RunArgs, env: ZEnv, httpClient: Client[Task]) = {
      def mkRequest(body: String): Request[Task] = {
        import io.circe.literal._
        import org.http4s.circe._
        import org.http4s.client.dsl.io._
        import org.http4s.dsl.io._
        import org.http4s.implicits._
        import zio.interop.catz._
        implicit def body2body: EntityBody[cats.effect.IO] => EntityBody[Task] = body => {
          body.translate {
            new FunctionK[cats.effect.IO, Task] {
              override def apply[A](fa: effect.IO[A]): Task[A] =
                Task(fa.unsafeRunSync())
            }
          }
        }
        implicit def one2(r: Request[cats.effect.IO]): Request[Task] = new Request[Task](
          method = r.method,
          uri = r.uri,
          headers = r.headers,
          body = r.body
        )
        import io.circe.parser._
        val j = parse(body).getOrElse(throw new RuntimeException)
        POST(json"""$j""", uri"http://localhost:9099/engines/test_ur/events").unsafeRunSync()
      }
      val source   = Source.fromFile(runArgs.fileName, "UTF-8") // then close
      val iterable = source.getLines().to(Iterable)
      import org.http4s.circe._
      import zio.interop.catz._
      val runtime = new DefaultRuntime {}
      for {
        _ <- putStrLn("Starting events")
        results <- zio.stream.Stream
          .fromIterable(iterable)
          .mapMParUnordered(runArgs.nThreads) { body =>
            ZIO(runtime.unsafeRun(httpClient.expect(mkRequest(body))))
          }
          .runDrain
      } yield results
    }

    def runSearches(runArgs: RunArgs) = ???

    mkHttp4sClient()
      .use { client =>
        for {
          runArgs <- UIO(RunArgs.parse(args)).flatMap(_.map(UIO(_)).getOrElse(ZIO.interrupt))
          _       <- putStrLn(s"Running with arguments: $runArgs")
          _       <- if (runArgs.input) runEvents(runArgs, environment, client) else runSearches(runArgs)
          _       <- putStrLn("Done executing")
        } yield 0
      }
      .mapErrorCause { c =>
        println(c.prettyPrint)
        Cause.empty
      }
  }
}
