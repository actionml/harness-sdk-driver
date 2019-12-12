package com.actionml.harness.client

import scopt.{ DefaultOParserSetup, OParser, OParserSetup }

case class RunArgs(
    nCpus: Int,
    nThreads: Int,
    maxPerSecond: Int,
    engineId: String,
    harnessUri: String,
    fileName: String,
    input: Boolean,
    entityType: String,
    factor: Int,
    isVerbose: Boolean
)

object RunArgs {
  def parse(args: Seq[String]): Option[RunArgs] = {

    val builder = OParser.builder[RunArgs]
    val parser = {
      import builder._
      OParser.sequence(
        programName("harness-load-test.sh"),
        head("harness load test", "0.2"),
        cmd("input")
          .required()
          .action((_, acc) => acc.copy(input = true)),
        cmd("query")
          .required()
          .action((_, acc) => acc.copy(input = false)),
        opt[String]("entityType")
          .action((t, acc) => acc.copy(entityType = t))
          .text("Value of 'entityType' field to be used to create search queries"),
        opt[Int]('c', "thread-pool-size")
          .action((c, acc) => acc.copy(nCpus = c))
          .text("Thread pool size"),
        opt[Int]('n', "num-of-threads")
          .action((v, acc) => acc.copy(nThreads = v))
          .text("Number of parallel threads"),
        opt[Int]('r', "max-requests-per-second")
          .action((v, acc) => acc.copy(maxPerSecond = v))
          .text("Maximum number of requests sent per second"),
        opt[String]('e', "engine-id")
          .required()
          .action((v, acc) => acc.copy(engineId = v))
          .text("Engine id"),
        opt[String]('u', "uri")
          .action((u, acc) => acc.copy(harnessUri = u))
          .text("Harness server URI")
          .required(),
        opt[String]('f', "file")
          .required()
          .action((v, acc) => acc.copy(fileName = v))
          .text(
            "Path to the file with events. It can be a path to the file or directory. E.g. /tmp/event.json or /tmp. Directory means that all of it's files will be sent"
          ),
        opt[Unit]('v', "verbose")
          .action((_, acc) => acc.copy(isVerbose = true))
          .text("More info"),
        opt[Int]("factor")
          .validate { i =>
            if (i >= 1) success
            else failure("Option --factor should be > 0")
          }
          .action((v, acc) => acc.copy(factor = v))
          .text(
            "Skip all events except one of factor. E.g. if factor is 10, then only 1 event of the random value from 1 to 10 will be sent."
          )
      )
    }
    val setup: OParserSetup = new DefaultOParserSetup {
      override def showUsageOnError = Some(true)
    }

    OParser.parse(
      parser,
      args,
      RunArgs(nCpus = 4,
              nThreads = 32,
              maxPerSecond = 10000,
              "test-ur",
              "http://localhost:9090",
              "events.json",
              input = true,
              entityType = "user",
              factor = 10,
              isVerbose = false),
      setup
    )
  }
}
