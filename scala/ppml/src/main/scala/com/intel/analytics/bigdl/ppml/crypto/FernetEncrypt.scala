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

package com.intel.analytics.bigdl.ppml.crypto

import com.intel.analytics.bigdl.dllib.utils.Log4Error
import com.intel.analytics.bigdl.ppml.crypto.CryptoMode

import java.io._
import java.nio.file.{Files, Path, Paths}
import java.security.SecureRandom
import java.time.Instant
import java.util.Arrays
import javax.crypto.spec.{IvParameterSpec, SecretKeySpec}
import javax.crypto.{Cipher, Mac}
import org.apache.spark.input.PortableDataStream

import java.nio.ByteBuffer

class FernetEncrypt extends Crypto {
  protected var cipher: Cipher = null
  protected var mac: Mac = null
  protected var ivParameterSpec: IvParameterSpec = null
  protected var opMode: OperationMode = null
  protected var initializationVector: Array[Byte] = null
  def init(cryptoMode: CryptoMode, mode: OperationMode, dataKeyPlaintext: String): Unit = {
    opMode = mode
    val secret = dataKeyPlaintext.getBytes()
    // key encrypt
    val signingKey = Arrays.copyOfRange(secret, 0, 16)
    val encryptKey = Arrays.copyOfRange(secret, 16, 32)
    initializationVector = Arrays.copyOfRange(secret, 0, 16)
    ivParameterSpec = new IvParameterSpec(initializationVector)
    val encryptionKeySpec = new SecretKeySpec(encryptKey, cryptoMode.secretKeyAlgorithm)
    cipher = Cipher.getInstance(cryptoMode.encryptionAlgorithm)
    cipher.init(mode.opmode, encryptionKeySpec, ivParameterSpec)
    mac = Mac.getInstance(cryptoMode.signingAlgorithm)
    val signingKeySpec = new SecretKeySpec(signingKey, cryptoMode.signingAlgorithm)
    mac.init(signingKeySpec)
  }

  protected var signingDataStream: DataOutputStream = null

  def genFileHeader(): Array[Byte] = {
    Log4Error.invalidOperationError(cipher != null,
      s"you should init FernetEncrypt first.")
    val timestamp: Instant = Instant.now()
    val signingByteBuffer = ByteBuffer.allocate(1 + 8 + ivParameterSpec.getIV.length)
    val version: Byte = (0x80).toByte
    signingByteBuffer.put(version)
    signingByteBuffer.putLong(timestamp.getEpochSecond())
    signingByteBuffer.put(ivParameterSpec.getIV())
    signingByteBuffer.array()
  }

  def verifyFileHeader(header: Array[Byte]): Unit = {
    val inputStream: ByteArrayInputStream = new ByteArrayInputStream(header)
    val dataStream: DataInputStream = new DataInputStream(inputStream)
    val version: Byte = dataStream.readByte()
    if (version.compare((0x80).toByte) != 0) {
      throw new EncryptRuntimeException("File header version error!")
    }
    val timestampSeconds: Long = dataStream.readLong()
    val initializationVector: Array[Byte] = read(dataStream, 16)
    if (!initializationVector.sameElements(this.initializationVector)) {
      throw new EncryptRuntimeException("File header not match!")
    }
  }

  def update(content: Array[Byte]): Array[Byte] = {
    val cipherText: Array[Byte] = cipher.update(content)
    mac.update(cipherText)
    cipherText
  }

  def update(content: Array[Byte], offset: Int, len: Int): Array[Byte] = {
    val cipherText: Array[Byte] = cipher.update(content, offset, len)
    mac.update(cipherText, offset, len)
    cipherText
  }

  def doFinal(content: Array[Byte]): (Array[Byte], Array[Byte]) = {
    val cipherText: Array[Byte] = cipher.doFinal(content)
    val hmac: Array[Byte] = mac.doFinal(cipherText)
    (cipherText, hmac)
  }

  def doFinal(content: Array[Byte], offset: Int, len: Int): (Array[Byte], Array[Byte]) = {
    val cipherText: Array[Byte] = cipher.doFinal(content, offset, len)
    val hmac: Array[Byte] = mac.doFinal(cipherText.slice(offset, offset + len))
    (cipherText, hmac)
  }

  val blockSize = 1024 * 1024 // 1m per update
  val byteBuffer = new Array[Byte](blockSize)
  def encryptStream(inputStream: DataInputStream, outputStream: DataOutputStream): Unit = {
    val header = genFileHeader()
    outputStream.write(header)
    while (inputStream.available() > blockSize) {
      val readLen = inputStream.read(byteBuffer)
      outputStream.write(update(byteBuffer, 0, readLen))
    }
    val last = inputStream.read(byteBuffer)
    val (lastSlice, hmac) = doFinal(byteBuffer, 0, last)
    outputStream.write(lastSlice)
    outputStream.write(hmac)
  }

