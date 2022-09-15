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


import com.intel.analytics.bigdl.dllib.nn.ckks.{CAddTable, FusedBCECriterion}
import com.intel.analytics.bigdl.dllib.optim.{OptimMethod, ValidationMethod, ValidationResult}
import com.intel.analytics.bigdl.dllib.tensor.Tensor
import com.intel.analytics.bigdl.dllib.utils.T
import com.intel.analytics.bigdl.ppml.fl.common.FLPhase
import com.intel.analytics.bigdl.ppml.fl.generated.FlBaseProto._
import com.intel.analytics.bigdl.ppml.fl.utils.ProtoUtils
import com.intel.analytics.bigdl.ppml.fl.utils.ProtoUtils.toFloatTensor
import com.intel.analytics.bigdl.{Criterion, Module}
import org.apache.logging.log4j.LogManager


/**
 *
 * @param model
 * @param optimMethod
 * @param criterion loss function, HFL takes loss at estimator, VFL takes loss at aggregator
 * @param validationMethods
 */
class VFLNNAggregatorCkks(optimMethod: OptimMethod[Float],
                          validationMethods: Array[ValidationMethod[Float]]) extends NNAggregator{
  val m1 = CAddTable(1)
  val criterion = FusedBCECriterion(1)


  var validationResult = List[Array[ValidationResult]]()

  /**
   * Aggregate the clients data to update server data by aggType
   * @param flPhase FLPhase enum type, one of TRAIN, EVAL, PREDICT
   */
  override def aggregate(flPhase: FLPhase): Unit = {
    val storage = getStorage(flPhase)
    val (inputTable, target) = ProtoUtils.ckksProtoToBytes(storage)

    val output = m1.updateOutput(inputTable)

    val metaBuilder = MetaData.newBuilder()
    var aggregatedTable: TensorMap = null
    flPhase match {
      case FLPhase.TRAIN =>
        val loss = criterion.forward(output, target)
        val grad = criterion.backward(output, target)
        val meta = metaBuilder.setName("gradInput").setVersion(storage.version).build()
        // Pass byte back to clients
        aggregatedTable = TensorMap.newBuilder()
          .setMetaData(meta)
          .putTensorMap("gradInput", toFloatTensor(grad.toTable.apply[Tensor[Float]](1)))
          .putTensorMap("loss", toFloatTensor(Tensor[Float](T(loss))))
          .build()

      case FLPhase.EVAL =>
        val batchValidationResult = validationMethods.map(vMethod => {
          vMethod.apply(output, target)
        })
        validationResult = validationResult :+ batchValidationResult
        if (shouldReturn) {
          val result = validationResult.reduce((x, y) => {
            x.zip(y).map {
              case (r1, r2) => r1 + r2
            }
          })
          setReturnMessage(result.toString)
        }
        val meta = metaBuilder.setName("evaluateResult").setVersion(storage.version).build()
        aggregatedTable = TensorMap.newBuilder()
          .setMetaData(meta)
          .build()

      case FLPhase.PREDICT =>
        val meta = metaBuilder.setName("predictResult").setVersion(storage.version).build()
        aggregatedTable = TensorMap.newBuilder()
          .setMetaData(meta)
          .putTensorMap("predictOutput", toFloatTensor(output.toTensor[Float]))
          .build()
    }
    storage.clearClientAndUpdateServer(aggregatedTable)
  }

}

object VFLNNAggregator {
  val logger = LogManager.getLogger(this.getClass)

  def apply(clientNum: Int,
            classifier: Module[Float],
            optimMethod: OptimMethod[Float],
            criterion: Criterion[Float]): VFLNNAggregator = {
    val vflNNAggregator = new VFLNNAggregator(classifier, optimMethod, criterion, null)
    vflNNAggregator.setClientNum(clientNum)
    vflNNAggregator
  }

  def apply(clientNum: Int,
            classifier: Module[Float],
            optimMethod: OptimMethod[Float],
            criterion: Criterion[Float],
            validationMethods: Array[ValidationMethod[Float]]): VFLNNAggregator = {
    val vflNNAggregator = new VFLNNAggregator(
      classifier, optimMethod, criterion, validationMethods)
    vflNNAggregator.setClientNum(clientNum)
    vflNNAggregator
  }
}
