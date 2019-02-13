import sbt._

object Dependencies {
  lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.0.5"
  lazy val fs2_core  = "co.fs2"        %% "fs2-core"  % "1.0.0"
  lazy val fs2_io    = "co.fs2"        %% "fs2-io"    % "1.0.0"
  lazy val circe = Seq(
    "io.circe" %% "circe-core"    % "0.10.1",
    "io.circe" %% "circe-parser"  % "0.10.1",
    "io.circe" %% "circe-generic" % "0.10.1"
  )
  lazy val scopt    = "com.github.scopt" %% "scopt"           % "4.0.0-RC2"
  lazy val java_sdk = "com.actionml"     % "harness-java-sdk" % "0.3.0"
}
