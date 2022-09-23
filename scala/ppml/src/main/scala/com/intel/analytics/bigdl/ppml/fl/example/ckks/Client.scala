package com.intel.analytics.bigdl.ppml.fl.example.ckks

import com.intel.analytics.bigdl.dllib.NNContext
import com.intel.analytics.bigdl.dllib.nn.SparseLinear
import com.intel.analytics.bigdl.dllib.utils.{Log4Error, RandomGenerator}
import com.intel.analytics.bigdl.ppml.fl.{FLContext, NNModel}
import com.intel.analytics.bigdl.ppml.fl.algorithms.{VFLLogisticRegression, VFLLogisticRegressionCkks}
import com.intel.analytics.bigdl.ppml.fl.utils.FlContextForTest
import org.apache.spark.sql.SparkSession

class Client(trainDataPath: String,
             testDataPath: String,
             clientId: Int,
             appName: String) extends Thread {
  override def run(): Unit = {
    FLContext.initFLContext(clientId.toString)
    val sqlContext = SparkSession.builder().getOrCreate()
    val pre = new DataPreprocessing(sqlContext, trainDataPath, testDataPath, clientId)
    val (trainDataset, validationDataset) = pre.loadCensusData()

    val numFeature = 3049

    RandomGenerator.RNG.setSeed(2L)
    val linear = SparseLinear[Float](numFeature, 1)

    val lr: NNModel = appName match {
      case "dllib" => new VFLLogisticRegression(learningRate = 0.001f, customModel = linear)
      case "ckks" => new VFLLogisticRegressionCkks(learningRate = 0.001f, customModel = linear)
      case _ => throw new Error()
    }

    val epochNum = 20
    var accTime: Long = 0
    (0 until epochNum).foreach { epoch =>
      trainDataset.shuffle()
      val trainData = trainDataset.toLocal().data(false)
      while (trainData.hasNext) {
        val miniBatch = trainData.next()
        val input = miniBatch.getInput()
        val currentBs = input.toTensor[Float].size(1)
        val target = miniBatch.getTarget()
        val dllibStart = System.nanoTime()
        lr.trainStep(input, target)
        accTime += System.nanoTime() - dllibStart
      }
      println(s"$appName Time: " + accTime / 1e9)
    }

    linear.evaluate()
    val evalData = validationDataset.toLocal().data(false)
    var accDllib = 0
    while (evalData.hasNext) {
      val miniBatch = evalData.next()
      val input = miniBatch.getInput()
      val currentBs = input.toTensor[Float].size(1)
      val target = miniBatch.getTarget().toTensor[Float]
      val predict = lr.predictStep(input)
      println(s"Predicting $appName")
      (0 until currentBs).foreach { i =>
        val dllibPre = predict.toTensor[Float].valueAt(i)
        val t = target.valueAt(i + 1, 1)
        if (t == 0) {
          if (dllibPre <= 0.5) {
            accDllib += 1
          }
        } else {
          if (dllibPre > 0.5) {
            accDllib += 1
          }
        }
        //        println(t + " " + dllibPre + " " + ckksPre)
      }
    }
    println(s"$appName predict correct: $accDllib")

  }
}
