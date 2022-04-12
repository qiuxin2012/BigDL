package com.intel.analytics.bigdl.ppml.cryptos

class CryptoRuntimeException(message: String, cause: Throwable)
  extends RuntimeException(message) {
  if (cause != null) {
    initCause(cause)
  }
  def this(message: String) = this(message, null)
}
