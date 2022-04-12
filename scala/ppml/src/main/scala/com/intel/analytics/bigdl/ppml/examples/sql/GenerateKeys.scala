package com.intel.analytics.bigdl.ppml.examples.sql

import com.intel.analytics.bigdl.ppml.cryptos.CryptoRuntimeException
import com.intel.analytics.bigdl.ppml.kms.{EHSMKeyManagementService, KMS_CONVENTION, SimpleKeyManagementService}
import com.intel.analytics.bigdl.ppml.templates.EncryptIOArguments
import org.slf4j.LoggerFactory

object GenerateKeys extends App {
  val logger = LoggerFactory.getLogger(getClass)

  val arguments = {
    EncryptIOArguments.parser.parse(args, EncryptIOArguments()) match {
      case Some(arguments) => logger.info(s"starting with $arguments"); arguments
      case None => EncryptIOArguments.parser.failure("miss args, please see the usage info"); null
    }
  }

  val primaryKeySavePath = arguments.primaryKeyPath
  val dataKeySavePath = arguments.dataKeyPath
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
  kms.retrievePrimaryKey(primaryKeySavePath)
  kms.retrieveDataKey(primaryKeySavePath, dataKeySavePath)
}
