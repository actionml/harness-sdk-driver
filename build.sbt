import Dependencies._

lazy val root = (project in file(".")).
  settings(
    inThisBuild(List(
      organization := "com.actionml",
      scalaVersion := "2.11.12",
      version      := "0.1.0-SNAPSHOT"
    )),
    name := "harness-load-test",
    resolvers += Resolver.mavenLocal,
    libraryDependencies ++= Seq(
      fs2_core,
      fs2_io,
      scopt,
      spinoco_http,
      java_sdk
    ) ++ circe
  ).enablePlugins(JavaAppPackaging)
