package com.example.fitguard.ui.chart

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class BloodOxygenChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dataPoints = mutableListOf<Float>()
    private val lowThreshold = 90f

    private companion object {
        const val MAX_POINTS = 12
    }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6EDB34")
        style = Paint.Style.FILL
    }

    private val lowDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E53935")
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E0E0")
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private val gridTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#999999")
        textSize = 28f
        textAlign = Paint.Align.RIGHT
    }

    private val rangeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val rangeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#666666")
        textSize = 26f
    }

    private val rangeLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#999999")
        textSize = 24f
    }

    private val minY = 70f
    private val maxY = 100f
    private val gridLines = listOf(70f, 80f, 90f, 100f)


    fun setData(points: List<Float>) {
        dataPoints.clear()
        dataPoints.addAll(points)
        invalidate()
    }

    fun addDataPoint(value: Float) {
        dataPoints.add(value)
        if (dataPoints.size > MAX_POINTS) {
            dataPoints.removeAt(0)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val leftPad = 60f
        val rightPad = 20f
        val topPad = 20f
        val bottomPad = 80f // room for range bar

        val chartW = width - leftPad - rightPad
        val chartH = height - topPad - bottomPad

        // Grid (always visible)
        for (value in gridLines) {
            val y = topPad + chartH * (1f - (value - minY) / (maxY - minY))
            canvas.drawLine(leftPad, y, width - rightPad, y, gridPaint)
            canvas.drawText(value.toInt().toString(), leftPad - 10f, y + 10f, gridTextPaint)
        }

        // Range bar at bottom (always visible)
        val rangeBarTop = height - 50f
        val rangeBarHeight = 10f
        val rangeRect = RectF(leftPad, rangeBarTop, width - rightPad, rangeBarTop + rangeBarHeight)

        rangeBgPaint.shader = LinearGradient(
            leftPad, 0f, width - rightPad, 0f,
            Color.parseColor("#E0E0E0"),
            Color.parseColor("#6EDB34"),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rangeRect, 5f, 5f, rangeBgPaint)

        // Range labels
        canvas.drawText("70", leftPad, rangeBarTop + rangeBarHeight + 24f, rangeLabelPaint)
        rangeTextPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("100", width - rightPad, rangeBarTop + rangeBarHeight + 24f, rangeTextPaint)
        rangeTextPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("SpO2 Range", (leftPad + width - rightPad) / 2, rangeBarTop + rangeBarHeight + 24f, rangeTextPaint)

        if (dataPoints.isEmpty()) return

        // Bars
        val barCount = dataPoints.size
        val totalBarWidth = chartW / barCount
        val barWidth = totalBarWidth * 0.6f
        val barGap = totalBarWidth * 0.4f / 2f

        for (i in dataPoints.indices) {
            val x = leftPad + i * totalBarWidth + barGap
            val valueNorm = ((dataPoints[i] - minY) / (maxY - minY)).coerceIn(0f, 1f)
            val barTop = topPad + chartH * (1f - valueNorm)
            val barBottom = topPad + chartH

            // Green bar
            val barRect = RectF(x, barTop, x + barWidth, barBottom)
            canvas.drawRoundRect(barRect, 4f, 4f, barPaint)

            // Red dot for low readings
            if (dataPoints[i] < lowThreshold) {
                canvas.drawCircle(x + barWidth / 2, barTop - 12f, 6f, lowDotPaint)
            }
        }
    }
}
