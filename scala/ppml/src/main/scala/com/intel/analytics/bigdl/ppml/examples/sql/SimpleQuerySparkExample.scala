package com.intel.analytics.bigdl.ppml.examples.sql

import com.intel.analytics.bigdl.ppml.cryptos.CryptoRuntimeException
import com.intel.analytics.bigdl.ppml.kms.{EHSMKeyManagementService, KMS_CONVENTION, SimpleKeyManagementService, SparkKmsContext}
import com.intel.analytics.bigdl.ppml.templates.EncryptIOArguments
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.types.{StringType, StructField, StructType}
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.apache.spark.{SparkConf, SparkContext}
import org.slf4j.LoggerFactory

object SimpleQuerySparkExample {

  def main(args: Array[String]): Unit = {
    val logger = LoggerFactory.getLogger(getClass)

    // parse parameter
    val arguments = EncryptIOArguments.parser.parse(args, EncryptIOArguments()) match {
        case Some(arguments) => logger.info(s"starting with $arguments"); arguments
        case None => EncryptIOArguments.parser.failure("miss args, please see the usage info"); null
      }

    // create kms
    val kms = arguments.kmsType match {
      case KMS_CONVENTION.MODE_EHSM_KMS =>
        new EHSMKeyManagementService(arguments.kmsServerIP, arguments.kmsServerPort, arguments.ehsmAPPID, arguments.ehsmAPPKEY)
      case KMS_CONVENTION.MODE_SIMPLE_KMS =>
        new SimpleKeyManagementService
      case _ =>
        throw new CryptoRuntimeException("Wrong kms type")
    }

    // create spark context
    val sc = new SparkContext(new SparkConf().setAppName("SimpleQuery"))

    // create spark kms context via parameter.
    val sparkKmsContext = SparkKmsContext(kms, sc)
    sparkKmsContext.loadKeys(arguments.primaryKeyPath, arguments.dataKeyPath)
    sparkKmsContext.setInputCryptoMode(arguments.inputCryptoModeValue)

    // load csv file to data frame with sparkKmsContext api.
    val csvRdd = sparkKmsContext.textFile(arguments.inputPath + "/people.csv")
    // convert rdd to DF
    val df = Utils.toDataFrame(csvRdd)

    // Select only the "name" column
    df.select("name").count()

    // Select everybody, but increment the age by 1
    df.select(df("name"), df("age") + 1).show()

    // Select Developer and records count
    val developers = df.filter(df("job") === "Developer" and df("age").between(20, 40)).toDF()
    developers.count()

    Map[String, DataFrame]({
      "developers" -> developers
    })

    // save data frame using spark kms context
    sparkKmsContext.setOutputCryptoMode(arguments.outputCryptoModeValue)
    sparkKmsContext.saveDataFrame(developers, "develops", arguments.outputPath)
  }
}

object Utils {
  // to data frame from a csv with header.
  def toDataFrame(dataRDD: RDD[String]): DataFrame = {
    // get schema
    val sparkSession: SparkSession = SparkSession.builder().getOrCreate()
    val header = dataRDD.first()
    val schemaArray = header.split(",")
    val fields = schemaArray.map(fieldName => StructField(fieldName, StringType, true))
    val schema = StructType(fields)

    // remove title line
    val data_filter = dataRDD.filter(row => row != header)

    // create df
    val rowRdd = data_filter.map(s => Row.fromSeq(s.split(",")))
    sparkSession.createDataFrame(rowRdd, schema)
  }
}
