package com.actionml.harness.client

import scopt.{ DefaultOParserSetup, OParser, OParserSetup }

import scala.concurrent.duration.FiniteDuration

case class RunArgs(
    nThreads: Int,
    maxPerSecond: Int,
    engineId: String,
    uri: String,
    fileName: String,
    inputWeight: Double,
    userBasedWeight: Double,
    itemBasedWeight: Double,
    isAllItems: Boolean,
    filterByItemEvent: String,
    factor: Int,
    isVerbose: Boolean,
    isVVerbose: Boolean,
    timeout: FiniteDuration,
    nRetries: Int,
    skipSets: Boolean,
) {
  def isInput: Boolean             = inputWeight > 0
  def isItemBased: Boolean         = itemBasedWeight > 0
  def isUserBased: Boolean         = userBasedWeight > 0
  def isQuery: Boolean             = isItemBased || isUserBased
  private val commonWeight: Double = inputWeight + userBasedWeight + itemBasedWeight
  def inputRps: Int                = (inputWeight / commonWeight * maxPerSecond).toInt
  def itemBasedRps: Int            = (itemBasedWeight / commonWeight * maxPerSecond).toInt
  def userBasedRps: Int            = (userBasedWeight / commonWeight * maxPerSecond).toInt
}

object RunArgs {
  import scala.concurrent.duration._
  def parse(args: Seq[String]): Option[RunArgs] = {
    val builder = OParser.builder[RunArgs]
    val parser = {
      import builder._
      OParser.sequence(
        programName("harness-load-test.sh"),
        head("harness load test", "0.3"),
        opt[Double]("input-weight")
          .action((w, acc) => acc.copy(inputWeight = w))
          .validate { i =>
            if (i >= 0) success
            else failure("Option --input-weight should be >= 0")
          }
          .text(
            "--input-weight <n> will send every n-th line of source file as an input. Default is 1 (every input will be sent)."
          ),
        opt[Double]("user-based-weight")
          .action((w, acc) => acc.copy(userBasedWeight = w))
          .validate { i =>
            if (i >= 0) success
            else failure("Option --user-based-weight should be >= 0")
          }
          .text(
            "--user-based-weight <n> will transform every n-th line of source file to an user-based query and send it to the Harness server. Default is 0 (nothing will be sent by default)."
          ),
        opt[Double]("item-based-weight")
          .action((w, acc) => acc.copy(itemBasedWeight = w))
          .validate { i =>
            if (i >= 0) success
            else failure("Option --item-based-weight should be >= 0")
          }
          .text(
            "--item-based-weight <n> will transform every n-th line of source file to an item-based query and send it to the Harness server. Default is 0 (nothing will be sent by default)."
          ),
        opt[Unit]("all-items")
          .action((_, acc) => acc.copy(isAllItems = true))
          .text("Forces to send all item-based queries, not just 'buy' queries"),
        opt[String]("filter-by-item-event")
          .action((v, acc) => acc.copy(filterByItemEvent = v))
          .text("Filter item-based queries by this event"),
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
          .action((u, acc) => acc.copy(uri = u))
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
        opt[Unit]("vv")
          .action((_, acc) => acc.copy(isVVerbose = true))
          .text("Even more info"),
        opt[Int]("factor")
          .validate { i =>
            if (i >= 1) success
            else failure("Option --factor should be > 0")
          }
          .action((v, acc) => acc.copy(factor = v))
          .text(
            "Skip all events except one of factor. E.g. if factor is 10, then only 1 event of the random value from 1 to 10 will be sent."
          ),
        opt[Int]('R', "num-of-retries")
          .action((r, acc) => acc.copy(nRetries = r))
          .text("Number of retries"),
        opt[Int]('t', "timeout")
          .action((t, acc) => acc.copy(timeout = t.seconds))
          .text("Response timeout in seconds"),
        opt[Unit]("skip-sets")
          .action((_, acc) => acc.copy(skipSets = true))
          .text("Skips all $set events"),
      )
    }
    val setup: OParserSetup = new DefaultOParserSetup {
      override def showUsageOnError: Option[Boolean] = Some(true)
    }

    OParser.parse(
      parser,
      args,
      RunArgs(
        nThreads = 8,
        maxPerSecond = 10000,
        engineId = "test-ur",
        uri = "http://localhost:9090",
        fileName = "events.json",
        inputWeight = 1,
        itemBasedWeight = 0,
        userBasedWeight = 0,
        isAllItems = false,
        filterByItemEvent = "buy",
        factor = 1,
        isVerbose = false,
        isVVerbose = false,
        timeout = 5.seconds,
        nRetries = 3,
        skipSets = false,
      ),
      setup
    )
  }
}
