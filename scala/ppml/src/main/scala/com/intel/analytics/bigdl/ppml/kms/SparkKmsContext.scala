package com.intel.analytics.bigdl.ppml.kms

import com.intel.analytics.bigdl.ppml.cryptos.CryptoModeEnum.CryptoModeEnum
import com.intel.analytics.bigdl.ppml.cryptos.{CryptoModeEnum, FernetCryptos}
import com.intel.analytics.bigdl.ppml.utils.Supportive
import org.apache.spark.SparkContext
import org.apache.spark.input.PortableDataStream
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, SparkSession}

import java.nio.file.Paths

class SparkKmsContext(kms: KeyManagementService, sc: SparkContext) {

  private var inputCryptoMode: CryptoModeEnum = CryptoModeEnum.AES_CBC_PKCS5PADDING
  private var outputCryptoMode: CryptoModeEnum = CryptoModeEnum.AES_CBC_PKCS5PADDING
  private var dataKeyPlaintext: String = ""

  def setInputCryptoMode(inputCryptoMode: CryptoModeEnum): this.type = {
    this.inputCryptoMode = inputCryptoMode
    this
  }

  def setInputCryptoMode(inputCryptoMode: String): this.type = {
    this.inputCryptoMode = CryptoModeEnum.parse(inputCryptoMode)
    this
  }

  def setOutputCryptoMode(outputCryptoMode: String): this.type = {
    this.outputCryptoMode = CryptoModeEnum.parse(outputCryptoMode)
    this
  }

  def setOutputCryptoMode(outputCryptoMode: CryptoModeEnum): this.type = {
    this.outputCryptoMode = outputCryptoMode
    this
  }

  def loadKeys(primaryKeyPath: String, dataKeyPath: String): this.type = {
    dataKeyPlaintext = kms.retrieveDataKeyPlaintext(
      Paths.get(primaryKeyPath).toString, Paths.get(dataKeyPath).toString)
    this
  }

  def textFile(path: String,
               minPartitions: Int = sc.defaultMinPartitions): RDD[String] = {
    inputCryptoMode match {
      case CryptoModeEnum.PLAIN_TEXT =>
        sc.textFile(path, minPartitions)
      case CryptoModeEnum.AES_CBC_PKCS5PADDING =>
        SparkKmsContext.textFile(sc, path, dataKeyPlaintext, minPartitions)
    }
  }

  def saveDataFrame(dataFrame: DataFrame, tableName: String, path: String): Unit = {
    outputCryptoMode match {
      case CryptoModeEnum.PLAIN_TEXT =>
        dataFrame.write.mode("overwrite").option("header", true).csv(Paths.get(path, tableName).toString)
      case CryptoModeEnum.AES_CBC_PKCS5PADDING =>
        SparkKmsContext.saveDataFrame(dataFrame.sparkSession, dataKeyPlaintext, dataFrame, tableName, path)
    }
  }
}

object SparkKmsContext{
  def apply(): SparkKmsContext = {
    val sc = SparkContext.getOrCreate()
    apply(sc)
  }

  def apply(sc: SparkContext): SparkKmsContext = {
    val skms = SimpleKeyManagementService()
    apply(skms, sc)
  }

  def apply(kms: KeyManagementService): SparkKmsContext = {
    val sc = SparkContext.getOrCreate()
    new SparkKmsContext(kms, sc)
  }

  def apply(kms: KeyManagementService, sc: SparkContext): SparkKmsContext = {
    new SparkKmsContext(kms, sc)
  }


  def registerUDF(spark: SparkSession,
                  dataKeyPlaintext: String) = {
    val bcKey = spark.sparkContext.broadcast(dataKeyPlaintext)
    val convertCase = (x: String) => {
      val fernetCryptos = new FernetCryptos()
      new String(fernetCryptos.encryptBytes(x.getBytes, bcKey.value))
    }
    spark.udf.register("convertUDF", convertCase)
  }

  def textFile(sc: SparkContext,
               path: String,
               dataKeyPlaintext: String,
               minPartitions: Int): RDD[String] = {
    require(dataKeyPlaintext != "", "dataKeyPlaintext should not be empty, please loadKeys first.")
    val data: RDD[(String, PortableDataStream)] = sc.binaryFiles(path, minPartitions)
    val fernetCryptos = new FernetCryptos
    data.mapPartitions { iterator => {
      Supportive.logger.info("Decrypting bytes with JavaAESCBC...")
      fernetCryptos.decryptBigContent(iterator, dataKeyPlaintext)
    }}.flatMap(_.split("\n"))
  }

  def saveDataFrame(sparkSession: SparkSession,
                     dataKeyPlaintext: String,
                    dataFrame: DataFrame,
                    tableName: String,
                    path: String): Unit = {
    dataFrame.createOrReplaceTempView(tableName)
    SparkKmsContext.registerUDF(sparkSession, dataKeyPlaintext)
    // Select all and encrypt columns.
    val convertSql = "select " + dataFrame.schema.map(column =>
      "convertUDF(" + column.name + ") as " + column.name).mkString(", ") +
      " from " + tableName
    val df = sparkSession.sql(convertSql)
    df.write.mode("overwrite").option("header", true).csv(Paths.get(path, tableName).toString)
  }
}
