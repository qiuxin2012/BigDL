package com.intel.analytics.bigdl.ppml.kms

import scala.collection.mutable.HashMap
import java.io._
import scala.util.Random
import com.intel.analytics.bigdl.ppml.utils.KeyReaderWriter

class SimpleKeyManagementService extends KeyManagementService {
  var enrollMap:HashMap[String,String] = new HashMap[String,String]
  val keyReaderWriter = new KeyReaderWriter

  enroll()

  def enroll():(String, String) = {
    val appid:String = (1 to 12).map(x => Random.nextInt(10)).mkString
    val appkey:String = (1 to 12).map(x => Random.nextInt(10)).mkString
    enrollMap.contains(appid) match {
      case true => setAppIdAndKey(appid, appkey) //TODO
      case false => setAppIdAndKey(appid, appkey)
    }
    (appid, appkey)
  }

  def retrievePrimaryKey(primaryKeySavePath: String) = {
    timing("SimpleKeyManagementService retrievePrimaryKey") {
      require(enrollMap.keySet.contains(_appid) && enrollMap(_appid) == _appkey, "appid and appkey do not match!")
      require(primaryKeySavePath != null && primaryKeySavePath != "", "primaryKeySavePath should be specified")
      val randVect = (1 to 16).map { x => Random.nextInt(10) }
      val encryptedPrimaryKey:String = randVect.mkString
      keyReaderWriter.writeKeyToFile(primaryKeySavePath, encryptedPrimaryKey)
    }
  }

  def retrieveDataKey(primaryKeyPath: String, dataKeySavePath: String) = {
    timing("SimpleKeyManagementService retrieveDataKey") {
      require(enrollMap.keySet.contains(_appid) && enrollMap(_appid) == _appkey, "appid and appkey do not match!")
      require(primaryKeyPath != null && primaryKeyPath != "", "primaryKeyPath should be specified")
      require(dataKeySavePath != null && dataKeySavePath != "", "dataKeySavePath should be specified")
      val primaryKeyPlaintext:String = keyReaderWriter.readKeyFromFile(primaryKeyPath)
      val randVect = (1 to 16).map { x => Random.nextInt(10) }
      val dataKeyPlaintext:String = randVect.mkString
      var dataKeyCiphertext:String = ""
      for(i <- 0 until 16){
        dataKeyCiphertext += '0' + ((primaryKeyPlaintext(i) - '0') + (dataKeyPlaintext(i) - '0')) % 10
      }
      keyReaderWriter.writeKeyToFile(dataKeySavePath, dataKeyCiphertext)
    }
  }

  def retrieveDataKeyPlaintext(primaryKeyPath: String, dataKeyPath: String): String = {
    timing("SimpleKeyManagementService retrieveDataKeyPlaintext") {
      require(enrollMap.keySet.contains(_appid) && enrollMap(_appid) == _appkey, "appid and appkey do not match!")
      require(primaryKeyPath != null && primaryKeyPath != "", "primaryKeyPath should be specified")
      require(dataKeyPath != null && dataKeyPath != "", "dataKeyPath should be specified")
      val primaryKeyCiphertext:String = keyReaderWriter.readKeyFromFile(primaryKeyPath)
      val dataKeyCiphertext:String = keyReaderWriter.readKeyFromFile(dataKeyPath)
      var dataKeyPlaintext:String = ""
      for(i <- 0 until 16){
        dataKeyPlaintext += '0' + ((dataKeyCiphertext(i) - '0') - (primaryKeyCiphertext(i) - '0') + 10) % 10
      }
      dataKeyPlaintext
    }
  }

  private def setAppIdAndKey(appid:String, appkey:String) = {
    _appid = appid
    _appkey = appkey
    enrollMap(_appid) = _appkey
  }

}

object SimpleKeyManagementService {
  def apply(): SimpleKeyManagementService = {
    new SimpleKeyManagementService()
  }
}
