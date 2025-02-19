/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.vito.options

data class RoundingOptions(
    val isCircular: Boolean,
    val cornerRadius: Float,
    val cornerRadii: FloatArray?,
    val isAntiAliased: Boolean,
    val isForceRoundAtDecode: Boolean
) {
  fun hasRoundedCorners(): Boolean = cornerRadius != CORNER_RADIUS_UNSET || cornerRadii != null

  companion object {
    const val CORNER_RADIUS_UNSET = 0f
    val AS_CIRCLE = RoundingOptions(true, CORNER_RADIUS_UNSET, null, false, false)
    val AS_CIRCLE_ANTI_ALIASING = RoundingOptions(true, CORNER_RADIUS_UNSET, null, true, false)

    @JvmStatic fun asCircle(): RoundingOptions = AS_CIRCLE

    @JvmStatic
    fun asCircle(antiAliasing: Boolean): RoundingOptions =
        if (antiAliasing) AS_CIRCLE_ANTI_ALIASING else AS_CIRCLE

    @JvmStatic
    fun asCircle(antiAliasing: Boolean, forceRoundAtDecode: Boolean): RoundingOptions {
      return RoundingOptions(true, CORNER_RADIUS_UNSET, null, antiAliasing, forceRoundAtDecode)
    }

    @JvmStatic
    fun forCornerRadiusPx(cornerRadiusPx: Float): RoundingOptions {
      return RoundingOptions(false, cornerRadiusPx, null, false, false)
    }

    @JvmStatic
    fun forCornerRadii(
        topLeft: Float,
        topRight: Float,
        bottomRight: Float,
        bottomLeft: Float
    ): RoundingOptions {
      val radii = FloatArray(8)
      radii[0] = topLeft
      radii[1] = topLeft
      radii[2] = topRight
      radii[3] = topRight
      radii[4] = bottomRight
      radii[5] = bottomRight
      radii[6] = bottomLeft
      radii[7] = bottomLeft
      return RoundingOptions(false, CORNER_RADIUS_UNSET, radii, false, false)
    }

    @JvmStatic
    fun forCornerRadii(cornerRadii: FloatArray?, antiAliasing: Boolean): RoundingOptions {
      return RoundingOptions(false, CORNER_RADIUS_UNSET, cornerRadii, antiAliasing, false)
    }
  }
}
