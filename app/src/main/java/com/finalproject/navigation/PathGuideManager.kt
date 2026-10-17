package com.finalproject.navigation

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

    fun evaluatePathAlignment(
        pathBoxes: List<BoundingBox>,
        viewWidth: Int
    ): GuidanceResult {
        if (pathBoxes.isEmpty() || viewWidth <= 0) {
            return GuidanceResult(AlignmentState.LOST, 0f, "점자블록을 찾을 수 없습니다. 주의해서 보행하세요.")
        }

        val screenCenterX = viewWidth / 2f
        val primaryBlock = pathBoxes.maxByOrNull { it.w * it.h} ?: return GuidanceResult(AlignmentState.LOST, 0f, "")

        val blockCenterX = primaryBlock.cx
        val deviation = blockCenterX - screenCenterX
        val tolerancePx = viewWidth * CENTER_TOLERANCE_RATIO

        return when {
            abs(deviation) <= tolerancePx -> {
                GuidanceResult(AlignmentState.CENTER, deviation, "경로 중앙입니다. 직진하세요.")
            }
            deviation <= tolerancePx -> {
                GuidanceResult(AlignmentState.RIGHT_DEVIATION, deviation, "오른쪽으로 치우쳤습니다. 왼쪽으로 이동하세요.")
            }
            else -> {
                GuidanceResult(AlignmentState.LEFT_DEVIATION, deviation, "왼쪽으로 치우쳤습니다. 오른쪽으로 이동하세요.")
            }
        }
    }
}