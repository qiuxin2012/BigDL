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


package com.intel.analytics.bigdl.ppml.fl.nn

import com.intel.analytics.bigdl.ckks.CKKS
import java.util
import java.util.Map
import com.intel.analytics.bigdl.dllib.nn.{BCECriterion, MSECriterion, Sigmoid, View}
import com.intel.analytics.bigdl.dllib.optim.Top1Accuracy
import com.intel.analytics.bigdl.ppml.fl.base.DataHolder
import com.intel.analytics.bigdl.ppml.fl.common.FLPhase._
import com.intel.analytics.bigdl.ppml.fl.generated.NNServiceGrpc
import com.intel.analytics.bigdl.ppml.fl.generated.FlBaseProto._
import com.intel.analytics.bigdl.ppml.fl.generated.NNServiceProto._
import io.grpc.stub.StreamObserver
import org.apache.commons.lang3.exception.ExceptionUtils
import org.apache.logging.log4j.LogManager

import java.util.concurrent.ConcurrentHashMap
import collection.JavaConverters._

class NNServiceImpl(clientNum: Int) extends NNServiceGrpc.NNServiceImplBase {
  private val logger = LogManager.getLogger(getClass)
  private var aggregatorMap: Map[String, NNAggregator] = null
  initAggregatorMap()

  private def initAggregatorMap(): Unit = {
    aggregatorMap = new util.HashMap[String, NNAggregator]
    aggregatorMap.put("vfl_logistic_regression", VFLNNAggregator(1, Sigmoid[Float](),
      null, BCECriterion[Float](), Array(new Top1Accuracy())))
    aggregatorMap.put("vfl_linear_regression", VFLNNAggregator(1, View[Float](),
      null, MSECriterion[Float](), Array(new Top1Accuracy())))
    aggregatorMap.put("hfl_logistic_regression", new HFLNNAggregator())
    aggregatorMap.asScala.foreach(entry => {
      entry._2.setClientNum(clientNum)
    })
  }

  def initCkksAggregator(secretPath: String): Unit = {
    val secret = CKKS.loadSecret(secretPath)
    initCkksAggregator(secret)
  }
  def initCkksAggregator(secret: Array[Array[Byte]]): Unit = {
    val ckks = new CKKS()
    val ckksCommonInstance = ckks.createCkksCommonInstance(secret)
    val ckksAggregator = new VFLNNAggregatorCkks(ckksCommonInstance)
    ckksAggregator.setClientNum(clientNum)
    aggregatorMap.put("vfl_logistic_regression_ckks", ckksAggregator)
  }

  override def train(request: TrainRequest,
                     responseObserver: StreamObserver[TrainResponse]): Unit = {
    val clientUUID = request.getClientuuid
    logger.info("Server get train request from client: " + clientUUID)
    val data = request.getData
    val version = data.getMetaData.getVersion
    val aggregator = aggregatorMap.get(request.getAlgorithm)
    try {
      aggregator.putClientData(TRAIN, clientUUID, version, new DataHolder(data))
      logger.info(s"$clientUUID getting server new data to update local")
      val responseData = aggregator.getStorage(TRAIN).serverData
      if (responseData == null) {
        val response = "Data requested doesn't exist"
        responseObserver.onNext(TrainResponse.newBuilder.setResponse(response).setCode(0).build)
      }
      else {
        val response = "Download data successfully"
        responseObserver.onNext(TrainResponse.newBuilder.setResponse(response)
          .setData(responseData).setCode(1).build)
      }
      responseObserver.onCompleted()
    } catch {
      case e: Exception =>
        val errorMsg = ExceptionUtils.getStackTrace(e)
        logger.error(errorMsg)
        val response = TrainResponse.newBuilder.setResponse(errorMsg).setCode(1).build
        responseObserver.onNext(response)
        responseObserver.onCompleted()
    } finally {

    }

  }
  override def evaluate(request: EvaluateRequest,
                        responseObserver: StreamObserver[EvaluateResponse]): Unit = {
    val clientUUID = request.getClientuuid
    val data = request.getData
    val version = data.getMetaData.getVersion
    val hasReturn = request.getReturn
    val aggregator = aggregatorMap.get(request.getAlgorithm)
    try {
      aggregator.setShouldReturn(hasReturn)
      aggregator.putClientData(EVAL, clientUUID, version, new DataHolder(data))
      val responseData = aggregator.getStorage(EVAL).serverData
      if (responseData == null) {
        val response = "Data requested doesn't exist"
        responseObserver.onNext(EvaluateResponse.newBuilder.setResponse(response).setCode(0).build)
      }
      else if (hasReturn) {
        val response = "Evaluate finishes"
        // TODO: return type need to be refactor
        val resultString = aggregator.getReturnMessage
        responseObserver.onNext(EvaluateResponse.newBuilder
          .setResponse(response)
          .setMessage(resultString)
          .setData(responseData).setCode(1).build)
      }
      else {
        val response = "Evaluate batch uploaded successfully, continue to next batch"
        responseObserver.onNext(EvaluateResponse.newBuilder
          .setResponse(response).setData(responseData).setCode(1).build)
      }
      responseObserver.onCompleted()
    } catch {
      case e: Exception =>
        val errorMsg = ExceptionUtils.getStackTrace(e)
        logger.error(errorMsg)
        val response = EvaluateResponse.newBuilder.setResponse(errorMsg).setCode(1).build
        responseObserver.onNext(response)
        responseObserver.onCompleted()
    } finally {

    }
  }
  override def predict(request: PredictRequest,
                       responseObserver: StreamObserver[PredictResponse]): Unit = {
    val clientUUID = request.getClientuuid
    val data = request.getData
    val version = data.getMetaData.getVersion
    val aggregator = aggregatorMap.get(request.getAlgorithm)
    try {
      aggregator.putClientData(PREDICT, clientUUID, version, new DataHolder(data))
      val responseData = aggregator.getStorage(PREDICT).serverData
      if (responseData == null) {
        val response = "Data requested doesn't exist"
        responseObserver.onNext(PredictResponse.newBuilder.setResponse(response).setCode(0).build)
      }
      else {
        val response = "Download data successfully"
        responseObserver.onNext(PredictResponse.newBuilder.setResponse(response)
          .setData(responseData).setCode(1).build)
      }
      responseObserver.onCompleted()
    } catch {
      case e: Exception =>
        val errorMsg = ExceptionUtils.getStackTrace(e)
        logger.error(errorMsg)
        val response = PredictResponse.newBuilder.setResponse(errorMsg).setCode(1).build
        responseObserver.onNext(response)
        responseObserver.onCompleted()
    } finally {

    }
  }

}

