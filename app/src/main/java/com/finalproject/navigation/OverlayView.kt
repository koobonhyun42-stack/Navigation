package com.finalproject.navigation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var results: List<BoundingBox> = emptyList()
    private var labels = emptyArray<String>()

    private var frameWidth: Int = 640
    private var frameHeight: Int = 640

    private val boxPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    private val textBackgroundPaint = Paint().apply {
        color = Color.argb(180, 0, 0, 0)
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 38f
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
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (results.isEmpty()) return

        val scaleX = width.toFloat() / frameWidth.toFloat()
        val scaleY = height.toFloat() / frameHeight.toFloat()

        for (box in results) {
            val left = box.x1 * scaleX
            val top = box.y1 * scaleY
            val right = box.x2 * scaleX
            val bottom = box.y2 * scaleY

            val rect = RectF(left, top, right, bottom)

            canvas.drawRect(rect, boxPaint)

            val className = if (box.cls in labels.indices) labels[box.cls] else box.clsName
            val confidencePercent = (box.cnf * 100).toInt()

            val text = if (box.distanceMeter > 0f) {
                String.format(java.util.Locale.ROOT, "%s %d%% (%.1fm)", className, confidencePercent, box.distanceMeter)
            } else {
                String.format(java.util.Locale.ROOT, "%s %d%%", className, confidencePercent)
            }

            val textWidth = textPaint.measureText(text)
            val textHeight = textPaint.textSize

            val textBackgroundRect = RectF(
                left,
                top - textHeight - 12f,
                left + textWidth + 16f,
                top
            )

            if (textBackgroundRect.top < 0) {
                textBackgroundRect.top = top
                textBackgroundRect.bottom = top + textHeight + 12f
            }

            canvas.drawRect(textBackgroundRect, textBackgroundPaint)
            canvas.drawText(
                text,
                textBackgroundRect.left + 8f,
                textBackgroundRect.bottom - 6f,
                textPaint
            )
        }
    }
}