  val hmacSize = 32
  def decryptStream(inputStream: DataInputStream, outputStream: DataOutputStream): Unit = {
    val header = read(inputStream, 25)
    verifyFileHeader(header)
    while (inputStream.available() > blockSize) {
      val readLen = inputStream.read(byteBuffer)
      outputStream.write(update(byteBuffer, 0, readLen))
    }
    val last = inputStream.read(byteBuffer)
    val inputHmac = byteBuffer.slice(last - hmacSize, last)
    val (lastSlice, streamHmac) = doFinal(byteBuffer, 0, last - hmacSize)
    if(inputHmac.sameElements(streamHmac)) {
      throw new EncryptRuntimeException("hmac not match")
    }
    outputStream.write(lastSlice)
  }

  def decryptFile(binaryFilePath: String, savePath: String, dataKeyPlaintext: String): Unit = {
    Log4Error.invalidInputError(savePath != null && savePath != "",
      "decrypted file save path should be specified")
    val content: Array[Byte] = readBinaryFile(binaryFilePath) // Ciphertext file is read into Bytes
    val decryptedBytes = timing("FernetCryptos decrypt a single file...") {
      decryptContent(content, dataKeyPlaintext)
    }
    timing("FernetCryptos save a decrypted file") {
      writeBinaryFile(savePath, decryptedBytes)
    }
  }

  def encryptBytes(sourceBytes: Array[Byte], dataKeyPlaintext: String): Array[Byte] = {
    encryptContent(sourceBytes, dataKeyPlaintext)
  }

  def decryptBytes(sourceBytes: Array[Byte], dataKeyPlaintext: String): Array[Byte] = {
    timing("FernetCryptos decrypting bytes") {
      decryptContent(sourceBytes, dataKeyPlaintext)
    }
  }

  private def readBinaryFile(binaryFilePath: String): Array[Byte] = {
    Files.readAllBytes(Paths.get(binaryFilePath))
  }

  private def writeBinaryFile(savePath: String, content: Array[Byte]): Path = {
    Files.write(Paths.get(savePath), content)
  }

  private def writeStringToFile(savePath: String, content: String): Unit = {
    val bw = new BufferedWriter(new FileWriter(new File(savePath)))
    bw.write(content)
  }

  private def read(stream: DataInputStream, numBytes: Int): Array[Byte] = {
    val retval = new Array[Byte](numBytes)
    val bytesRead: Int = stream.read(retval)
    if (bytesRead < numBytes) {
      throw new EncryptRuntimeException("Not enough bits to read!")
    }
    retval
  }

  private def encryptContent(content: Array[Byte], dataKeyPlaintext: String): Array[Byte] = {

    val secret = dataKeyPlaintext.getBytes()

    //  get IV
    val random = new SecureRandom()
    val initializationVector: Array[Byte] = new Array[Byte](16)
    random.nextBytes(initializationVector)
    val ivParameterSpec: IvParameterSpec = new IvParameterSpec(initializationVector)

    // key encrypt
    val signingKey: Array[Byte] = Arrays.copyOfRange(secret, 0, 16)
    val encryptKey: Array[Byte] = Arrays.copyOfRange(secret, 16, 32)
    val encryptionKeySpec: SecretKeySpec = new SecretKeySpec(encryptKey, "AES")

    val cipher: Cipher = Cipher.getInstance(AES_CBC_PKCS5PADDING.encryptionAlgorithm)
    cipher.init(Cipher.ENCRYPT_MODE, encryptionKeySpec, ivParameterSpec)

    val cipherText: Array[Byte] = cipher.doFinal(content)
    val timestamp: Instant = Instant.now()

    // sign
    val byteStream: ByteArrayOutputStream = new ByteArrayOutputStream(25 + cipherText.length)
    val dataStream: DataOutputStream = new DataOutputStream(byteStream)

    val version: Byte = (0x80).toByte
    dataStream.writeByte(version)
    dataStream.writeLong(timestamp.getEpochSecond())
    dataStream.write(ivParameterSpec.getIV())
    dataStream.write(cipherText)

    val mac: Mac = Mac.getInstance("HmacSHA256")
    val signingKeySpec = new SecretKeySpec(signingKey, "HmacSHA256")
    mac.init(signingKeySpec)
    val hmac: Array[Byte] = mac.doFinal(byteStream.toByteArray())

    // to bytes
    val outByteStream: ByteArrayOutputStream = new ByteArrayOutputStream(57 + cipherText.length)
    val dataOutStream: DataOutputStream = new DataOutputStream(outByteStream)
    dataOutStream.writeByte(version)
    dataOutStream.writeLong(timestamp.getEpochSecond())
    dataOutStream.write(ivParameterSpec.getIV())
    dataOutStream.write(cipherText)
    dataOutStream.write(hmac)

    if (timestamp == null) {
      throw new EncryptRuntimeException("Timestamp cannot be null")
    }
    if (ivParameterSpec == null || ivParameterSpec.getIV().length != 16) {
      throw new EncryptRuntimeException("Initialization Vector must be 128 bits")
    }
    if (cipherText == null || cipherText.length % 16 != 0) {
      throw new EncryptRuntimeException("Ciphertext must be a multkmsServerIPle of 128 bits")
    }
    if (hmac == null || hmac.length != 32) {
      throw new EncryptRuntimeException("Hmac must be 256 bits")
    }

    outByteStream.toByteArray()
  }

