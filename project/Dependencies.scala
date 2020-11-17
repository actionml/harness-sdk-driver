import sbt._

object Dependencies {
  private val circeVersion = "0.12.3"
  val circe = Seq(
    "io.circe" %% "circe-core"    % circeVersion,
    "io.circe" %% "circe-parser"  % circeVersion,
    "io.circe" %% "circe-generic" % circeVersion,
    "io.circe" %% "circe-literal" % circeVersion
  )
  val scopt    = "com.github.scopt" %% "scopt"           % "4.0.0-RC2"
  val java_sdk = "com.actionml"     % "harness-java-sdk" % "0.3.0"
  val zio = Seq(
    "dev.zio" %% "zio"         % "1.0.3",
    "dev.zio" %% "zio-streams" % "1.0.3",
  )
  val sttp = Seq(
    "com.softwaremill.sttp.client" %% "core"                                  % "2.0.0-RC6",
    "com.softwaremill.sttp.client" %% "async-http-client-backend-zio-streams" % "2.0.0-RC6",
    "com.softwaremill.sttp.client" %% "circe"                                 % "2.0.0-RC6"
  )
  private val http4sVersion = "0.21.0-M6"
  val http4s = Seq(
    "org.http4s"     %% "http4s-blaze-client" % http4sVersion,
    "org.http4s"     %% "http4s-circe"        % http4sVersion,
    "org.http4s"     %% "http4s-dsl"          % http4sVersion,
    "ch.qos.logback" % "logback-classic"      % "1.2.3"
  )
  val logstage = "io.7mind.izumi" %% "logstage-core" % "0.10.0-M7"
}
