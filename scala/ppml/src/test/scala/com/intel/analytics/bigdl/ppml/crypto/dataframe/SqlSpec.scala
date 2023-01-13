/*
 * Copyright 2016 The BigDL Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intel.analytics.bigdl.ppml.crypto.dataframe

import com.intel.analytics.bigdl.dllib.utils.LoggerFilter
import com.intel.analytics.bigdl.ppml.PPMLContext
import com.intel.analytics.bigdl.ppml.crypto._
import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession

import java.io.{File, FileWriter}

class SqlSpec extends DataFrameHelper {
  LoggerFilter.redirectSparkInfoLogs()

  val (plainFileName, encryptFileName, data, dataKeyPlaintext) = generateCsvData()

  val ppmlArgs = Map(
      "spark.bigdl.kms.simple.id" -> appid,
      "spark.bigdl.kms.simple.key" -> apikey,
      "spark.bigdl.kms.key.primary" -> primaryKeyPath,
      "spark.bigdl.kms.key.data" -> dataKeyPath
  )
  val sparkConf = new SparkConf().setMaster("local[4]")
    .set("spark.sql.catalogImplementation", "hive")
  val sc = PPMLContext.initPPMLContext(sparkConf, "SimpleQuery", ppmlArgs)

  "sparkSession.read" should "work" in {
    val sparkSession: SparkSession = SparkSession
      .builder()
      .appName("Spark Hive Example")
      .enableHiveSupport()
      .getOrCreate()
//    sparkSession.sql("create database abc;")
    sparkSession.sql("use abc;")
//    sparkSession.sql("create table People(name string, age int, job string)" +
//      " row format delimited fields terminated by ',';")
//    sparkSession.sql(s"LOAD DATA LOCAL INPATH '${plainFileName}' OVERWRITE INTO TABLE People;")
//    sparkSession.sql("create table People_copy(name string, age int, job string)" +
//      " row format delimited fields terminated by ',';")
    sparkSession.sql("set io.compression.codecs=com.intel.analytics.bigdl.ppml.crypto.CryptoCodec")
    sparkSession.sql(s"set bigdl.kms.data.key=$dataKeyPlaintext")
    sparkSession.sql("set hive.exec.compress.output=true")
    sparkSession.sql("set mapreduce.output.fileoutputformat.compress.codec=" +
      "com.intel.analytics.bigdl.ppml.crypto.CryptoCodec")

    val r = sparkSession.sql(s"select count(*) from People")
    val r2 = sparkSession.sql("insert into People_copy select * from People")
    val r3 = sparkSession.sql("select * from People_copy")
    val rr = r.collect()
    val rr3 = r3.collect()
    println(r.take(1))


  }

}

