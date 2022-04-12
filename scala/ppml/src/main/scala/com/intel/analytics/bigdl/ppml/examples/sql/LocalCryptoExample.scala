package com.intel.analytics.bigdl.ppml.examples.sql

import com.intel.analytics.bigdl.ppml.cryptos.{CryptoRuntimeException, FernetCryptos}
import com.intel.analytics.bigdl.ppml.kms.{EHSMKeyManagementService, KMS_CONVENTION, SimpleKeyManagementService}
import com.intel.analytics.bigdl.ppml.templates.EncryptIOArguments
import com.intel.analytics.bigdl.ppml.examples.sql.SimpleEncryptIO.timing
import org.slf4j.LoggerFactory

object LocalCryptoExample extends App {
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

  val primaryKeyPath = arguments.primaryKeyPath
  val dataKeyPath = arguments.dataKeyPath
  val encryptedFilePath = arguments.inputPath + ".encrypted"
  val decryptedFilePath = arguments.inputPath + ".decrypted"

  logger.info(s"$arguments.inputPath will be encrypted and saved at $encryptedFilePath, and decrypted and saved at $decryptedFilePath")

  logger.info(s"Primary key will be saved at $primaryKeyPath, and data key will be saved at $dataKeyPath")

  logger.info("The cryptos initializing...")
  val cryptos = new FernetCryptos

  logger.info("Retrivee data key in palintext...")
  val dataKeyPlaintext: String = kms.retrieveDataKeyPlaintext(primaryKeyPath, dataKeyPath)

  logger.info("Start to encrypt the file.")
  cryptos.encryptFile(arguments.inputPath, encryptedFilePath, dataKeyPlaintext)
  logger.info("Finish encryption successfully!")

  logger.info("Start to decrypt the file.")
  cryptos.decryptFile(encryptedFilePath, decryptedFilePath, dataKeyPlaintext)
  logger.info("Finish decryption successfully!")

}
