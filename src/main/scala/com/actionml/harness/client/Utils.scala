package com.actionml.harness.client

import zio.Cause
import zio.blocking.Blocking
import zio.stream.{ ZStream, ZTransducer }

import java.io.FileInputStream

object Utils {
  def linesFromPath(s: String): ZStream[Blocking, Nothing, String] = {
    val fileOrDir = new java.io.File(s)
    val files     = if (fileOrDir.isDirectory) fileOrDir.listFiles().toSeq else Seq(fileOrDir)
    files
      .map(new FileInputStream(_))
      .map(ZStream.fromInputStream(_, 8192).mapErrorCause(_ => Cause.empty))
      .reduce(_ ++ _)
      .transduce(ZTransducer.utf8Decode)
      .transduce(ZTransducer.splitLines)
  }
}
