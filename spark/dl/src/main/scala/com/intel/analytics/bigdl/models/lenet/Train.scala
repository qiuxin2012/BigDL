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

package com.intel.analytics.bigdl.models.lenet

import com.intel.analytics.bigdl._
import com.intel.analytics.bigdl.dataset.{DataSet, Sample, SampleToMiniBatch}
import com.intel.analytics.bigdl.dataset.image.{BytesToGreyImg, GreyImgNormalizer, GreyImgToBatch}
import com.intel.analytics.bigdl.nn.{Module => _, _}
import com.intel.analytics.bigdl.numeric.NumericFloat
import com.intel.analytics.bigdl.optim._
import com.intel.analytics.bigdl.tensor.Tensor
import com.intel.analytics.bigdl.utils.{Engine, LoggerFilter, T, Table}
import org.apache.log4j.{Level, Logger}
import org.apache.spark.SparkContext

object Train {
  LoggerFilter.redirectSparkInfoLogs()


  import Utils._

  def testLargeModel(): Unit = {
    Logger.getLogger("org").setLevel(Level.WARN)
    val conf = Engine.createSparkConf()
      .setAppName("testLargeModel")
    val sc = new SparkContext(conf)
    Engine.init

    val data = sc.parallelize(Array.tabulate(100)(_ => (Seq(2.0f), Seq(1.0f))))
    val samples = data.map { case (feature, label) =>
      Sample(Tensor(feature.toArray, Array(1)),
        Tensor(label.toArray, Array(1)))
    }

    val batches = DataSet.rdd(samples) -> SampleToMiniBatch(20)
    val model = Sequential[Float]().add(Select(2, 1))
      .add(LookupTable[Float](1000000, 600))
      .add(Linear[Float](600, 1))
    val criterion = MSECriterion[Float]()
    val optimizer = Optimizer(model, batches, criterion)
      .setEndWhen(Trigger.maxEpoch(2))
    val optimizedModel = optimizer.optimize()

    optimizedModel.getParameters()
  }

  def main(args: Array[String]): Unit = {
    testLargeModel()
//    trainParser.parse(args, new TrainParams()).map(param => {
//      val conf = Engine.createSparkConf()
//        .setAppName("Train Lenet on MNIST")
//        .set("spark.task.maxFailures", "1")
//      val sc = new SparkContext(conf)
//      Engine.init
//
//      val trainData = param.folder + "/train-images-idx3-ubyte"
//      val trainLabel = param.folder + "/train-labels-idx1-ubyte"
//      val validationData = param.folder + "/t10k-images-idx3-ubyte"
//      val validationLabel = param.folder + "/t10k-labels-idx1-ubyte"
//
//      val model = if (param.modelSnapshot.isDefined) {
//        Module.load[Float](param.modelSnapshot.get)
//      } else {
//        if (param.graphModel) LeNet5.graph(classNum = 10) else LeNet5(classNum = 10)
//      }
//
//      val optimMethod = if (param.stateSnapshot.isDefined) {
//        OptimMethod.load[Float](param.stateSnapshot.get)
//      } else {
//        new SGD[Float](learningRate = param.learningRate,
//          learningRateDecay = param.learningRateDecay)
//      }
//
//      val trainSet = DataSet.array(load(trainData, trainLabel), sc) ->
//        BytesToGreyImg(28, 28) -> GreyImgNormalizer(trainMean, trainStd) -> GreyImgToBatch(
//        param.batchSize)
//
//      val optimizer = Optimizer(
//        model = model,
//        dataset = trainSet,
//        criterion = ClassNLLCriterion[Float]())
//      if (param.checkpoint.isDefined) {
//        optimizer.setCheckpoint(param.checkpoint.get, Trigger.everyEpoch)
//      }
//      if(param.overWriteCheckpoint) {
//        optimizer.overWriteCheckpoint()
//      }
//
//      val validationSet = DataSet.array(load(validationData, validationLabel), sc) ->
//        BytesToGreyImg(28, 28) -> GreyImgNormalizer(testMean, testStd) -> GreyImgToBatch(
//        param.batchSize)
//
//      optimizer
//        .setValidation(
//          trigger = Trigger.everyEpoch,
//          dataset = validationSet,
//          vMethods = Array(new Top1Accuracy, new Top5Accuracy[Float], new Loss[Float]))
//        .setOptimMethod(optimMethod)
//        .setEndWhen(Trigger.maxEpoch(param.maxEpoch))
//        .optimize()
//
//      sc.stop()
//    })
  }
}
