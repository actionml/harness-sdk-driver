import sbt._
import sbt.Keys.resolvers
import Dependencies._

lazy val root = (project in file(".")).
  settings(
    inThisBuild(List(
      organization := "com.actionml",
      scalaVersion := "2.13.1",
      version      := "0.2.0-SNAPSHOT"
    )),
    addCompilerPlugin("org.typelevel" %% "kind-projector"     % "0.10.3"),
    addCompilerPlugin("com.olegpy"    %% "better-monadic-for" % "0.3.0"),
    name := "harness-load-test",
    resolvers += Resolver.mavenLocal,
    resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
    resolvers += "Sonatype OSS Releases" at "https://oss.sonatype.org/content/repositories/releases",
    libraryDependencies ++= Seq(
      scopt,
    ) ++ circe ++ sttp ++ zio ++ http4s
  ).enablePlugins(JavaAppPackaging)
