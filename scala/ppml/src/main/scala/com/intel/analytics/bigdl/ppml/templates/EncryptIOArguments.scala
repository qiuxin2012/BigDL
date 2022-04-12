package com.intel.analytics.bigdl.ppml.templates

import com.intel.analytics.bigdl.ppml.cryptos.CryptoModeEnum
import com.intel.analytics.bigdl.ppml.kms.KMS_CONVENTION

case class EncryptIOArguments(
                               inputPath: String = "./input",
                               outputPath: String = "./output",
                               inputCryptoModeValue: String = CryptoModeEnum.PLAIN_TEXT.value,
                               outputCryptoModeValue: String = CryptoModeEnum.PLAIN_TEXT.value,
                               inputPartitionNum: Int = 4,
                               outputPartitionNum: Int = 4,
                               primaryKeyPath: String = "./primaryKeyPath",
                               dataKeyPath: String = "./dataKeyPath",
                               kmsType: String = KMS_CONVENTION.MODE_SIMPLE_KMS,
                               kmsServerIP: String = "0.0.0.0",
                               kmsServerPort: String = "5984",
                               ehsmAPPID: String = "ehsmAPPID",
                               ehsmAPPKEY: String = "ehsmAPPKEY"
                             )

object EncryptIOArguments {
  val parser = new scopt.OptionParser[EncryptIOArguments]("BigDL PPML E2E workflow") {
    head("BigDL PPML E2E workflow")
    opt[String]('i', "inputPath")
      .action((x, c) => c.copy(inputPath = x))
      .text("inputPath")
    opt[String]('o', "outputPath")
      .action((x, c) => c.copy(outputPath = x))
      .text("outputPath")
    opt[String]('a', "inputCryptoModeValue")
      .action((x, c) => c.copy(inputCryptoModeValue = x))
      .text("inputCryptoModeValue: plain_text/aes_cbc_pkcs5padding")
    opt[String]('b', "outputCryptoModeValue")
      .action((x, c) => c.copy(outputCryptoModeValue = x))
      .text("outputCryptoModeValue: plain_text/aes_cbc_pkcs5padding")
    opt[String]('c', "outputPath")
      .action((x, c) => c.copy(outputPath = x))
      .text("outputPath")
    opt[Int]('f', "inputPartitionNum")
      .action((x, c) => c.copy(inputPartitionNum = x))
      .text("inputPartitionNum")
    opt[Int]('e', "outputPartitionNum")
      .action((x, c) => c.copy(outputPartitionNum = x))
      .text("outputPartitionNum")
    opt[String]('p', "primaryKeyPath")
      .action((x, c) => c.copy(primaryKeyPath = x))
      .text("primaryKeyPath")
    opt[String]('d', "dataKeyPath")
      .action((x, c) => c.copy(dataKeyPath = x))
      .text("dataKeyPath")
    opt[String]('k', "kmsType")
      .action((x, c) => c.copy(kmsType = x))
      .text("kmsType")
    opt[String]('g', "kmsServerIP")
      .action((x, c) => c.copy(kmsServerIP = x))
      .text("kmsServerIP")
    opt[String]('h', "kmsServerPort")
      .action((x, c) => c.copy(kmsServerPort = x))
      .text("kmsServerPort")
    opt[String]('j', "ehsmAPPID")
      .action((x, c) => c.copy(ehsmAPPID = x))
      .text("ehsmAPPID")
    opt[String]('k', "ehsmAPPKEY")
      .action((x, c) => c.copy(ehsmAPPKEY = x))
      .text("ehsmAPPKEY")
  }
}
