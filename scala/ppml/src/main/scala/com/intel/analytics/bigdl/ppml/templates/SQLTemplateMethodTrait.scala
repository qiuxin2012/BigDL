package com.intel.analytics.bigdl.ppml.templates

import java.nio.file.Paths

import com.intel.analytics.bigdl.ppml.cryptos.CryptoModeEnum.CryptoModeEnum
import com.intel.analytics.bigdl.ppml.cryptos.CryptoRuntimeException
import com.intel.analytics.bigdl.ppml.utils.Supportive
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.rdd.RDD
import org.apache.spark.input.PortableDataStream
import org.apache.spark.sql.types.{StringType, StructField, StructType}
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import com.intel.analytics.bigdl.ppml.kms.KeyManagementService
import com.intel.analytics.bigdl.ppml.cryptos.{CryptoModeEnum, FernetCryptos}
import org.slf4j.LoggerFactory

trait SQLTemplateMethodTrait extends Supportive {
  val nameDefiner = (p: Path) => p.toString.split(":")(1).split("/").last.split("\\.")(0)

  def process(sparkSession: SparkSession,
              inputPath: String,
              outputPath: String,
              inputCryptoMode: CryptoModeEnum,
              outputCryptoMode: CryptoModeEnum,
              inputPartitionNum: Int,
              outputPartitionNum: Int,
              kms: KeyManagementService,
              primaryKeyPath: String,
              dataKeyPath: String) = {


    val fs: FileSystem = FileSystem.get(sparkSession.sparkContext.hadoopConfiguration)
    val inputFilePaths = timing("1/5 loadInputs") {
      getInputPaths(fs, inputPath)
    }
    val decryptedInputs = timing("2/5 decryptInputs") {
      inputFilePaths.map(inputFilePath => {
        nameDefiner(inputFilePath) -> loadInputs(sparkSession, inputFilePath, inputCryptoMode, inputPartitionNum, kms, primaryKeyPath, dataKeyPath)
      })
    }

    val dataframeMap = timing("3/5 createDataFrame") {
      decryptedInputs.map((data: (String, RDD[String])) => createDataFrame(sparkSession, data)).toMap
    }
    val results = timing("4/5 doSQLOperations") {
      doSQLOperations(dataframeMap)
    }
    timing("5/5 encryptAndSaveOutputs") {
      results.map(result => saveOutputs(sparkSession, outputPath, outputCryptoMode, outputPartitionNum, kms, primaryKeyPath, dataKeyPath, result._1, result._2))
    }
  }

  def doSQLOperations(dataframeMap: Map[String, DataFrame]): Map[String, DataFrame]

  def getInputPaths(fs: FileSystem, inputPath: String): Array[Path] = {
    fs.listStatus(new Path(inputPath)).map(fileStatus => {
      fileStatus.isDirectory match {
        case true => {
          SQLTemplateMethodTrait.logger.info(s"$fileStatus isDir ${fileStatus.isDirectory}")
          val iterator = fs.listFiles(fileStatus.getPath, true)
          while(iterator.hasNext) {
            SQLTemplateMethodTrait.logger.info(s"-- ${iterator.next()}")
          }
        }
        case false => {
          SQLTemplateMethodTrait.logger.info(s"$fileStatus isFile ${fileStatus.isFile}")
        }
      }
      SQLTemplateMethodTrait.logger.info( """Input should like:
        inputPath
        ├── file1
        │     ├── split1
        │     ├── split2
        ├── file2
        │     ├── split1
        │     ├── split2""")
      val cs = fs.getContentSummary(fileStatus.getPath)
      if (cs.getFileCount == 0) {
        throw new CryptoRuntimeException("Error! " + fileStatus.getPath.toString + " is Empty!")
      }
      fileStatus
    }).filter(_.isDirectory).map(_.getPath)
  }

