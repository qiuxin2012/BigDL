package com.intel.analytics.bigdl.ppml.cryptos

import com.intel.analytics.bigdl.ppml.utils.Supportive

trait Cryptos extends Supportive with Serializable {
  def encryptFile(sourceFilePath:String, saveFilePath:String, dataKeyPlaintext:String)
  def decryptFile(sourceFilePath:String, saveFilePath:String, dataKeyPlaintext:String)
  def encryptBytes(sourceBytes:Array[Byte], dataKeyPlaintext:String): Array[Byte]
  def decryptBytes(sourceBytes:Array[Byte], dataKeyPlaintext:String): Array[Byte]
}

object CryptoModeEnum extends Enumeration {
  type CryptoModeEnum = Value
  val PLAIN_TEXT = Value("plain_text", "plain_text")
  val AES_CBC_PKCS5PADDING = Value("AES/CBC/PKCS5Padding", "AES/CBC/PKCS5Padding")
  val UNKNOWN = Value("UNKNOWN", "UNKNOWN")
  class CryptoModeEnumVal(name: String, val value: String) extends Val(nextId, name)
  protected final def Value(name: String, value: String): CryptoModeEnumVal = new CryptoModeEnumVal(name, value)
  def parse(s: String) = values.find(_.toString.toLowerCase() == s.toLowerCase).getOrElse(CryptoModeEnum.UNKNOWN)
}
