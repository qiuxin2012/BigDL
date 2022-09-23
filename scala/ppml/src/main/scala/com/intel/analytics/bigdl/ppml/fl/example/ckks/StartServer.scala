package com.intel.analytics.bigdl.ppml.fl.example.ckks

import com.intel.analytics.bigdl.ppml.fl.FLServer

object StartServer {
  def main(args: Array[String]): Unit = {
    val flServer = new FLServer()
    flServer.setClientNum(2)
    flServer.build()
    flServer.start()
    flServer.blockUntilShutdown()
  }
}
