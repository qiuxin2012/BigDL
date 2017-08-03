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

package com.intel.analytics.bigdl.nn

import com.intel.analytics.bigdl.Module
import com.intel.analytics.bigdl.nn.abstractnn.{Initializable, TensorModule}
import com.intel.analytics.bigdl.tensor.TensorNumericMath.TensorNumeric
import com.intel.analytics.bigdl.tensor.{DoubleType, FloatType, Tensor}
import com.intel.analytics.bigdl.utils.{Engine, T, Table}
import com.intel.analytics.bigdl.utils.RandomGenerator._

import scala.concurrent.Future
import scala.reflect.ClassTag

/**
 * This layer implements Batch Normalization as described in the paper:
 * "Batch Normalization: Accelerating Deep Network Training by Reducing Internal Covariate Shift"
 * by Sergey Ioffe, Christian Szegedy https://arxiv.org/abs/1502.03167
 *
 * This implementation is useful for inputs NOT coming from convolution layers.
 * For convolution layers, use nn.SpatialBatchNormalization.
 *
 * The operation implemented is:
 *             ( x - mean(x) )
 *     y =  -------------------- * gamma + beta
 *             standard-deviation(x)
 * where gamma and beta are learnable parameters.The learning of gamma and beta is optional.
 * @param nOutput output feature map number
 * @param eps avoid divide zero
 * @param momentum momentum for weight update
 * @param affine affine operation on output or not
 * @param ev numeric operator
 * @tparam T numeric type
 */
