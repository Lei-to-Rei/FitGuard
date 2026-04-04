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
    private var timeLabels = listOf<String>()

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

    fun setData(points: List<Float>) {
        dataPoints.clear()
        dataPoints.addAll(points)
        invalidate()
    }

    fun setTimeRange(startTime: java.time.Instant, endTime: java.time.Instant) {
        val formatter = java.time.format.DateTimeFormatter
            .ofPattern("ha")
            .withZone(java.time.ZoneId.systemDefault())
        val totalMs = endTime.toEpochMilli() - startTime.toEpochMilli()
        timeLabels = (22..8).map { i ->
            val t = startTime.plusMillis(totalMs * i / 6)
            formatter.format(t).lowercase()
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val leftPad = stageLabels.maxOf { (_, label) -> labelPaint.measureText(label) } + 16f
        val rightPad = 16f
        val topPad = 16f
        val bottomPad = labelPaint.textSize + timeLabelPaint.textSize + 16f

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
        if (timeLabels.size >= 2) {
            val stepX = chartW / (timeLabels.size - 1)
            for (i in timeLabels.indices) {
                val x = leftPad + i * stepX
                canvas.drawText(timeLabels[i], x, height.toFloat() - 8f, timeLabelPaint)
            }
        }

        if (dataPoints.isEmpty()) return

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
