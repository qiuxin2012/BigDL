package com.intel.analytics.bigdl.ppml.examples.sql

import com.intel.analytics.bigdl.ppml.cryptos.{CryptoModeEnum, CryptoRuntimeException}
import com.intel.analytics.bigdl.ppml.kms.{EHSMKeyManagementService, KMS_CONVENTION, SimpleKeyManagementService}
import com.intel.analytics.bigdl.ppml.templates.{EncryptIOArguments, SQLTemplateMethodTrait}
import com.intel.analytics.bigdl.ppml.utils.Supportive
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.{IntegerType, LongType}
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.slf4j.LoggerFactory

object MaxComputeExample extends SQLTemplateMethodTrait with Supportive {

  def main(args: Array[String]): Unit = {
    val logger = LoggerFactory.getLogger(getClass)

    val arguments = timing("parse arguments") {
      EncryptIOArguments.parser.parse(args, EncryptIOArguments()) match {
        case Some(arguments) => logger.info(s"starting with $arguments"); arguments
        case None => EncryptIOArguments.parser.failure("miss args, please see the usage info"); null
      }
    }
    val sparkSession: SparkSession = SparkSession.builder().getOrCreate()

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
    val tmp_mock_r_table = dataframeMap.get("tmp_mock_r_table").head
    val tmp_mock_a_table = dataframeMap.get("tmp_mock_a_table").head

    val tmp_table = tmp_mock_r_table.select(col("brand").cast(IntegerType), col("userid").cast(IntegerType), col("date").cast(LongType))
    tmp_table.cache()

    val df_tmp_mock_r_table_search = tmp_table
      .filter(col("search") > 0)
      .groupBy("brand", "userid")
      .agg(max(col("date")) as "act_date", countDistinct(col("date")) as "actdays")
      .withColumn("crowd", lit("1_"))

    val df_tmp_mock_r_table_read = tmp_table
      .filter(col("read") > 0)
      .groupBy("brand", "userid")
      .agg(max(col("date")) as "act_date", countDistinct(col("date")) as "actdays")
      .withColumn("crowd", lit("2_"))

    val df_tmp_mock_r_table_comment = tmp_table
      .filter(col("comment") > 0)
      .groupBy("brand", "userid")
      .agg(max(col("date")) as "act_date", countDistinct(col("date")) as "actdays")
      .withColumn("crowd", lit("3_"))

    val df_tmp_mock_r_table_follow = tmp_table
      .filter(col("follow") > 0)
      .groupBy("brand", "userid")
      .agg(max(col("date")) as "act_date", countDistinct(col("date")) as "actdays")
      .withColumn("crowd", lit("4_"))

    val df_tmp_mock_r_table_screenshot = tmp_table
      .filter(col("screenshot") > 0)
      .groupBy("brand", "userid")
      .agg(max(col("date")) as "act_date", countDistinct(col("date")) as "actdays")
      .withColumn("crowd", lit("5_"))

    val df_tmp_mock_r_table_share = tmp_table
      .filter(col("share") > 0)
      .groupBy("brand", "userid")
      .agg(max(col("date")) as "act_date", countDistinct(col("date")) as "actdays")
      .withColumn("crowd", lit("6_"))

    val df_tmp_mock_r_table_write = tmp_table
      .filter(col("write") > 0)
      .groupBy("brand", "userid")
      .agg(max(col("date")) as "act_date", countDistinct(col("date")) as "actdays")
      .withColumn("crowd", lit("7_"))

    val df_tmp_mock_r_table_all = tmp_table
      .groupBy("brand", "userid")
      .agg(max(col("date")) as "act_date", countDistinct(col("date")) as "actdays")
      .withColumn("crowd", lit("0_"))

    // create tmp_mock_r_crowd
    val tmp_mock_r_crowd = df_tmp_mock_r_table_search
      .unionAll(df_tmp_mock_r_table_read)
      .unionAll(df_tmp_mock_r_table_comment)
      .unionAll(df_tmp_mock_r_table_follow)
      .unionAll(df_tmp_mock_r_table_screenshot)
      .unionAll(df_tmp_mock_r_table_share)
      .unionAll(df_tmp_mock_r_table_write)
      .unionAll(df_tmp_mock_r_table_all)
      .repartition(8)

    println("PERF tmp_mock_r_crowd created: " + System.currentTimeMillis())

    // create dataset: a
    val a = tmp_mock_a_table.select(col("brand").cast(IntegerType),
      col("userid").cast(IntegerType), col("ds").cast(LongType))
      .filter(col("pay") > 0)
      .groupBy("brand", "userid")
      .agg(min(col("ds")) as "pay_date")

    // checked success!
    // a.show()
    // println("this is a count!", a.count())

    val tmp_mock_a_r_3_1 = a.join(tmp_mock_r_crowd.alias("r"), a.col("userid") === tmp_mock_r_crowd.col("userid") && a.col("brand") === tmp_mock_r_crowd.col("brand") && a.col("pay_date") <= tmp_mock_r_crowd.col("act_date"), "leftouter")
      .groupBy(a.col("brand"), when(tmp_mock_r_crowd.col("crowd").isNull, lit("-1_")).otherwise(tmp_mock_r_crowd.col("crowd")) as "redact_afterpay")
      .agg(count(a.col("userid")) as "cnt")
      .repartition(8)

    println("PERF tmp_mock_a_r_3_1 created: " + System.currentTimeMillis())

    Map[String, DataFrame]({
      "tmp_mock_r_crowd" -> tmp_mock_r_crowd
    }, {
      "tmp_mock_a_r_3_1" -> tmp_mock_a_r_3_1
    })
  }


}
