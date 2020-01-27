package com.actionml.harness.client

import java.io.FileInputStream

import zio.Cause
import zio.stream.{ ZSink, ZStream }

object Utils {
  def linesFromPath(s: String): ZStream[Any, Nothing, String] = {
    val fileOrDir = new java.io.File(s)
    val files     = if (fileOrDir.isDirectory) fileOrDir.listFiles().toSeq else Seq(fileOrDir)
    files
      .map(new FileInputStream(_))
      .map(ZStream.fromInputStream(_, 512).mapErrorCause(_ => Cause.empty))
      .reduce(_ ++ _)
      .chunks
      .transduce(ZSink.utf8DecodeChunk)
      .transduce(ZSink.splitLines)
      .flatMap(ZStream.fromChunk)
  }
}
