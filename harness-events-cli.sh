#!/usr/bin/env bash
java -cp target/scala-2.11/harness-client-cli_2.11-0.1.0-SNAPSHOT.jar:target/universal/stage/lib/org.scala-lang.scala-library-2.11.12.jar:target/universal/stage/lib/ch.qos.logback.logback-classic-1.2.3.jar:target/universal/stage/lib/ch.qos.logback.logback-core-1.2.3.jar:target/universal/stage/lib/co.fs2.fs2-core_2.11-1.0.0.jar:target/universal/stage/lib/co.fs2.fs2-io_2.11-1.0.0.jar:target/universal/stage/lib/com.actionml.harness-java-sdk-0.3.0.jar:target/universal/stage/lib/com.chuusai.shapeless_2.11-2.3.3.jar:target/universal/stage/lib/com.example.harness-load-test-0.1.0-SNAPSHOT.jar:target/universal/stage/lib/com.github.scopt.scopt_2.11-4.0.0-RC2.jar:target/universal/stage/lib/com.google.code.gson.gson-2.8.0.jar:target/universal/stage/lib/com.google.guava.guava-21.0.jar:target/universal/stage/lib/com.typesafe.akka.akka-actor_2.11-2.5.12.jar:target/universal/stage/lib/com.typesafe.akka.akka-http-core_2.11-10.1.1.jar:target/universal/stage/lib/com.typesafe.akka.akka-http_2.11-10.1.1.jar:target/universal/stage/lib/com.typesafe.akka.akka-parsing_2.11-10.1.1.jar:target/universal/stage/lib/com.typesafe.akka.akka-protobuf_2.11-2.5.12.jar:target/universal/stage/lib/com.typesafe.akka.akka-slf4j_2.11-2.5.12.jar:target/universal/stage/lib/com.typesafe.akka.akka-stream_2.11-2.5.12.jar:target/universal/stage/lib/com.typesafe.config-1.3.2.jar:target/universal/stage/lib/com.typesafe.ssl-config-core_2.11-0.2.3.jar:target/universal/stage/lib/harness-load-test_2.11-0.1.0-SNAPSHOT.jar:target/universal/stage/lib/io.circe.circe-core_2.11-0.10.1.jar:target/universal/stage/lib/io.circe.circe-generic_2.11-0.10.1.jar:target/universal/stage/lib/io.circe.circe-jawn_2.11-0.10.1.jar:target/universal/stage/lib/io.circe.circe-numbers_2.11-0.10.1.jar:target/universal/stage/lib/io.circe.circe-parser_2.11-0.10.1.jar:target/universal/stage/lib/joda-time.joda-time-2.9.9.jar:target/universal/stage/lib/org.reactivestreams.reactive-streams-1.0.2.jar:target/universal/stage/lib/org.scala-lang.modules.scala-java8-compat_2.11-0.7.0.jar:target/universal/stage/lib/org.scala-lang.modules.scala-parser-combinators_2.11-1.1.0.jar:target/universal/stage/lib/org.scala-lang.scala-library-2.11.12.jar:target/universal/stage/lib/org.scala-lang.scala-reflect-2.11.12.jar:target/universal/stage/lib/org.scodec.scodec-bits_2.11-1.1.5.jar:target/universal/stage/lib/org.slf4j.slf4j-api-1.7.25.jar:target/universal/stage/lib/org.spire-math.jawn-parser_2.11-0.13.0.jar:target/universal/stage/lib/org.typelevel.cats-core_2.11-1.4.0.jar:target/universal/stage/lib/org.typelevel.cats-effect_2.11-1.0.0.jar:target/universal/stage/lib/org.typelevel.cats-kernel_2.11-1.4.0.jar:target/universal/stage/lib/org.typelevel.cats-macros_2.11-1.4.0.jar:target/universal/stage/lib/org.typelevel.machinist_2.11-0.6.5.jar:target/universal/stage/lib/org.typelevel.macro-compat_2.11-1.1.1.jar com.actionml.harness.client.SendEventsApp "$@"
