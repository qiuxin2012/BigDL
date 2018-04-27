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
package com.intel.analytics.bigdl.nn.ops

import com.intel.analytics.bigdl.tensor.Tensor
import com.intel.analytics.bigdl.tensor.TensorNumericMath.TensorNumeric
import com.intel.analytics.bigdl.utils.Table

import scala.reflect.ClassTag

class Gather[T: ClassTag, D: ClassTag]()(implicit ev: TensorNumeric[T], ev2: TensorNumeric[D])
  extends Operation[Table, Tensor[D], T]{
  output = Tensor[D]()

  override def updateOutput(input: Table): Tensor[D] = {
    val inputTensor = input[Tensor[D]](1)
    val indices = input[Tensor[Int]](2)
    val inputSizes = inputTensor.size()

    if (indices.isScalar) {
      val index = indices.value()
      require(index < inputSizes(0),
        s"index should smaller than ${inputSizes(0)}, but got $index")
      val theOutput = inputTensor.select(1, index + 1)
      inputSizes(0) = 1
      this.output.resize(inputSizes).copy(theOutput)
    } else {
      val indicesSize = indices.size()
      val outputSizes = indicesSize ++ inputSizes.slice(1, inputSizes.length)

      output.resize(Array(indices.nElement()) ++ inputSizes.slice(1, inputSizes.length))
      indices.resize(indices.nElement())
      var i = 0
      while (i < indices.nElement()) {
        val index = indices.valueAt(i + 1)
        require(index < inputSizes(0),
          s"index should smaller than ${inputSizes(0)}, but got $index")
        output.select(1, i + 1).copy(inputTensor.select(1, index + 1))
        i += 1
      }

      indices.resize(indicesSize)
      output.resize(outputSizes)
    }

    output
  }

  override def getClassTagNumerics() : (Array[ClassTag[_]], Array[TensorNumeric[_]]) = {
    (Array[ClassTag[_]](scala.reflect.classTag[T], scala.reflect.classTag[D]),
      Array[TensorNumeric[_]](ev, ev2))
  }

}

object Gather {
  def apply[T: ClassTag, D: ClassTag]()(implicit ev: TensorNumeric[T], ev2: TensorNumeric[D]):
  Gather[T, D] = new Gather()

}