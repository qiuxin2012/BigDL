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

package com.intel.analytics.bigdl.dataset.image

import com.intel.analytics.bigdl.dataset.Transformer
import com.intel.analytics.bigdl.tensor.Tensor

/**
 * Scale BGRImg with Bicubic interpolation.
 * @param size size
 */
class BGRImgScaler(size: Int)
  extends Transformer[LabeledBGRImage, LabeledBGRImage] {

  private val tmp = Tensor[Float]()

  override def apply(prev: Iterator[LabeledBGRImage]): Iterator[LabeledBGRImage] = {
    prev.map(src => {
      bicubicScale(src)
    })
  }

  def bicubicScale(src: LabeledBGRImage): LabeledBGRImage = {
    val src_height = src.height()
    val src_width = src.width()
    if ((src_width <= src_height && src_width == size) ||
      (src_height <= src_width && src_height == size)) {
      src
    } else {
      val (scaleWidth, scaleHeight) = if (src_width < src_height) {
        (size, Math.floor(src_height.toFloat / src_width * size).toInt)
      } else {
        (Math.floor(src_width.toFloat / src_height * size).toInt, size)
      }
      val dst = new LabeledBGRImage(scaleWidth, scaleHeight)
      val dst_stride0 = BGRImgScaler.stride(dst, 0)
      val dst_stride1 = BGRImgScaler.stride(dst, 1)
      val dst_stride2 = BGRImgScaler.stride(dst, 2)
      val src_stride0 = BGRImgScaler.stride(src, 0)
      val src_stride1 = BGRImgScaler.stride(src, 1)
      val src_stride2 = BGRImgScaler.stride(src, 2)
      val dst_width = scaleWidth
      val dst_height = scaleHeight
      tmp.resize(src_height, dst_width).zero()
      val tmp_width = dst_width
      val tmp_height = src_height
      val tmp_stride0 = BGRImgScaler.stride(tmp, 0)
      val tmp_stride1 = BGRImgScaler.stride(tmp, 1)
      val tmp_stride2 = BGRImgScaler.stride(tmp, 2)

      var k = 0
      while (k < 3) {
        /* compress/expand rows first */
        var j = 0
        while (j < src_height) {
          BGRImgScaler.scaleCubicRowcol(src.content,
            tmp.storage().array(),
            0 * src_stride2 + j * src_stride1 + k * src_stride0,
            0 * tmp_stride2 + j * tmp_stride1 + k * tmp_stride0,
            src_stride2,
            tmp_stride2,
            src_width,
            tmp_width)
          j += 1
        }

        /* then columns */
        var i = 0
        while (i < dst_width) {
          BGRImgScaler.scaleCubicRowcol(tmp.storage().array(),
            dst.content,
            i * tmp_stride2 + 0 * tmp_stride1 + k * tmp_stride0,
            i * dst_stride2 + 0 * dst_stride1 + k * dst_stride0,
            tmp_stride1,
            dst_stride1,
            tmp_height,
            dst_height)
          i += 1
        }

        k += 1
      }

      dst
    }

  }
}

object BGRImgScaler {
  def apply(size: Int): BGRImgScaler = {
    new BGRImgScaler(size)
  }


  def stride(t: Tensor[Float], i: Int): Int = {
    if (t.nDimension == 2) {
      if (i == 0) {
        0
      } else {
        t.stride(i)
      }
    } else {
      t.stride(i + 1)
    }
  }

  def stride(t: LabeledBGRImage, i: Int): Int = {
    if (i == 0) {
      1
    } else if (i == 1) {
      t.width() * 3
    } else {
      3
    }

  }

  def scaleCubicRowcol(
        src: Array[Float],
        dst: Array[Float],
        srcStart: Int,
        dstStart: Int,
        srcStride: Int,
        dstStride: Int,
        srcLength: Int,
        dstLength: Int) {
    if (dstLength == srcLength ) {
      var i = 0
      while (i < dstLength) {
        dst( dstStart + i*dstStride ) = src( srcStart + i*srcStride )
        i += 1
      }
    } else if ( srcLength == 1 ) {
      var i = 0
      while (i < dstLength - 1) {
        val dst_pos = dstStart + i*dstStride
        dst(dst_pos) = src( srcStart )
        i += 1
      }
    } else {
      val scale = if (dstLength == 1) {
        srcLength.toFloat - 1
      } else {
        (srcLength - 1).toFloat / (dstLength - 1)
      }

      var di = 0
      while(di < dstLength - 1) {
        val dst_pos = dstStart + di*dstStride
        var si_f = di * scale
        val si_i = si_f.toInt
        si_f -= si_i

        val p1 = src( srcStart + si_i * srcStride )
        val p2 = src( srcStart + (si_i + 1) * srcStride )
        val p0 = if (si_i > 0) {
          src( srcStart + (si_i - 1) * srcStride )
        } else {
          2 * p1 - p2
        }
        val p3 = if (si_i + 2 < srcLength) {
          src( srcStart + (si_i + 2) * srcStride )
        } else {
          2 * p2 - p1
        }

        val value = cubicInterpolate(p0, p1, p2, p3, si_f)
        if (dst_pos == 98560) {
          print(dst_pos)
        }
        dst(dst_pos) = fromIntermediate(value)

        di += 1
      }

      dst( dstStart + (dstLength - 1) * dstStride ) =
      src( srcStart + (srcLength - 1) * srcStride )
    }
  }

  def cubicInterpolate(
        p0: Float,
        p1: Float,
        p2: Float,
        p3: Float,
        x: Float): Float = {
    val a0 = p1
    val a1 = p2 - p0
    val a2 = 2 * p0 - 5 * p1 + 4 * p2 - p3
    val a3 = 3 * (p1 - p2) + p3 - p0
    a0 + 0.5f * x * (a1 + x * (a2 + x * a3))
  }

  def fromIntermediate(x: Float): Float = {
//    val x = 0.5f + t
//    if( x <= 0 ) {
//      0
//    } else if (x >= 255 ) {
//      255
//    } else {
//      x
//    }
    x
  }

}