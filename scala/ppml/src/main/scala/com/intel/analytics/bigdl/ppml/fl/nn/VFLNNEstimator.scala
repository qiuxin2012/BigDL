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

import com.intel.analytics.bigdl.Module
import com.intel.analytics.bigdl.dllib.feature.dataset.{LocalDataSet, MiniBatch}
import com.intel.analytics.bigdl.dllib.keras.models.InternalOptimizerUtil
import com.intel.analytics.bigdl.dllib.keras.models.InternalOptimizerUtil.getParametersFromModel
import com.intel.analytics.bigdl.dllib.nn.abstractnn.Activity
import com.intel.analytics.bigdl.dllib.optim.OptimMethod
import com.intel.analytics.bigdl.dllib.tensor.Tensor
import com.intel.analytics.bigdl.ppml.fl.base.Estimator
import com.intel.analytics.bigdl.ppml.fl.generated.FlBaseProto._
import com.intel.analytics.bigdl.ppml.fl.generated.NNServiceProto.EvaluateResponse
import com.intel.analytics.bigdl.ppml.fl.FLContext
import com.intel.analytics.bigdl.ppml.fl.utils.FLClientClosable
import com.intel.analytics.bigdl.ppml.fl.utils.ProtoUtils._
import org.apache.logging.log4j.LogManager

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

class VFLNNEstimator(algorithm: String,
                     model: Module[Float],
                     optimMethod: OptimMethod[Float],
                     threadNum: Int = 1) extends Estimator with FLClientClosable {
  val logger = LogManager.getLogger(getClass)
  val (weight, grad) = getParametersFromModel(model)
  var iteration = 0
  protected val evaluateResults = mutable.Map[String, ArrayBuffer[Float]]()

  /**
   * Train VFL model
   * For each batch, client estimator upload output tensor to server aggregator,
   * the server return the loss to client, and client use the loss to do backward propagation
   * @param endEpoch epoch of training
   * @param trainDataSet
   * @param valDataSet
   * @return
   */
  def train(endEpoch: Int,
            trainDataSet: LocalDataSet[MiniBatch[Float]],
            valDataSet: LocalDataSet[MiniBatch[Float]]): Module[Float] = {
    val clientUUID = flClient.getClientUUID()
    val size = trainDataSet.size()
    iteration = 0
    (0 until endEpoch).foreach { epoch =>
      val dataSet = trainDataSet.data(true)
      var count = 0
      while (count < size) {
        logger.debug(s"training next batch, progress: $count/$size, epoch: $epoch/$endEpoch")
        val miniBatch = dataSet.next()
        miniBatch.size()
        InternalOptimizerUtil.getStateFromOptiMethod(optimMethod)
          .update("epoch", epoch + 1)
        InternalOptimizerUtil.getStateFromOptiMethod(optimMethod)
          .update("neval", iteration + 1)
        val input = miniBatch.getInput()
        val target = miniBatch.getTarget()
        trainStep(input, target)
        count += miniBatch.size()
      }
      if (valDataSet != null) {
        model.evaluate()
        evaluate(valDataSet)
      }

    }

    model
  }
  def trainStep(input: Activity,
                target: Activity): Unit = {
    model.training()
    model.zeroGradParameters()
    val output = model.forward(input)
    println(output.toTensor[Float].mean())
    println(output.toTensor[Float].min())
    println(output.toTensor[Float].max())

    // Upload to PS
    val metadata = MetaData.newBuilder
      .setName(s"${model.getName()}_output").setVersion(iteration).build
    val tableProto = outputTargetToTableProto(model.output, target, metadata)
    val gradInput = flClient.nnStub.train(tableProto, algorithm)

    // model replace
    val errors = getTensor("gradInput", gradInput)
    val loss = getTensor("loss", gradInput).mean()
    logger.info(s"Loss: ${loss}")
    model.backward(input, errors)
    logger.debug(s"Model doing backward, version: $iteration")
    optimMethod.optimize(_ => (loss, grad), weight)

    iteration += 1
  }

  /**
   * Evaluate VFL model
   * For each batch, client estimator upload output tensor to server aggregator,
   * the server keep updating the validation result until last batch of data,
   * then return the evaluation result
   * @param dataSet the data to evaluate
   */
  override def evaluate(dataSet: LocalDataSet[MiniBatch[Float]]): Unit = {
    val dataSize = dataSet.size()
    val data = dataSet.data(false)
    var count = 0
    var iteration = 0
    while (count < dataSize) {
      logger.debug(s"evaluating next batch, progress: $count/$dataSize")
      model.evaluate()
      val miniBatch = data.next()
      val input = miniBatch.getInput()
      val target = miniBatch.getTarget()
      val output = model.forward(input)

      val metadata = MetaData.newBuilder
        .setName(s"${model.getName()}_output").setVersion(iteration).build
      val tableProto = outputTargetToTableProto(model.output, target, metadata)
      val hasReturn = if (!data.hasNext) true else false
      val evaluateResponse = flClient.nnStub.evaluate(tableProto, algorithm, hasReturn)
      logger.info(evaluateResponse.getResponse)
      if (hasReturn) {
        logger.info(s"Evaluate result: ${evaluateResponse.getMessage}")
      }
      iteration += 1
      count += miniBatch.size()
    }
  }

  /**
  *n Predict VFL model
   * For each batch, client estimator upload output tensor to server aggregator,
   * the server return the output to client, and client store the output and finally return
   * all the output of input dataset
   * @param dataSet the data to predict
   * @return
   */
  override def predict(dataSet: LocalDataSet[MiniBatch[Float]]): Array[Activity] = {
    val dataSize = dataSet.size()
    val data = dataSet.data(false)
    var count = 0
    var iteration = 0
    var resultSeq = Seq[Tensor[Float]]()
    while (count < dataSize) {
      model.evaluate()
      val miniBatch = data.next()
      val input = miniBatch.getInput()
      val result = predict(input)
      resultSeq = resultSeq :+ result.toTensor[Float]
      iteration += 1
      count += miniBatch.size()
    }
    resultSeq.toArray
  }

  override def predict(input: Activity): Activity = {
    val output = model.forward(input)
    val metadata = MetaData.newBuilder
      .setName(s"${model.getName()}_output").setVersion(iteration).build
    val tableProto = outputTargetToTableProto(model.output, null, metadata)
    val result = flClient.nnStub.predict(tableProto, algorithm)
    getTensor("predictOutput", result)
  }
}
