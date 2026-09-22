package com.finalproject.navigation

import android.R.attr.strokeWidth
import android.R.attr.textSize
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var results: List<BoundingBox> = emptyList()
    private var labels = emptyArray<String>()

    fun setResults(boundingBoxes: List<BoundingBox>) {
        this.results = boundingBoxes
        postInvalidate()
    }

    private val boxPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    private val textBackgroundPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        isAntiAlias = true
    }

    fun setResults(boundingBoxes: List<BoundingBox>, labelArray: Array<String>) {
        this.results = boundingBoxes
        this.labels = labelArray
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        for (box in results) {
            val rect = RectF(box.x1, box.y1, box.x2, box.y2)

            canvas.drawRect(rect, boxPaint)

            val className = if (box.cls in labels.indices) labels[box.cls] else "Unknown"
            val text = "$className ${(box.cnf * 100).toInt()}%"

            val textWidth = textPaint.measureText(text)
            val textHeight = textPaint.textSize

            val textBackgroundRect = RectF(
                box.x1,
                box.y1 - textHeight - 10f,
                box.x1 + textWidth + 10f,
                box.y1
            )
            canvas.drawText(
                text,
                box.x1 + 5f,
                box.y1 - 10f,
                textPaint
            )
        }
    }
}