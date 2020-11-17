import sbt._
import sbt.Keys.resolvers
import Dependencies._

lazy val root = (project in file(".")).
  settings(
    inThisBuild(List(
      organization := "com.actionml",
      maintainer := "actionml.com",
      scalaVersion := "2.13.3",
      version      := "0.2.0-SNAPSHOT"
    )),
    addCompilerPlugin("com.olegpy"    %% "better-monadic-for" % "0.3.0"),
    name := "harness-load-test",
    resolvers += Resolver.mavenLocal,
    resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
    resolvers += "Sonatype OSS Releases" at "https://oss.sonatype.org/content/repositories/releases",
    libraryDependencies ++= Seq(scopt, logstage) ++ circe ++ zio ++ http4s ++ sttp
  ).enablePlugins(JavaAppPackaging)
