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
import com.intel.analytics.bigdl.ppml.fl.algorithms.{PSI, VFLLinearRegression, VFLLogisticRegression}
import com.intel.analytics.bigdl.ppml.fl.{FLContext, FLServer, FLSpec}


class VFLCkksSpec extends FLSpec {
  "CKKS VFL LR" should "work" in {
    val secret = new CKKS().createSecrets()
    val flServer = new FLServer()
    flServer.setPort(port)
    flServer.setCkksAggregator(secret)
    flServer.build()
    flServer.start()


    FLContext.initFLContext("1", target)
    val psi = new PSI()
    val salt = psi.getSalt()
    val trainDf = psi.uploadSetAndDownloadIntersectionDataFrame(df, salt)
    val testDf = trainDf.drop("Outcome")
    trainDf.show()
    val lr = new VFLLogisticRegression(df.columns.size - 1)
    lr.fitDataFrame(trainDf, valData = trainDf)
    lr.evaluateDataFrame(trainDf)
    lr.predictDataFrame(testDf)
    flServer.stop()
  }
}
