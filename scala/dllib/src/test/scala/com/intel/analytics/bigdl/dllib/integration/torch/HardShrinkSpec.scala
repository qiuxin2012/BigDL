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
package com.intel.analytics.bigdl.dllib.integration.torch

import com.intel.analytics.bigdl.dllib.nn.HardShrink
import com.intel.analytics.bigdl.dllib.tensor.Tensor
import com.intel.analytics.bigdl.dllib.utils.RandomGenerator

@com.intel.analytics.bigdl.tags.Serial
class HardShrinkSpec extends TorchSpec {
    def randomn(): Double = RandomGenerator.RNG.normal(-10, 10)

  "An HardShrink" should "generate correct output and grad" in {
    torchCheck()
    val layer = HardShrink[Double](5)
    val input = Tensor[Double](2, 2, 2)
    input.apply1(x => randomn())
    val gradOutput = Tensor[Double](2, 2, 2)
    gradOutput.apply1(x => randomn())

    val start = System.nanoTime()
    val output = layer.forward(input)
    val gradInput = layer.backward(input, gradOutput)
    val end = System.nanoTime()
    val scalaTime = end - start

    val code = "module = nn.HardShrink(5)\n" +
      "output = module:forward(input)\n" +
      "gradInput = module:backward(input,gradOutput)"

    val (luaTime, torchResult) = TH.run(code, Map("input" -> input, "gradOutput" -> gradOutput),
      Array("output", "gradInput"))
    val luaOutput = torchResult("output").asInstanceOf[Tensor[Double]]
    val luaGradInput = torchResult("gradInput").asInstanceOf[Tensor[Double]]

    output should be (luaOutput)
    gradInput should be (luaGradInput)

    println("Test case : HardShrink, Torch : " + luaTime + " s, Scala : " + scalaTime / 1e9 + " s")
  }
}
