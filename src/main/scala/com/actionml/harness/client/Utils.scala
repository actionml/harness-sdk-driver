package com.actionml.harness.client

import zio.blocking.Blocking
import zio.clock.Clock
import zio.stream.{ ZStream, ZTransducer }
import zio.{ Cause, ZIO, ZManaged }

import java.io.{ FileInputStream, SequenceInputStream }
import scala.jdk.CollectionConverters.SeqHasAsJava

object Utils {
  def linesFromPath(s: String): ZStream[Blocking with Clock, Nothing, String] = {
    val mkStream = {
      val fileOrDir = new java.io.File(s)
      val files = ZIO.effect(
        new java.util.Vector(
          SeqHasAsJava(
            if (fileOrDir.isDirectory) fileOrDir.listFiles().map(new FileInputStream(_)).toSeq
            else Seq(new FileInputStream(fileOrDir))
          ).asJava
        ).elements()
      )

      ZStream.fromInputStreamManaged(
        ZManaged.fromEffect(
          files.bimap(e => new java.io.IOException(e), new SequenceInputStream(_))
        )
      )
    }

    mkStream
      .mapErrorCause(_ => Cause.empty)
      .transduce(ZTransducer.utf8Decode)
      .transduce(ZTransducer.splitLines)
  }
}
