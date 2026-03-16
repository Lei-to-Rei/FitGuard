package com.example.fitguard.ui.chart

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class SleepChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dataPoints = mutableListOf<Float>()

    // 1=Deep Sleep, 2=Light Sleep, 3=REM Sleep, 4=Awake
    private val minY = 1f
    private val maxY = 4f

    private val stageLabels = listOf(
        4f to "Awake",
        3f to "REM Sleep",
        2f to "Light Sleep",
        1f to "Deep Sleep"
    )
    private val timeLabels = listOf("12AM", "1", "2", "3", "4", "5", "6")

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1565C0")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E0E0")
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#999999")
        textSize = 26f
        textAlign = Paint.Align.RIGHT
    }

    private val timeLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#999999")
        textSize = 24f
        textAlign = Paint.Align.CENTER
    }

    private val linePath = Path()

    init {
        // Sleep hypnogram: 13 points across 6 hours (every ~30 min, 12AM–6AM)
        dataPoints.addAll(listOf(2f, 1f, 1f, 2f, 3f, 2f, 1f, 1f, 2f, 3f, 2f, 3f, 4f))
    }

    fun setData(points: List<Float>) {
        dataPoints.clear()
        dataPoints.addAll(points)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dataPoints.isEmpty()) return

        val leftPad = 100f
        val rightPad = 16f
        val topPad = 16f
        val bottomPad = 40f

        val chartW = width.toFloat() - leftPad - rightPad
        val chartH = height.toFloat() - topPad - bottomPad

        // Grid lines and stage labels
        for ((value, label) in stageLabels) {
            val y = topPad + chartH * (1f - (value - minY) / (maxY - minY))
            canvas.drawLine(leftPad, y, width.toFloat() - rightPad, y, gridPaint)
            canvas.drawText(label, leftPad - 8f, y + 9f, labelPaint)
        }

        // "Time" label centered in left area at bottom
        timeLabelPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("Time", leftPad / 2f, height.toFloat() - 8f, timeLabelPaint)

        // X-axis time labels
        val stepX = chartW / (timeLabels.size - 1)
        for (i in timeLabels.indices) {
            val x = leftPad + i * stepX
            canvas.drawText(timeLabels[i], x, height.toFloat() - 8f, timeLabelPaint)
        }

        // Draw line path
        val dataStepX = chartW / (dataPoints.size - 1).coerceAtLeast(1)
        linePath.reset()
        dataPoints.forEachIndexed { i, value ->
            val x = leftPad + i * dataStepX
            val y = topPad + chartH * (1f - (value - minY) / (maxY - minY))
            if (i == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
        }
        canvas.drawPath(linePath, linePaint)
    }
}
