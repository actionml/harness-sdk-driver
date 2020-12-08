package com.actionml.harness.client

import zio.{ Cause, IO, ZManaged }
import zio.blocking.Blocking
import zio.clock.Clock
import zio.stream.{ ZStream, ZTransducer }

import java.io.FileInputStream

object Utils {
  def linesFromPath(s: String): ZStream[Blocking with Clock, Nothing, String] = {
    def fileOrDir = new java.io.File(s)
    def mkStream =
      (if (fileOrDir.isDirectory) fileOrDir.listFiles().toSeq else Seq(fileOrDir))
        .map(new FileInputStream(_))
        .map { f =>
          ZStream.fromInputStreamManaged(
            ZManaged
              .make(IO.effect(f).catchAll(_ => IO.effect(new java.io.FileInputStream("."))))(
                f => IO.effect(f.close()).ignore
              )
              .mapError(e => new java.io.IOException(e))
          )
        }
        .reduce(_ ++ _)

    mkStream
      .mapErrorCause(_ => Cause.empty)
      .transduce(ZTransducer.utf8Decode)
      .transduce(ZTransducer.splitLines)
  }
}
