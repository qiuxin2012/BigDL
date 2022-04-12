package com.intel.analytics.bigdl.ppml.utils


import java.nio.file.Paths

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{FileIO, Framing}
import akka.util.ByteString

object HugeFileReader extends App with Supportive {
  println("hello")

  implicit val actorSystem = ActorSystem("MyAkkaSystem")
  import actorSystem.dispatcher
  implicit val materializer = ActorMaterializer()

  // val path = "/home/glorysdj/java_error_in_IDEA_33003.log"
  val path = "/home/glorysdj/tmp_mock_a_table.csv"

  val begin = System.currentTimeMillis

  val count = 100000000
  val largeBatch = 100
  val largeGroup = count/largeBatch
  val miniBatch = 100
  val miniGroup = largeGroup/miniBatch
  var index = 0
  val completion =
    FileIO.fromPath(Paths.get(path))
    .via(
      Framing.delimiter(ByteString("\n"), 256, true)
        .map(_.utf8String).grouped(largeGroup)
    )
    .runForeach(list => {
      val s = System.currentTimeMillis
      //list.grouped(10000).toParArray.map(_.mkString(""))
      list.grouped(miniGroup).map(_.mkString(""))
      val e = System.currentTimeMillis
      println(s"$index batch ${e-s} ms.")
      index = index + 1
    })
  completion.onComplete { _ =>
    val end = System.currentTimeMillis
    val cost = (end - begin)
    println(s"#################### time elapsed $cost ms.")
    actorSystem.terminate()
  }




}
