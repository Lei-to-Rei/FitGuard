package com.example.fitguard.ui.chart

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.example.fitguard.data.repository.HrDataPoint
import java.util.Locale

class DetailHeartRateChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var points: List<HrDataPoint> = emptyList()
    private var avgHr: Float = 0f
    private var sessionStartMs: Long = 0L

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF6D00")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val avgLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D32F2F")
        style = Paint.Style.STROKE
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E0E0")
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private val gridTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#999999")
        textSize = 26f
        textAlign = Paint.Align.RIGHT
    }

    private val xLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#999999")
        textSize = 26f
        textAlign = Paint.Align.CENTER
    }

    private val avgLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D32F2F")
        textSize = 24f
        textAlign = Paint.Align.LEFT
    }

    private val linePath = Path()
    private val fillPath = Path()

    fun setData(points: List<HrDataPoint>, avgHr: Float) {
        this.points = points
        this.avgHr = avgHr
        this.sessionStartMs = if (points.isNotEmpty()) points.first().timeMs else 0L
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.size < 2) return

        val leftPad = 70f
        val rightPad = 20f
        val topPad = 30f
        val bottomPad = 40f

        val chartW = width - leftPad - rightPad
        val chartH = height - topPad - bottomPad

        val minHr = (points.minOf { it.hrBpm } - 10f).coerceAtLeast(40f)
        val maxHr = (points.maxOf { it.hrBpm } + 10f).coerceAtMost(220f)
        val hrRange = (maxHr - minHr).coerceAtLeast(1f)

        val maxTimeMin = (points.last().timeMs - sessionStartMs) / 60000.0

        // Grid lines (Y axis)
        val gridStep = when {
            hrRange > 100 -> 40f
            hrRange > 60 -> 20f
            else -> 10f
        }
        var gridVal = (minHr / gridStep).toInt() * gridStep
        while (gridVal <= maxHr) {
            val y = topPad + chartH * (1f - (gridVal - minHr) / hrRange)
            canvas.drawLine(leftPad, y, width - rightPad, y, gridPaint)
            canvas.drawText(gridVal.toInt().toString(), leftPad - 8f, y + 8f, gridTextPaint)
            gridVal += gridStep
        }

        // X axis labels
        val xStep = when {
            maxTimeMin > 60 -> 15.0
            maxTimeMin > 30 -> 10.0
            maxTimeMin > 10 -> 5.0
            else -> 2.0
        }
        var xVal = 0.0
        while (xVal <= maxTimeMin) {
            val x = leftPad + (xVal / maxTimeMin * chartW).toFloat()
            canvas.drawText(String.format(Locale.US, "%.0f", xVal), x, height - 8f, xLabelPaint)
            xVal += xStep
        }

        // Build path
        linePath.reset()
        fillPath.reset()

        points.forEachIndexed { i, p ->
            val x = leftPad + ((p.timeMs - sessionStartMs) / 60000.0 / maxTimeMin * chartW).toFloat()
            val y = topPad + chartH * (1f - (p.hrBpm - minHr) / hrRange)
            if (i == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, topPad + chartH)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }

        val lastX = leftPad + ((points.last().timeMs - sessionStartMs) / 60000.0 / maxTimeMin * chartW).toFloat()
        fillPath.lineTo(lastX, topPad + chartH)
        fillPath.close()

        // Fill gradient
        fillPaint.shader = LinearGradient(
            0f, topPad, 0f, topPad + chartH,
            Color.parseColor("#66FF6D00"),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint)

        // Average HR line
        if (avgHr > 0f) {
            val avgY = topPad + chartH * (1f - (avgHr - minHr) / hrRange)
            canvas.drawLine(leftPad, avgY, width - rightPad, avgY, avgLinePaint)
            canvas.drawText(
                String.format(Locale.US, "Avg: %.0f bpm", avgHr),
                leftPad + 8f, avgY - 6f, avgLabelPaint
            )
        }
    }
}