  private def decryptContent(content: Array[Byte], dataKeyPlaintext: String): Array[Byte] = {

    val secret: Array[Byte] = dataKeyPlaintext.getBytes()

    val inputStream: ByteArrayInputStream = new ByteArrayInputStream(content)
    val dataStream: DataInputStream = new DataInputStream(inputStream)
    val version: Byte = dataStream.readByte()
    if (version.compare((0x80).toByte) != 0) {
      throw new EncryptRuntimeException("Version error!")
    }
    val encryptKey: Array[Byte] = Arrays.copyOfRange(secret, 16, 32)

    val timestampSeconds: Long = dataStream.readLong()

    val initializationVector: Array[Byte] = read(dataStream, 16)
    val ivParameterSpec = new IvParameterSpec(initializationVector)

    val cipherText: Array[Byte] = read(dataStream, content.length - 57)

    val hmac: Array[Byte] = read(dataStream, 32)
    if (initializationVector.length != 16) {
      throw new EncryptRuntimeException("Initialization Vector must be 128 bits")
    }
    if (cipherText == null || cipherText.length % 16 != 0) {
      throw new EncryptRuntimeException("Ciphertext must be a multkmsServerIPle of 128 bits")
    }
    if (hmac == null || hmac.length != 32) {
      throw new EncryptRuntimeException("hmac must be 256 bits")
    }

    val secretKeySpec = new SecretKeySpec(encryptKey, "AES")
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec)
    cipher.doFinal(cipherText)
  }

  override def decryptBigContent(
        ite: Iterator[(String, PortableDataStream)],
        dataKeyPlaintext: String): Iterator[String] = {
    val secret: Array[Byte] = dataKeyPlaintext.getBytes()
    var result: Iterator[String] = Iterator[String]()

    while (ite.hasNext == true) {
      val inputStream: DataInputStream = ite.next._2.open()
      val version: Byte = inputStream.readByte()
      if (version.compare((0x80).toByte) != 0) {
        throw new EncryptRuntimeException("Version error!")
      }
      val encryptKey: Array[Byte] = Arrays.copyOfRange(secret, 16, 32)

      val timestampSeconds: Long = inputStream.readLong()
      val initializationVector: Array[Byte] = read(inputStream, 16)
      val ivParameterSpec = new IvParameterSpec(initializationVector)

      val secretKeySpec = new SecretKeySpec(encryptKey, "AES")
      val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
      cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec)

      val blockSize = 102400000 // 100m per update
      var lastString = ""
      while (inputStream.available() > blockSize + 32) {
        val splitEncryptedBytes = read(inputStream, blockSize)
        val currentSplitDecryptString = new String(cipher.update(splitEncryptedBytes))
        val splitDecryptString = lastString + currentSplitDecryptString
        val splitDecryptStringArray = splitDecryptString.split("\r").flatMap(_.split("\n"))
        lastString = splitDecryptStringArray.last
        result = result ++ splitDecryptStringArray.dropRight(1)
      }

      val lastCipherText: Array[Byte] = read(inputStream, inputStream.available() - 32)
      val lastDecryptString = lastString + (new String(cipher.doFinal(lastCipherText)))
      val splitDecryptStringArray = lastDecryptString.split("\r").flatMap(_.split("\n"))
      result = result ++ splitDecryptStringArray

      val hmac: Array[Byte] = read(inputStream, 32)
      if (initializationVector.length != 16) {
        throw new EncryptRuntimeException("Initialization Vector must be 128 bits")
      }
      if (hmac == null || hmac.length != 32) {
        throw new EncryptRuntimeException("hmac must be 256 bits")
      }
      if (inputStream.available > 0) {
        throw new EncryptRuntimeException("inputStream still has contents")
      }
    }
    result

  }

}