  def loadInputs(sparkSession: SparkSession,
                    inputPath: Path,
                    inputCryptoMode: CryptoModeEnum,
                    inputPartitionNum: Int,
                    kms: KeyManagementService,
                    primaryKeyPath: String,
                    dataKeyPath: String
                   ): RDD[String] = {
    inputCryptoMode match {
      case CryptoModeEnum.AES_CBC_PKCS5PADDING => {
        val data: RDD[(String, PortableDataStream)] = sparkSession.sparkContext.binaryFiles(inputPath.toString.split(":")(1)).repartition(inputPartitionNum)
        val fernetCryptos = new FernetCryptos
        val dataKeyPlaintext = kms.retrieveDataKeyPlaintext(Paths.get(primaryKeyPath).toString, Paths.get(dataKeyPath).toString)
        data.mapPartitions { iterator => {
          Supportive.logger.info("Decrypting bytes with JavaAESCBC...")
          fernetCryptos.decryptBigContent(iterator, dataKeyPlaintext)
        }
        }.flatMap(_.split("\n"))
      }
      case CryptoModeEnum.PLAIN_TEXT => {
        val data: RDD[(String, String)] = sparkSession.sparkContext.wholeTextFiles(inputPath.toString.split(":")(1)).repartition(inputPartitionNum)
        data.mapPartitions{ iterator => {
          iterator.map{ x => x._2 }
        }}.flatMap(_.split("\n")).flatMap(_.split("\r"))
      }
      case default => {
        throw new CryptoRuntimeException("No such crypto mode!")
      }
    }
   }

  def createDataFrame(sparkSession: SparkSession, data: (String, RDD[String])): (String, DataFrame) = {
    // get schema
    val tableName = data._1
    val dataRDD = data._2
    val header = dataRDD.first()
    val schemaArray = header.split(",")
    val fields = schemaArray.map(fieldName => StructField(fieldName, StringType, true))
    val schema = StructType(fields)

    // remove title line
    val data_filter = dataRDD.filter(row => row != header)

    // create df
    val rowRdd = data_filter.map(s => Row.fromSeq(s.split(",")))
    tableName -> sparkSession.createDataFrame(rowRdd, schema)
  }

  def saveOutputs(sparkSession: SparkSession,
                            outputPath: String,
                            outputCryptoMode: CryptoModeEnum,
                            outputPartitionNum: Int,
                            kms: KeyManagementService,
                            primaryKeyPath: String,
                            dataKeyPath: String,
                            table_name: String,
                            dataframe: DataFrame) = {
    outputCryptoMode match {
      case CryptoModeEnum.AES_CBC_PKCS5PADDING => {
        dataframe.createOrReplaceTempView(table_name)
        registerUDF(sparkSession, kms, primaryKeyPath, dataKeyPath)
        // Select all and encrypt columns.
        val convertSql = "select " + dataframe.schema.map(columnName => "convertUDF(" + columnName.name + ") as " + columnName.name).mkString(", ") + " from " + table_name

        val df = timing("encryptColumn") {
          sparkSession.sql(convertSql)
        }
        timing("saveAsEncryptedOutputs") {
          df.repartition(outputPartitionNum).write.mode("overwrite").option("header", true).csv(Paths.get(outputPath, table_name).toString)
        }
      }
      case CryptoModeEnum.PLAIN_TEXT => {
        timing("saveAsPlainOutputs") {
          dataframe.repartition(outputPartitionNum).write.mode("overwrite").option("header", true).csv(Paths.get(outputPath, table_name).toString)
        }
      }
      case default => {
        throw new CryptoRuntimeException("No such crypto mode!")
      }
    }

  }

  def registerUDF(spark: SparkSession,
                  kms: KeyManagementService,
                  primaryKeyPath: String,
                  dataKeyPath: String) = {
    val fernetCryptos = new FernetCryptos
    val dataKeyPlaintext = kms.retrieveDataKeyPlaintext(primaryKeyPath,dataKeyPath)
    val convertCase = (x: String) => {
      new String(fernetCryptos.encryptBytes(x.getBytes, dataKeyPlaintext))
    }
    spark.udf.register("convertUDF", convertCase)
  }
}

object SQLTemplateMethodTrait {
  val logger = LoggerFactory.getLogger(getClass)
}