package com.intel.analytics.bigdl.ppml.crypto.dataframe

import com.intel.analytics.bigdl.ppml.PPMLContext
import com.intel.analytics.bigdl.ppml.crypto.PLAIN_TEXT
import org.apache.spark.SparkConf

class EncryptedJsonSpec extends DataFrameHelper {
  override val repeatedNum = 100

  val ppmlArgs = Map(
    "spark.bigdl.kms.simple.id" -> appid,
    "spark.bigdl.kms.simple.key" -> appkey,
    "spark.bigdl.kms.key.primary" -> primaryKeyPath,
    "spark.bigdl.kms.key.data" -> dataKeyPath
  )
  val conf = new SparkConf().setMaster("local[4]")
  val sc = PPMLContext.initPPMLContext(conf, "SimpleQuery", ppmlArgs)

  val sparkSession = sc.getSparkSession()
  import sparkSession.implicits._

  "json" should "work" in {
    val plainJsonPath = dir + "/plain-json"
    val df = sc.read(cryptoMode = PLAIN_TEXT)
      .option("header", "true").csv(plainFileName)
    df.write.option("compression", "gzip").json(plainJsonPath)
    val jsonDf = sparkSession.read.json(plainJsonPath)
    jsonDf.count()

  }

}
