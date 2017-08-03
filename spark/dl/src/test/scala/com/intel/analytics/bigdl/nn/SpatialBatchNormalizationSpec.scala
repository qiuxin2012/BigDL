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

import com.intel.analytics.bigdl.tensor.{Storage, Tensor}
import com.intel.analytics.bigdl.utils.T
import org.scalatest.{FlatSpec, Matchers}

@com.intel.analytics.bigdl.tags.Parallel
class SpatialBatchNormalizationSpec extends FlatSpec with Matchers {
  "A SpatialBatchNormalization" should "generate correct output" in {

    val sbn = SpatialBatchNormalization[Double](3)
    sbn.weight(1) = 0.1
    sbn.weight(2) = 0.2
    sbn.weight(3) = 0.3

    sbn.bias(1) = 0.1
    sbn.bias(2) = 0.2
    sbn.bias(3) = 0.3
    val input = Tensor[Double](2, 3, 4, 4)

    var i = 0
    input.apply1(e => {
      i += 1; i
    })
    val output = sbn.forward(input).toTensor[Double]

    output.isSameSizeAs(input) should be (true)
  }

}
