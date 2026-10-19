package com.finalproject.navigation

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

class PathGuideManager {
    companion object {
        private const val CENTER_TOLERANCE_RATIO = 0.15f
    }

    enum class AlignmentState {
        CENTER,
        LEFT_DEVIATION,
        RIGHT_DEVIATION,
        LOST
    }

    data class GuidanceResult(
        val state: AlignmentState,
        val deviationPx: Float,
        val message: String
    )

    private fun isYellowPixel(color: Int): Boolean {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        val hue = hsv[0]
        val saturation = hsv[1]
        val value = hsv[2]
        return hue in 45f..65f && saturation > 0.4f && value > 0.4f
    }

    private fun isValidYellowBlock(bitmap: Bitmap, box: BoundingBox): Boolean {
        val startX = box.x1.toInt().coerceIn(0, bitmap.width - 1)
        val startY = box.y1.toInt().coerceIn(0, bitmap.height - 1)
        val endX = box.x2.toInt().coerceIn(0, bitmap.width - 1)
        val endY = box.y2.toInt().coerceIn(0, bitmap.height - 1)

        var totalPixels = 0
        var yellowPixels = 0
        val step = 5

        for (x in startX..endX step step) {
            for (y in startY..endY step step) {
                val pixel = bitmap.getPixel(x, y)
                totalPixels++
                if (isYellowPixel(pixel)) {
                    yellowPixels++
                }
            }
        }

        if (totalPixels == 0) return false
        val yellowRatio = yellowPixels.toFloat() / totalPixels
        return yellowRatio >= 0.3f
    }

    fun evaluatePathAlignmentWithColor(
        pathBoxes: List<BoundingBox>,
        viewWidth: Int,
        currentBitmap: Bitmap?
    ): GuidanceResult {
        val validBoxes = if (currentBitmap != null) {
            pathBoxes.filter { box -> box.cls == 0 && isValidYellowBlock(currentBitmap, box) }
        } else {
            pathBoxes.filter { box -> box.cls == 0 }
        }

        if (validBoxes.isEmpty() || viewWidth <= 0) {
            return GuidanceResult(AlignmentState.LOST, 0f, "점자블록을 찾을 수 없습니다. 주의해서 보행하세요.")
        }

        val screenCenterX = viewWidth / 2f
        val primaryBlock = validBoxes.maxByOrNull { it.w * it.h } ?: return GuidanceResult(
            AlignmentState.LOST,
            0f,
            ""
        )

        val blockCenterX = viewWidth / 2f
        val deviation = blockCenterX - screenCenterX
        val tolerancePx = viewWidth * CENTER_TOLERANCE_RATIO

        return when {
            abs(deviation) <= tolerancePx -> {
                GuidanceResult(AlignmentState.CENTER, deviation, "경로 중앙입니다. 직진하세요.")
            }

            deviation <= tolerancePx -> {
                GuidanceResult(
                    AlignmentState.RIGHT_DEVIATION,
                    deviation,
                    "오른쪽으로 치우쳤습니다. 왼쪽으로 이동하세요."
                )
            }

            else -> {
                GuidanceResult(
                    AlignmentState.LEFT_DEVIATION,
                    deviation,
                    "왼쪽으로 치우쳤습니다. 오른쪽으로 이동하세요."
                )
            }
        }
    }
}