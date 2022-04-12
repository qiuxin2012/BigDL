package com.intel.analytics.bigdl.ppml.examples.sql

import com.intel.analytics.bigdl.ppml.cryptos.CryptoModeEnum.CryptoModeEnum
import com.intel.analytics.bigdl.ppml.cryptos.{CryptoModeEnum, CryptoRuntimeException, FernetCryptos}
import com.intel.analytics.bigdl.ppml.kms.{EHSMKeyManagementService, KMS_CONVENTION, SimpleKeyManagementService}
import com.intel.analytics.bigdl.ppml.templates.EncryptIOArguments
import com.intel.analytics.bigdl.ppml.examples.sql.SimpleEncryptIO.{getClass, timing}
import org.slf4j.LoggerFactory

import java.io.File
import java.nio.file.{Files, Paths}
import java.util.concurrent.locks.{Lock, ReentrantLock}
import scala.io.Source

object SplitAndEncrypt {

  def main(args: Array[String]): Unit = {
    val logger = LoggerFactory.getLogger(getClass)

    val arguments = timing("parse arguments") {
      EncryptIOArguments.parser.parse(args, EncryptIOArguments()) match {
        case Some(arguments) => logger.info(s"starting with $arguments"); arguments
        case None => EncryptIOArguments.parser.failure("miss args, please see the usage info"); null
      }
    }

    val kms = arguments.kmsType match {
      case KMS_CONVENTION.MODE_EHSM_KMS => {
        new EHSMKeyManagementService(arguments.kmsServerIP, arguments.kmsServerPort, arguments.ehsmAPPID, arguments.ehsmAPPKEY)
      }
      case KMS_CONVENTION.MODE_SIMPLE_KMS => {
        new SimpleKeyManagementService
      }
      case _ => {
        throw new CryptoRuntimeException("Wrong kms type")
      }
    }

    val outputCryptoMode: CryptoModeEnum = CryptoModeEnum.parse(arguments.outputCryptoModeValue)

    val inputFiles = (new File(arguments.inputPath)).listFiles
    inputFiles.par.map { file => {
      val filePrefix: String = file.getName()
      if (Files.exists(Paths.get(arguments.outputPath, filePrefix)) == false) {
        Files.createDirectory(Paths.get(arguments.outputPath, filePrefix))
      }
      val splitNum = arguments.outputPartitionNum
      val stream = Files.lines(Paths.get(file.getPath))
      val numLines: Long = stream.count - 1 // Number of file's lines

      val source = Source.fromFile(file.getPath)
      val content = source.getLines
      val head = content.take(1).mkString + "\n" // Csv's head

      val linesPerFile: Long = numLines / splitNum // lines per split file

      var splitArray = new Array[Long](splitNum) // split-point
      for (i <- 0 to splitNum - 1) {
        splitArray(i) = linesPerFile
      }
      splitArray(splitNum - 1) += numLines % linesPerFile // for last part

      var currentSplitNum: Int = 0
      val rtl: Lock = new ReentrantLock()
      val begin = System.currentTimeMillis
      splitArray.par.map {
        num => {
          var splitContentString = ""
          var splitFileName = ""
          rtl.lock()
          try { // get split content
            splitContentString = content.take(num.toInt).mkString("\n")
            splitFileName = "split_" + currentSplitNum.toString + ".csv"
            currentSplitNum += 1
          } finally {
            rtl.unlock()
          }
          outputCryptoMode match {
            case CryptoModeEnum.AES_CBC_PKCS5PADDING => {
              val fernetCryptos = new FernetCryptos
              val dataKeyPlaintext = kms.retrieveDataKeyPlaintext(arguments.primaryKeyPath, arguments.dataKeyPath)
              val encryptedBytes = fernetCryptos.encryptBytes((head + splitContentString + "\n").getBytes, dataKeyPlaintext)
              Files.write(Paths.get(arguments.outputPath, filePrefix, splitFileName), encryptedBytes)
              println("Successfully save encrypted text " + filePrefix + "  " + splitFileName)
            }
            case CryptoModeEnum.PLAIN_TEXT => {
              val splitPlainBytes = (head + splitContentString + "\n").getBytes
              Files.write(Paths.get(arguments.outputPath, filePrefix, splitFileName), splitPlainBytes)
              println("Successfully save plain text " + filePrefix + "  " + splitFileName)
            }
            case default => {
              throw new CryptoRuntimeException("No such crypto mode!")
            }
          }

        }
      }
      val end = System.currentTimeMillis
      val cost = (end - begin)
      println(s"Encrypt time elapsed $cost ms.")
    }
    }
  }
}
