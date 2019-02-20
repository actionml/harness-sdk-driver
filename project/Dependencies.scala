import sbt._

object Dependencies {
  lazy val fs2_core = "co.fs2" %% "fs2-core" % "1.0.0"
  lazy val fs2_io   = "co.fs2" %% "fs2-io"   % "1.0.0"
  lazy val circe = Seq(
    "io.circe" %% "circe-core"    % "0.10.1",
    "io.circe" %% "circe-parser"  % "0.10.1",
    "io.circe" %% "circe-generic" % "0.10.1"
  )
  lazy val scopt        = "com.github.scopt" %% "scopt"           % "4.0.0-RC2"
  lazy val spinoco_http = "com.spinoco"      %% "fs2-http"        % "0.4.1"
  lazy val java_sdk     = "com.actionml"     % "harness-java-sdk" % "0.3.0"
}
