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

import com.intel.analytics.bigdl.ppml.PPMLContext
import com.intel.analytics.bigdl.ppml.crypto.{AES_CBC_PKCS5PADDING, BigDLEncrypt, DECRYPT, ENCRYPT, PLAIN_TEXT}
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.sql.SparkSession

import java.io.{File, FileWriter}

class EncryptDataFrameSpec extends DataFrameHelper {

  val ppmlArgs = Map(
      "spark.bigdl.kms.simple.id" -> appid,
      "spark.bigdl.kms.simple.key" -> appkey,
      "spark.bigdl.kms.key.primary" -> primaryKeyPath,
      "spark.bigdl.kms.key.data" -> dataKeyPath
  )
  val sparkConf = new SparkConf().setMaster("local[4]")
  val sc = PPMLContext.initPPMLContext(sparkConf, "SimpleQuery", ppmlArgs)

  "textfile read from plaint text file" should "work" in {
    val file = sc.textFile(plainFileName).collect()
    file.mkString("\n") + "\n" should be (data)
    val file2 = sc.textFile(encryptFileName, cryptoMode = AES_CBC_PKCS5PADDING).collect()
    file2.mkString("\n") + "\n" should be (data)
  }

  "sparkSession.read" should "work" in {
    val sparkSession: SparkSession = SparkSession.builder().getOrCreate()
    val df = sparkSession.read.csv(plainFileName)
    val d = df.collect().map(v => s"${v.get(0)},${v.get(1)},${v.get(2)}").mkString("\n")
    d + "\n" should be (data)
    val df2 = sparkSession.read.option("header", "true").csv(plainFileName)
    val d2 = df2.schema.map(_.name).mkString(",") + "\n" +
      df2.collect().map(v => s"${v.get(0)},${v.get(1)},${v.get(2)}").mkString("\n")
    d2 + "\n" should be (data)
  }

  "read from plain csv with header" should "work" in {
    val df = sc.read(cryptoMode = PLAIN_TEXT)
      .option("header", "true").csv(plainFileName)
    val d = df.schema.map(_.name).mkString(",") + "\n" +
      df.collect().map(v => s"${v.get(0)},${v.get(1)},${v.get(2)}").mkString("\n")
    d + "\n" should be (data)
  }

  "read from plain csv with header2" should "work" in {
    val df = sc.read(cryptoMode = PLAIN_TEXT)
      .option("header", "true").csv(plainFileName)
    sc.write(df, cryptoMode = PLAIN_TEXT)
      .option("codec", "com.intel.analytics.bigdl.ppml.crypto.CryptoCodec")
      .option("header", "true").csv(dir + "/gzip")
    val df2 = sc.read(cryptoMode = PLAIN_TEXT)
      .option("codec", "com.intel.analytics.bigdl.ppml.crypto.CryptoCodec")
      .option("header", "true").csv(dir + "/gzip")
    val c = df2.count()
    println(df2.count())
  }

  "read from plain csv with header4" should "work" in {
    val df = sc.read(cryptoMode = PLAIN_TEXT)
      .option("header", "true").csv(plainFileName)
    df.write
      .option("compression", "com.intel.analytics.bigdl.ppml.crypto.CryptoCodec")
      .option("header", "true").parquet(dir + "/parquet")
    val df2 = sc.getSparkSession().read
      .option("codec", "com.intel.analytics.bigdl.ppml.crypto.CryptoCodec")
      .option("header", "true").parquet(dir + "/parquet").count()
//    val c = df2.count()
    Thread.sleep(100000)
//    println(df2.count())
  }

  "read from plain csv with json" should "work" in {
    val df = sc.read(cryptoMode = PLAIN_TEXT)
      .option("header", "true").csv(plainFileName)
    df.write
      .option("codec", "com.intel.analytics.bigdl.ppml.crypto.CryptoCodec")
      .option("header", "true").json(dir + "/json")
    val df2 = sc.getSparkSession().read
      .option("codec", "com.intel.analytics.bigdl.ppml.crypto.CryptoCodec")
      .option("header", "true").json(dir + "/json")
    val c = df2.count()
    println(df2.count())
  }

  "read from encrypted csv with header" should "work" in {
    val df = sc.read(cryptoMode = AES_CBC_PKCS5PADDING)
      .option("header", "true").csv(encryptFileName)
    val d = df.schema.map(_.name).mkString(",") + "\n" +
      df.collect().map(v => s"${v.get(0)},${v.get(1)},${v.get(2)}").mkString("\n")
    d + "\n" should be (data)
  }

