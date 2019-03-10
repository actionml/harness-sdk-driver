package com.actionml.harness.client

import scopt.{ DefaultOParserSetup, OParser, OParserSetup }

case class RunArgs(
    nThreads: Int,
    nPerSecond: Int,
    engineId: String,
    harnessHost: String,
    harnessPort: Int,
    fileName: String,
    input: Boolean,
    factor: Int
)

object RunArgs {
  def parse(args: Seq[String]): Option[RunArgs] = {

    val builder = OParser.builder[RunArgs]
    val parser = {
      import builder._
      OParser.sequence(
        programName("harness-events-cli.sh"),
        head("harness events", "0.1"),
        cmd("input")
          .required()
          .action((_, acc) => acc.copy(input = true)),
        cmd("query")
          .required()
          .action((_, acc) => acc.copy(input = false)),
        opt[Int]('c', "max-concurrent")
          .action((v, acc) => acc.copy(nThreads = v))
          .text("Number of parallel connections"),
        opt[Int]('r', "requests-per-second")
          .action((v, acc) => acc.copy(nPerSecond = v))
          .text("Number of requests sent per second"),
        opt[String]('e', "engine-id")
          .required()
          .action((v, acc) => acc.copy(engineId = v))
          .text("Engine id"),
        opt[String]('h', "host")
          .action((v, acc) => acc.copy(harnessHost = v))
          .text("Harness host"),
        opt[Int]('p', "port")
          .action((v, acc) => acc.copy(harnessPort = v))
          .text("Harness port"),
        opt[String]('f', "file")
          .required()
          .action((v, acc) => acc.copy(fileName = v))
          .text("Path to the file with events"),
        opt[Int]("factor")
          .validate { i =>
            if (i >= 1) success
            else failure("Option --factor should be > 0")
          }
          .action((v, acc) => acc.copy(factor = v))
          .text("Skip all events except one of factor. E.g. if factor is 10, then only 1 event of 10 will be sent.")
      )
    }
    val setup: OParserSetup = new DefaultOParserSetup {
      override def showUsageOnError = Some(true)
    }

    OParser.parse(parser,
                  args,
                  RunArgs(8, 10, "test-ur", "localhost", 9090, "events.json", input = true, factor = 1),
                  setup)
  }
}
