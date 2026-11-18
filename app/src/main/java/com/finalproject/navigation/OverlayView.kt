package com.finalproject.navigation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

class OverlayView @JvmOverloads constructor (context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) : View(context, attrs) {

    private var results: List<BoundingBox> = emptyList()
    private var labels = emptyArray<String>()

    private var frameWidth: Int = 640
    private var frameHeight: Int = 640

    // 시연 화면처럼 카메라가 화면을 꽉 채울 때는 true (미리보기의 채우기 비율과 맞춤)
    var fillMode = false
        set(value) { field = value; postInvalidate() }

    // 감지 개수와 가장 가까운 물체가 바뀔 때 화면 위쪽 안내 줄에 알려 줌
    var onSummaryChanged: ((count: Int, nearestName: String?, nearestDistance: Float) -> Unit)? = null

    private val density = resources.displayMetrics.density

    // 가장 가까운 물체: 굵은 연두색 상자 + 이름표
    private val mainBoxPaint = Paint().apply {
        color = BOX_COLOR
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        isAntiAlias = true
    }

    // 나머지 물체: 얇고 흐린 상자
    private val subBoxPaint = Paint().apply {
        color = BOX_COLOR
        alpha = 170
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        isAntiAlias = true
    }

    private val labelBackgroundPaint = Paint().apply {
        color = BOX_COLOR
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val labelTextPaint = Paint().apply {
        color = Color.parseColor("#0B1F0E")
        textSize = 14f * density
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }

    fun setResults(
        boundingBoxes: List<BoundingBox>,
        labelArray: Array<String> = emptyArray(),
        imgWidth: Int = 640,
        imgHeight: Int = 640
    ) {
        this.results = boundingBoxes
        this.labels = labelArray
        this.frameWidth = if (imgWidth > 0) imgWidth else 640
        this.frameHeight = if (imgHeight > 0) imgHeight else 640
        val nearest = findNearest()
        onSummaryChanged?.invoke(
            boundingBoxes.size,
            nearest?.let { nameOf(it) },
            nearest?.distanceMeter ?: 0f
        )
        postInvalidate()
    }

    // 거리값이 있으면 가장 가까운 것, 없으면 화면에서 가장 큰 것
    private fun findNearest(): BoundingBox? {
        val withDistance = results.filter { it.distanceMeter > 0f }
        return if (withDistance.isNotEmpty()) withDistance.minByOrNull { it.distanceMeter }
        else results.maxByOrNull { (it.x2 - it.x1) * (it.y2 - it.y1) }
    }

    // 화면 표시용 이름만 한국어로 바꿈 (탐지·음성 로직은 영어 이름을 그대로 사용)
    // 순서(인덱스)가 아니라 영어 라벨 이름으로 찾음. 모르는 라벨은 원래 이름 그대로
    private fun nameOf(box: BoundingBox): String {
        val english = box.clsName.ifBlank {
            if (box.cls in labels.indices) labels[box.cls] else ""
        }
        return KOREAN_LABELS[english.lowercase()] ?: english
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (results.isEmpty()) return

        // 미리보기 비율에 맞춰 탐지 상자가 늘어나지 않게 그림 (맞춤: 작은 쪽, 채우기: 큰 쪽 기준)
        val scaleX = width.toFloat() / frameWidth
        val scaleY = height.toFloat() / frameHeight
        val scale = if (fillMode) maxOf(scaleX, scaleY) else minOf(scaleX, scaleY)
        val offsetX = (width - frameWidth * scale) / 2f
        val offsetY = (height - frameHeight * scale) / 2f
        val nearest = findNearest()

        for (box in results) {
            val rect = RectF(
                box.x1 * scale + offsetX,
                box.y1 * scale + offsetY,
                box.x2 * scale + offsetX,
                box.y2 * scale + offsetY
            )
            if (box !== nearest) {
                canvas.drawRoundRect(rect, 3f * density, 3f * density, subBoxPaint)
                continue
            }

            canvas.drawRoundRect(rect, 4f * density, 4f * density, mainBoxPaint)
            val bgPaint = labelBackgroundPaint
            val textPaint = labelTextPaint

            // 이름표: "사람 · 93%"
            val text = "${nameOf(box)} · ${(box.cnf * 100).toInt()}%"
            val padH = 8f * density
            val padV = 5f * density
            val textWidth = textPaint.measureText(text)
            val textHeight = textPaint.textSize
            val labelWidth = textWidth + padH * 2
            val labelHeight = textHeight + padV * 2
            // 화면 오른쪽으로 넘치면 왼쪽으로 당기고, 왼쪽 끝은 0보다 작아지지 않게 함
            val left = minOf(rect.left, width - labelWidth).coerceAtLeast(0f)
            // 화면 위로 넘치면 상자 안쪽으로 내림
            val top = rect.top.coerceAtLeast(0f)
            val labelRect = RectF(left, top, left + labelWidth, top + labelHeight)
            canvas.drawRect(labelRect, bgPaint)
            canvas.drawText(text, labelRect.left + padH, labelRect.bottom - padV - 2f * density, textPaint)
        }
    }

    companion object {
        private val BOX_COLOR = Color.parseColor("#7EE08A")

        // 팀 보행 모델(7종) 영어 라벨 → 한국어 물체 이름 (소문자로 찾음)
        private val KOREAN_LABELS = mapOf(
            "braillebrailleblock-detect" to "점자블록",
            "bike" to "자전거",
            "crosswalk" to "횡단보도",
            "hazard-sign" to "위험표지판",
            "person" to "사람",
            "stairs" to "계단",
            "vehicle" to "차량"
        )
    }
}
