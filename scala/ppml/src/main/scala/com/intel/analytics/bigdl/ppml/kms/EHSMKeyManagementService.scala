package com.intel.analytics.bigdl.ppml.kms

import com.intel.analytics.bigdl.ppml.utils.{EHSMParams, KeyReaderWriter}
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.HttpClients
import org.apache.http.message.BasicHeader
import org.apache.http.util.EntityUtils
import org.json.JSONObject

object EHSM_CONVENTION {
  val ACTION_CREATE_KEY = "CreateKey"
  val ACTION_GENERATE_DATAKEY_WO_PLAINTEXT = "GenerateDataKeyWithoutPlaintext"
  val ACTION_DECRYPT = "Decrypt"

  val PAYLOAD_KEYSPEC = "keyspec"
  val PAYLOAD_ORIGIN = "origin"
  val PAYLOAD_AAD = "aad"
  val PAYLOAD_KEY_ID = "keyid"
  val PAYLOAD_KEY_LENGTH = "keylen"
  val PAYLOAD_CIPHER_TEXT = "ciphertext"
  val PAYLOAD_PLAIN_TEXT = "plaintext"


  val KEYSPEC_EH_AES_GCM_128 = "EH_AES_GCM_128"

  val ORIGIN_EH_INTERNAL_KEY = "EH_INTERNAL_KEY"
}


class EHSMKeyManagementService(kmsServerIP: String, kmsServerPort: String, ehsmAPPID: String, ehsmAPPKEY: String) extends KeyManagementService {

  val keyReaderWriter = new KeyReaderWriter

  enroll()

  def enroll(): (String, String) = {
    timing("EHSMKeyManagementService enroll") {
      setAppIdAndKey(ehsmAPPID, ehsmAPPKEY)
      (ehsmAPPID, ehsmAPPKEY)
    }
  }

  def retrievePrimaryKey(primaryKeySavePath: String) = {
    require(primaryKeySavePath != null && primaryKeySavePath != "", "primaryKeySavePath should be specified")
    val action: String = EHSM_CONVENTION.ACTION_CREATE_KEY
    val currentTime = System.currentTimeMillis() // ms
    val timestamp = s"$currentTime"
    val ehsmParams = new EHSMParams(_appid, _appkey, timestamp)
    ehsmParams.addPayloadElement(EHSM_CONVENTION.PAYLOAD_KEYSPEC, EHSM_CONVENTION.KEYSPEC_EH_AES_GCM_128)
    ehsmParams.addPayloadElement(EHSM_CONVENTION.PAYLOAD_ORIGIN, EHSM_CONVENTION.ORIGIN_EH_INTERNAL_KEY)

    val primaryKeyCiphertext: String = timing("EHSMKeyManagementService request for primaryKeyCiphertext") {
      val postString: String = ehsmParams.getPostJSONString
      val postResult = postRequest(action, postString)
      postResult.getString(EHSM_CONVENTION.PAYLOAD_KEY_ID)
    }
    keyReaderWriter.writeKeyToFile(primaryKeySavePath, primaryKeyCiphertext)
  }

  def retrieveDataKey(primaryKeyPath: String, dataKeySavePath: String) = {
    require(primaryKeyPath != null && primaryKeyPath != "", "primaryKeyPath should be specified")
    require(dataKeySavePath != null && dataKeySavePath != "", "dataKeySavePath should be specified")
    val action = EHSM_CONVENTION.ACTION_GENERATE_DATAKEY_WO_PLAINTEXT
    val encryptedPrimaryKey: String = keyReaderWriter.readKeyFromFile(primaryKeyPath)
    val currentTime = System.currentTimeMillis() // ms
    val timestamp = s"$currentTime"
    val ehsmParams = new EHSMParams(_appid, _appkey, timestamp)
    ehsmParams.addPayloadElement(EHSM_CONVENTION.PAYLOAD_AAD, "test")
    ehsmParams.addPayloadElement(EHSM_CONVENTION.PAYLOAD_KEY_ID, encryptedPrimaryKey)
    ehsmParams.addPayloadElement(EHSM_CONVENTION.PAYLOAD_KEY_LENGTH, "32")

    val dataKeyCiphertext: String = timing("EHSMKeyManagementService request for dataKeyCiphertext") {
      val postString: String = ehsmParams.getPostJSONString
      val postResult = postRequest(action, postString)
      postResult.getString(EHSM_CONVENTION.PAYLOAD_CIPHER_TEXT)
    }
    keyReaderWriter.writeKeyToFile(dataKeySavePath, dataKeyCiphertext)
  }


  def retrieveDataKeyPlaintext(primaryKeyPath: String, dataKeyPath: String): String = {
    require(primaryKeyPath != null && primaryKeyPath != "", "primaryKeyPath should be specified")
    require(dataKeyPath != null && dataKeyPath != "", "dataKeyPath should be specified")
    val action: String = EHSM_CONVENTION.ACTION_DECRYPT
    val encryptedPrimaryKey: String = keyReaderWriter.readKeyFromFile(primaryKeyPath)
    val encryptedDataKey: String = keyReaderWriter.readKeyFromFile(dataKeyPath)
    val currentTime = System.currentTimeMillis() // ms
    val timestamp = s"$currentTime"
    val ehsmParams = new EHSMParams(_appid, _appkey, timestamp)
    ehsmParams.addPayloadElement(EHSM_CONVENTION.PAYLOAD_AAD, "test")
    ehsmParams.addPayloadElement(EHSM_CONVENTION.PAYLOAD_CIPHER_TEXT, encryptedDataKey)
    ehsmParams.addPayloadElement(EHSM_CONVENTION.PAYLOAD_KEY_ID, encryptedPrimaryKey)
    val dataKeyPlaintext: String = timing("EHSMKeyManagementService request for dataKeyPlaintext") {
      val postString: String = ehsmParams.getPostJSONString
      val postResult = postRequest(action, postString)
      postResult.getString(EHSM_CONVENTION.PAYLOAD_PLAIN_TEXT)
    }
    dataKeyPlaintext
  }

  private def postRequest(action: String, postString: String): JSONObject = {
    val url: String = constructUrl(action)
    val response: String = retrieveResponse(url, postString)
    val jsonObj: JSONObject = new JSONObject(response)
    val result: JSONObject = new JSONObject(jsonObj.getString("result"))
    result
  }

  private def constructUrl(action: String): String = {
    s"http://$kmsServerIP:$kmsServerPort/ehsm?Action=$action"
  }

  private def retrieveResponse(url: String, params: String = null): String = {
    val httpClient = HttpClients.createDefault()
    val post = new HttpPost(url)
    post.setHeader(new BasicHeader("Content-Type", "application/json"));
    if (params != null) {
      post.setEntity(new StringEntity(params, "UTF-8"))
    }
    val response = httpClient.execute(post)
    EntityUtils.toString(response.getEntity, "UTF-8")
  }

  private def setAppIdAndKey(appid: String, appkey: String) = {
    _appid = appid
    _appkey = appkey
  }

}
