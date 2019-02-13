package com.actionml.harness.client

import scopt.{ DefaultOParserSetup, OParser, OParserSetup }

case class RunArgs(
    nThreads: Int,
    engineId: String,
    harnessHost: String,
    harnessPort: Int,
    fileName: String,
    input: Boolean
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
          .text("Path to the file with events")
      )
    }
    val setup: OParserSetup = new DefaultOParserSetup {
      override def showUsageOnError = Some(true)
    }

    OParser.parse(parser, args, RunArgs(20, "test-ur", "localhost", 9090, "events.json", input = true), setup)
  }
}