  "read from plain csv without header" should "work" in {
    val df = sc.read(cryptoMode = PLAIN_TEXT).csv(plainFileName)
    val d = df.collect().map(v => s"${v.get(0)},${v.get(1)},${v.get(2)}").mkString("\n")
    d + "\n" should be (data)
  }

  "read from encrypted csv without header" should "work" in {
    val df = sc.read(cryptoMode = AES_CBC_PKCS5PADDING).csv(encryptFileName)
    val d = df.collect().map(v => s"${v.get(0)},${v.get(1)},${v.get(2)}").mkString("\n")
    d + "\n" should be (data)
  }

  "save df" should "work" in {
    val enWriteCsvPath = dir + "/en_write_csv"
    val writeCsvPath = dir + "/write_csv"
    val df = sc.read(cryptoMode = AES_CBC_PKCS5PADDING).csv(encryptFileName)
    df.count() should be (totalNum + 1) // with header
    sc.write(df, cryptoMode = AES_CBC_PKCS5PADDING).csv(enWriteCsvPath)
    sc.write(df, cryptoMode = PLAIN_TEXT).csv(writeCsvPath)

    val readEn = sc.read(cryptoMode = AES_CBC_PKCS5PADDING).csv(enWriteCsvPath)
    val readEnCollect = readEn.collect().map(v =>
      s"${v.get(0)},${v.get(1)},${v.get(2)}").mkString("\n")
    readEnCollect + "\n" should be (data)

    val readPlain = sc.read(cryptoMode = PLAIN_TEXT).csv(writeCsvPath)
    val readPlainCollect = readPlain.collect().map(v =>
      s"${v.get(0)},${v.get(1)},${v.get(2)}").mkString("\n")
    readPlainCollect + "\n" should be (data)
  }

  "save df with multi-partition" should "work" in {
    val enWriteCsvPath = dir + "/en_write_csv_multi"
    val writeCsvPath = dir + "/write_csv_multi"
    val df = sc.read(cryptoMode = AES_CBC_PKCS5PADDING)
      .option("header", "true").csv(encryptFileName).repartition(4)
    df.count() should be (totalNum) // with header
    sc.write(df, cryptoMode = AES_CBC_PKCS5PADDING).csv(enWriteCsvPath)
    sc.write(df, cryptoMode = PLAIN_TEXT).csv(writeCsvPath)

    val readEn = sc.read(cryptoMode = AES_CBC_PKCS5PADDING).csv(enWriteCsvPath)
    readEn.count() should be (totalNum)
    val readEnCollect = readEn.collect()
      .sortWith((a, b) => a.get(0).toString < b.get(0).toString)
      .sortWith((a, b) => a.get(1).toString.toInt < b.get(1).toString.toInt)
      .map(v => s"${v.get(0)},${v.get(1)},${v.get(2)}")
      .mkString("\n")
    header + readEnCollect + "\n" should be (data)

    val readPlain = sc.read(cryptoMode = PLAIN_TEXT).csv(writeCsvPath)
    readPlain.count() should be (totalNum)
    val readPlainCollect = readPlain.collect()
      .sortWith((a, b) => a.get(0).toString < b.get(0).toString)
      .sortWith((a, b) => a.get(1).toString.toInt < b.get(1).toString.toInt)
      .map(v => s"${v.get(0)},${v.get(1)},${v.get(2)}").mkString("\n")
    header + readPlainCollect + "\n" should be (data)
  }

  "encrypt/Decrypt BigFile" should "work" in {
    val bigFile = dir + "/big_file.csv"
    val outFile = dir + "/plain_big_file.csv"
    val enFile = dir + "/en_big_file.csv"
    val fw = new FileWriter(bigFile)
    val genNum = 40000000
    (0 until genNum).foreach {i =>
      fw.append(s"gdni,$i,Engineer\npglyal,$i,Engineer\nyvomq,$i,Developer\n")
    }
    fw.close()
    val crypto = new BigDLEncrypt()
    crypto.init(AES_CBC_PKCS5PADDING, ENCRYPT, dataKeyPlaintext)
    crypto.doFinal(bigFile, enFile)

    crypto.init(AES_CBC_PKCS5PADDING, DECRYPT, dataKeyPlaintext)
    crypto.doFinal(enFile, outFile)
    new File(bigFile).length() should be (new File(outFile).length())

    val read = sc.read(AES_CBC_PKCS5PADDING).csv(enFile)
    read.count() should be (genNum * 3)
  }

}

