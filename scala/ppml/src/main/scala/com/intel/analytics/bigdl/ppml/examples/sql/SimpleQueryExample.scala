package com.intel.analytics.bigdl.ppml.examples.sql

import com.intel.analytics.bigdl.ppml.cryptos.{CryptoModeEnum, CryptoRuntimeException}
import com.intel.analytics.bigdl.ppml.kms.{EHSMKeyManagementService, KMS_CONVENTION, SimpleKeyManagementService}
import com.intel.analytics.bigdl.ppml.templates.{EncryptIOArguments, SQLTemplateMethodTrait}
import com.intel.analytics.bigdl.ppml.utils.Supportive
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.slf4j.LoggerFactory

object SimpleQueryExample extends SQLTemplateMethodTrait with Supportive {

  def main(args: Array[String]): Unit = {
    val logger = LoggerFactory.getLogger(getClass)

    val arguments = timing("parse arguments") {
      EncryptIOArguments.parser.parse(args, EncryptIOArguments()) match {
        case Some(arguments) => logger.info(s"starting with $arguments"); arguments
        case None => EncryptIOArguments.parser.failure("miss args, please see the usage info"); null
      }
    }

    var kms = arguments.kmsType match {
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

    val sparkSession: SparkSession = SparkSession.builder().getOrCreate()

    timing("process time") {
      process(sparkSession,
        arguments.inputPath,
        arguments.outputPath,
        CryptoModeEnum.parse(arguments.inputCryptoModeValue),
        CryptoModeEnum.parse(arguments.outputCryptoModeValue),
        arguments.inputPartitionNum,
        arguments.outputPartitionNum,
        kms,
        arguments.primaryKeyPath,
        arguments.dataKeyPath)
    }

    sparkSession.stop()
  }

  def doSQLOperations(dataframeMap: Map[String, DataFrame]): Map[String, DataFrame] = {
    val df = dataframeMap.get("people").head

    // Select only the "name" column
    df.select("name").count()

    // Select everybody, but increment the age by 1
    df.select(df("name"), df("age") + 1).show()

    // Select Developer and records count
    val developers = df.filter(df("job") === "Developer" and df("age").between(20, 40)).toDF()

    Map[String, DataFrame]({
      "developers" -> developers
    })
  }

}