@SerialVersionUID(- 3181824540272906068L)
class BatchNormalization[@specialized(Float, Double) T: ClassTag](
  val nOutput: Int, // output feature map number
  val eps: Double = 1e-5, // avoid divde zero
  val momentum: Double = 0.1, // momentum for weight update
  val affine: Boolean = true, // affine operation on output or not
  initWeight: Tensor[T] = null,
  initBias: Tensor[T] = null,
  initGradWeight: Tensor[T] = null,
  initGradBias: Tensor[T] = null
)(implicit ev: TensorNumeric[T]) extends TensorModule[T] with Initializable {

  require(nOutput > 0)

  val nDim = 2
  val runningMean = if (affine) Tensor[T](nOutput) else Tensor[T]()
  val runningVar = if (affine) Tensor[T](nOutput).fill(ev.fromType[Int](1)) else Tensor[T]()
  val saveMean = if (affine) Tensor[T](nOutput) else Tensor[T]()
  val saveStd = if (affine) Tensor[T](nOutput).fill(ev.fromType[Int](1)) else Tensor[T]()
  val saveGradSum = if (affine) Tensor[T](nOutput) else Tensor[T]()
  val saveDotp = if (affine) Tensor[T](nOutput) else Tensor[T]()

  val weight: Tensor[T] =
    if (initWeight != null) initWeight else if (affine) Tensor[T](nOutput) else null
  val bias: Tensor[T] =
    if (initBias != null) initBias else if (affine) Tensor[T](nOutput) else null

  val gradWeight: Tensor[T] =
    if (initGradWeight != null) initGradWeight else if (affine) Tensor[T](nOutput) else null
  val gradBias: Tensor[T] =
    if (initGradBias != null) initGradBias else if (affine) Tensor[T](nOutput) else null

  @transient
  private var results : Array[Future[_]] = null
  @transient
  // BatchNormalization has internal parameters (saveMean, saveStd)
  // that changes at every forward, so a standard gradcheck won't work with this module.
  // if you want to do a gradcheck, you will need to fix those variables, otherwise not fix.
  private var needFix: Boolean = false

  {
    val wInit = RandomUniform(0, 1)
    val bInit = Zeros
    setInitMethod(wInit, bInit)
  }

  override def reset(): Unit = {
    if (null != weight && initWeight == null) {
      weightInitMethod.init(weight, VariableFormat.ONE_D)
    }

    if (null != bias && initBias == null) {
      biasInitMethod.init(bias, VariableFormat.ONE_D)
    }

    zeroGradParameters()
  }

  @inline
  // to fix internal parameters (saveMean, saveStd)
  def setInit(status: Boolean = true): this.type = {
    needFix = status
    this
  }

  @inline
  private def checkInputDim(input: Tensor[T]): Unit = {
    require(input.dim() == nDim || (input.dim() == nDim - 1 && train == false),
      s"only mini-batch supported (${nDim}D tensor), got ${input.dim()}D tensor instead")
  }

  @inline
  private def makeBatch(input: Tensor[T]): Tensor[T] = {
    if (input.dim() == nDim - 1 && train == false) {
      input.addSingletonDimension()
    } else {
      input
    }
  }

  @inline
  private def initializeBuffer(nOutput: Int): Unit = {
    runningMean.resize(nOutput).zero
    runningVar.resize(nOutput).fill(ev.fromType[Int](1))
  }

  var inputBuffer = Tensor()

  override def updateOutput(input: Tensor[T]): Tensor[T] = {
    checkInputDim(input)

    output.resizeAs(input).copy(input)

    inputBuffer.resizeAs(input).copy(input)
    val _input = makeBatch(inputBuffer)
    val nInput = _input.size(2)

    if (runningMean.nElement == 0 || runningMean.nElement < nInput) {
      initializeBuffer(nInput)
    }

    saveMean.resizeAs(runningMean).zero
    saveStd.resizeAs(runningVar).fill(ev.fromType[Int](1))

    if (results == null || results.length > nInput) {
      results = new Array[Future[_]](nInput)
    }
    val n = _input.nElement() / nInput

    if (train) {
      gradInput.resizeAs(_input)
    }

    val spatialBatchSize = if (nDim == 2) 1 else _input.size(1)
    val inputFloat = _input
    val inputData = inputFloat.storage().array()
    val inputOffset = inputFloat.storageOffset() - 1
    val inputStride = if (nDim == 2) _input.stride(1) else _input.stride(4)
    val inputStride2 = _input.stride(2)
    val outputFloat = output
    val outputData = outputFloat.storage().array()
    val outputOffset = outputFloat.storageOffset() - 1
    val outputStride = output.stride(1)
    updateOutputFrame(inputData, inputOffset, inputStride, outputData, outputOffset,
      outputStride, nInput, n, inputStride2, spatialBatchSize)

    output
  }

  var ones = Tensor()

  private def updateOutputFrame(
      input: Array[T], inputOffset: Int, inputStride: Int,
      output: Array[T], outputOffset: Int, outputStride: Int,
      nInput: Int, n: Int, inputStride2: Int, spatialBatchSize: Int): Unit = {
    val frameSize = if (nDim == 2) n else n / spatialBatchSize

    if (train) {
      var f = 1
      while (f <= nInput) {
        if (ones.nElement() != frameSize) {
          ones.resize(frameSize)
          ones.fill(ev.one)
        }
        val onesArray = ones.storage().array()
        var sum = ev.zero
        var i = 0
        while (i < spatialBatchSize) {
          sum = ev.plus(sum, ev.dot(frameSize, input, inputOffset + (f - 1 + i * nInput)
            * inputStride2, inputStride, onesArray, ones.storageOffset() - 1, ones.stride(1)))
          i += 1
        }
        val mean = ev.divide(sum, ev.fromType(n))
        saveMean.setValue(f, mean)

        i = 0
        while (i < spatialBatchSize) {
          ev.sub(frameSize, input, inputOffset + (f - 1 + i * nInput)
            * inputStride2, mean, inputStride)
          i += 1
        }

        f += 1
      }

      ev.arraycopy(input, 0, gradInput.storage().array(), 0, input.length)
      ev.vPowx(input.length, input, 0, ev.fromType(2), input, 0)

      f = 1
      while (f <= nInput) {
        val mean = saveMean.valueAt(f)
        var sum = ev.zero
        var i = 0
        while (i < spatialBatchSize) {
          sum = ev.plus(sum, ev.dot(frameSize, input,
            inputOffset + (f - 1 + i * nInput) * inputStride2,
            inputStride, ones.storage.array, ones.storageOffset() - 1, ones.stride(1)))
          i += 1
        }

        val invstd = ev.fromType(
          if (sum == 0 && eps == 0.0) {
            0.0
          } else {
            1.0 / Math.sqrt(ev.toType[Double](sum) / n + eps).toFloat
          }
        )
        saveStd.setValue(f, invstd)

        runningMean.setValue(f, ev.fromType(momentum * ev.toType[Double](mean) + (1 - momentum) *
          ev.toType[Double](runningMean.valueAt(f))))

        val unbiasedVar = ev.toType[Double](sum) / (n - 1)
        runningVar.setValue(f, ev.fromType[Double](momentum * unbiasedVar + (1 - momentum) *
          ev.toType[Double](runningVar.storage().array()(f - 1))))
        f += 1
      }
    }

    if (needFix) {
      saveMean.zero().fill(ev.zero)
      saveStd.zero().fill(ev.fromType(0.0001))
    }

    // update output
    var f = 1
    while (f <= nInput) {
      val (mean, invstd) = if (train) {
        (saveMean.valueAt(f), saveStd.valueAt(f))
      } else {
        (runningMean.valueAt(f),
          ev.fromType(1 / Math.sqrt(ev.toType[Double](runningVar.valueAt(f)) + eps).toFloat))
      }

      val w = if (null != weight) weight.valueAt(f) else ev.one
      val b = if (null != bias) bias.valueAt(f) else ev.zero

      var i = 0
      while (i < spatialBatchSize) {
        ev.scal(frameSize, ev.times(w, invstd), output,
          inputOffset + (f - 1 + i * nInput) * inputStride2, inputStride)
        ev.add(frameSize, output, inputOffset + (f - 1 + i * nInput) * inputStride2,
          ev.minus(b, ev.times(mean, ev.times(invstd, w))), inputStride)
        i += 1
      }


      f += 1
    }
  }

  override def updateGradInput(input: Tensor[T], gradOutput: Tensor[T]): Tensor[T] = {
    backward(input, gradOutput, gradInput, null, null)
  }

  override def accGradParameters(input: Tensor[T], gradOutput: Tensor[T]): Unit = {
    backward(input, gradOutput, null, gradWeight, gradBias)
  }

  override def backward(input: Tensor[T], gradOutput: Tensor[T]): Tensor[T] = {
    checkInputDim(input)
    checkInputDim(gradOutput)
    val before = System.nanoTime()
    val result = backward(input, gradOutput, gradInput, gradWeight, gradBias)
    backwardTime += System.nanoTime() - before
    result
  }

  def backward(input: Tensor[T], gradOutput: Tensor[T],
    theGradInput: Tensor[T] = null, theGradWeight: Tensor[T] = null,
    theGradBias: Tensor[T] = null): Tensor[T] = {
    require(train, "should be in training mode when this.train is true")
    require(null != saveMean && null != saveStd, "must call updateOutput() first")

    if (null != theGradInput) {
      theGradInput.resizeAs(gradOutput)
    }

    val nInput = input.size(2)
    if (results == null || results.length > nInput) {
      results = new Array[Future[_]](nInput)
    }
    val n = input.nElement() / nInput
    val spatialBatchSize = if (nDim == 2) 1 else inputBuffer.size(1)

    val gradWeightData = gradWeight.storage().array()
    val gradWeightOffset = gradWeight.storageOffset() - 1
    val gradBiasData = gradBias.storage().array()
    val gradBiasOffset = gradBias.storageOffset() - 1
    val gradInputData = gradInput.storage().array()
    val gradInputOffset = gradInput.storageOffset() - 1
    val gradInputStride = if (nDim == 2) gradInput.stride(1) else gradInput.stride(4)
    val gradInputStride2 = gradInput.stride(2)
    val inputData = inputBuffer.storage().array()
    val inputOffset = inputBuffer.storageOffset() - 1
    val inputStride = if (nDim == 2) inputBuffer.stride(1) else inputBuffer.stride(4)
    val inputStride2 = inputBuffer.stride(2)
    val gradOutputData = gradOutput.storage().array()
    val gradOutputOffset = gradOutput.storageOffset() - 1
    val gradOutputStride = if (nDim == 2) gradOutput.stride(1) else gradOutput.stride(4)
    val gradOutputStride2 = gradOutput.stride(2)
    if (affine) {
      if (theGradInput != null) {
        if (theGradWeight != null && theGradBias != null) {
          backwardFrame(gradInputData, gradInputOffset, gradInputStride, gradInputStride2,
            gradOutputData, gradOutputOffset, gradOutputStride, gradOutputStride2,
            gradInputData, gradInputOffset, gradInputStride, gradInputStride2, nInput, n,
            scaleW, scaleB,
            gradWeightData, gradWeightOffset, gradBiasData,
            gradBiasOffset, spatialBatchSize)
        } else {
          backwardFrame(inputData, inputOffset, inputStride, inputStride2, gradOutputData,
            gradOutputOffset, gradOutputStride, gradOutputStride2,
            gradInputData, gradInputOffset, gradInputStride, gradInputStride2, nInput, n,
            scaleW, scaleB, null, 0, null, 0, spatialBatchSize)
        }
      } else {
        backwardFrame(inputData, inputOffset, inputStride, inputStride2, gradOutputData,
          gradOutputOffset, gradOutputStride, gradOutputStride2,
          null, 0, 0, 0, nInput, n, scaleW, scaleB,
          gradWeightData, gradWeightOffset,
          gradBiasData, gradBiasOffset, spatialBatchSize)
      }
    } else if (null != theGradInput) {
      backwardFrame(inputData, inputOffset, inputStride, inputStride2, gradOutputData,
        gradOutputOffset, gradOutputStride, gradOutputStride2,
        gradInputData, gradInputOffset, gradInputStride, gradInputStride2, nInput, n,
        scaleW, scaleB, null, 0, null, 0, spatialBatchSize)
    }

    gradInput
  }

  private def backwardFrame(
        input: Array[T], inputOffset: Int, inputStride: Int,
        inputStride2: Int, gradOutput: Array[T], gradOutputOffset: Int, gradOutputStride: Int,
        gradOutputStride2: Int, gradInput: Array[T], gradInputOffset: Int, gradInputStride: Int,
        gradInputStride2: Int, nInput: Int, n: Int, scaleW: Double, scaleB: Double,
        gradWeight: Array[T], gradWeightOffset: Int,
        gradBias: Array[T], gradBiasOffset: Int,
        spatialBatchSize: Int): Unit = {
    if (!train) {
      ev.arraycopy(gradOutput, 0, gradInput, 0, gradOutput.length)
    }
    val frameSize = if (nDim == 2) n else n / spatialBatchSize

    var f = 1
    while (f <= nInput) {
      val w = if (null != weight) weight.valueAt(f) else ev.one
      val (mean, invstd) = if (train) {
        (saveMean.valueAt(f), saveStd.valueAt(f))
      } else {
        (runningMean.valueAt(f), ev.fromType(
          1 / Math.sqrt(ev.toType[Double](runningVar.valueAt(f)) + eps)))
      }

      // sum gradOutput
      var sum = ev.zero
      var i = 0
      while (i < spatialBatchSize) {
        sum = ev.plus(sum, ev.dot(frameSize, gradOutput,
          gradOutputOffset + (f - 1 + i * nInput) * gradOutputStride2, gradOutputStride,
          ones.storage().array(), ones.storageOffset() - 1, ones.stride(1)))
        i += 1
      }
      saveGradSum.setValue(f, sum)

      // dot product of the Q(X) and gradOutput
      var dotp = ev.zero
      i = 0
      while (i < spatialBatchSize) {
        dotp = ev.plus(dotp, ev.dot(frameSize, input, inputOffset + (f - 1 + i * nInput)
          * inputStride2, inputStride, gradOutput, gradOutputOffset + (f - 1 + i * nInput)
          * gradOutputStride2, gradOutputStride))
        i += 1
      }
      saveDotp.setValue(f, dotp)

      if (null != gradInput) {
        if (train) {
          val k = ev.divide(ev.times(dotp, ev.times(invstd, invstd)), ev.fromType(n))

          i = 0
          while (i < spatialBatchSize) {
            ev.scal(frameSize, ev.negative(k), gradInput, gradInputOffset + (f - 1 + i * nInput)
              * gradInputStride2, gradInputStride)
            i += 1
          }

          val gradMean = ev.divide(sum, ev.fromType(n))
          i = 0
          while (i < spatialBatchSize) {
            ev.sub(frameSize, gradInput, gradInputOffset + (f - 1 + i * nInput) * gradInputStride2,
              gradMean, gradInputStride)
            i += 1
          }
        } else {
          var i = 0
          while (i < spatialBatchSize) {
            ev.scal(frameSize, ev.times(invstd, w), gradInput,
              gradInputOffset + (f - 1 + i * nInput) * gradInputStride2, gradInputStride)
            i += 1
          }
        }
      }

      if (null != gradWeight && scaleW != 0) {
        gradWeight(f - 1 + gradWeightOffset) = ev.plus(gradWeight(f - 1 + gradWeightOffset),
          ev.times(ev.fromType(scaleW), ev.times(dotp, invstd)))
      }

      if (null != gradBias && scaleB != 0) {
        gradBias(f - 1 + gradBiasOffset) = ev.plus(gradBias(f - 1 + gradBiasOffset),
          ev.times(ev.fromType(scaleB), sum))
      }
      f += 1
    }

    if (train) {
      if (null != gradInput) {
        ev.vAdd(gradInput.length, gradInput, gradInputOffset, gradOutput, gradOutputOffset,
          gradInput, gradInputOffset)
        var f = 1
        while (f <= nInput) {
          val w = if (null != weight) weight.valueAt(f) else ev.one
          val invstd = saveStd.valueAt(f)
          var i = 0
          while (i < spatialBatchSize) {
            ev.scal(frameSize, ev.times(invstd, w), gradInput,
              gradInputOffset + (f - 1 + i * nInput) * gradInputStride2, gradInputStride)
            i += 1
          }
          f += 1
        }
      }
    }

  }

  override def copyStatus(src: Module[T]): this.type = {
    require(canEqual(src), s"copyStatus: type mismatch, $src is different from $this")
    val srcModule = src.asInstanceOf[BatchNormalization[T]]
    runningMean.copy(srcModule.runningMean)
    runningVar.copy(srcModule.runningVar)
    this
  }

  override def zeroGradParameters(): Unit = {
    if (affine) {
      gradWeight.zero()
      gradBias.zero()
    }
  }

  override def parameters(): (Array[Tensor[T]], Array[Tensor[T]]) = {
    if (affine) {
      (Array(this.weight, this.bias), Array(this.gradWeight, this.gradBias))
    } else {
      null
    }
  }

  override def getParametersTable(): Table = {
    if (affine) {
      T(getName() -> T("weight" -> weight, "bias" -> bias,
        "gradWeight" -> gradWeight, "gradBias" -> gradBias,
        "runningMean" -> runningMean, "runningVar" -> runningVar))
    } else {
      T(getName() -> T("runningMean" -> runningMean, "runningVar" -> runningVar))
    }
  }

  override def toString(): String = {
    s"nn.BatchNormalization($nOutput, $eps, $momentum, $affine)"
  }

  override def canEqual(other: Any): Boolean = other.isInstanceOf[BatchNormalization[T]]

  override def equals(other: Any): Boolean = other match {
    case that: BatchNormalization[T] =>
      super.equals(that) &&
        (that canEqual this) &&
        nDim == that.nDim &&
        runningMean == that.runningMean &&
        runningVar == that.runningVar &&
        weight == that.weight &&
        bias == that.bias &&
        nOutput == that.nOutput &&
        eps == that.eps &&
        momentum == that.momentum &&
        affine == that.affine
    case _ => false
  }

  override def hashCode(): Int = {
    val state = Seq(super.hashCode(), nDim, runningMean, runningVar, weight, bias,
      nOutput, eps, momentum, affine)
    state.map(_.hashCode()).foldLeft(0)((a, b) => 31 * a + b)
  }
}

object BatchNormalization {
  def apply[@specialized(Float, Double) T: ClassTag](
    nOutput: Int,
    eps: Double = 1e-5,
    momentum: Double = 0.1,
    affine: Boolean = true,
    initWeight: Tensor[T] = null,
    initBias: Tensor[T] = null,
    initGradWeight: Tensor[T] = null,
    initGradBias: Tensor[T] = null)(implicit ev: TensorNumeric[T]): BatchNormalization[T] = {
    new BatchNormalization[T](
      nOutput, eps, momentum, affine, initWeight, initBias, initGradWeight, initGradBias)
  }
  def apply[@specialized(Float, Double) T: ClassTag](
    affine: Option[Int])(implicit ev: TensorNumeric[T]): BatchNormalization[T] = {
    new BatchNormalization[T](nOutput = affine.getOrElse(1), affine = affine.isDefined)
  }
}
