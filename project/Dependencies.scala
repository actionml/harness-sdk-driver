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
    "com.softwaremill.sttp.client3" %% "httpclient-backend-zio" % "3.0.0-RC9",
    "com.softwaremill.sttp.client3" %% "core"                   % "3.0.0-RC9",
    "com.softwaremill.sttp.client3" %% "circe"                  % "3.0.0-RC9",
  )
  val logstage = "io.7mind.izumi" %% "logstage-core" % "1.0.0-M1"
}
