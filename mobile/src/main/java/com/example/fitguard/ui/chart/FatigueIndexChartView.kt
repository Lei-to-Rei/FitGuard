package com.example.fitguard.ui.chart

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.example.fitguard.data.repository.FatigueDataPoint
import java.util.Locale

class FatigueIndexChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var points: List<FatigueDataPoint> = emptyList()
    private var sessionStartMs: Long = 0L
    var fatigueThreshold: Double = 300.0

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

    private val thresholdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D32F2F")
        style = Paint.Style.STROKE
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }

    private val thresholdLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D32F2F")
        textSize = 24f
        textAlign = Paint.Align.LEFT
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

    private val linePath = Path()
    private val fillPath = Path()

    fun setData(points: List<FatigueDataPoint>) {
        this.points = points
        this.sessionStartMs = if (points.isNotEmpty()) points.first().timeMs else 0L
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.size < 2) return

        val leftPad = 80f
        val rightPad = 20f
        val topPad = 30f
        val bottomPad = 40f

        val chartW = width - leftPad - rightPad
        val chartH = height - topPad - bottomPad

        val maxPower = (points.maxOf { it.totalPowerMs2 } * 1.2).coerceAtLeast(fatigueThreshold * 1.5)
        val maxTimeMin = (points.last().timeMs - sessionStartMs) / 60000.0

        // Y-axis grid
        val gridStep = when {
            maxPower > 2000 -> 500.0
            maxPower > 1000 -> 200.0
            maxPower > 500 -> 100.0
            else -> 50.0
        }
        var gridVal = 0.0
        while (gridVal <= maxPower) {
            val y = topPad + chartH * (1f - (gridVal / maxPower).toFloat())
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
            val y = topPad + chartH * (1f - (p.totalPowerMs2 / maxPower).toFloat())
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

        // Fill
        fillPaint.shader = LinearGradient(
            0f, topPad, 0f, topPad + chartH,
            Color.parseColor("#66FF6D00"),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint)

        // Threshold line
        val thresholdY = topPad + chartH * (1f - (fatigueThreshold / maxPower).toFloat())
        if (thresholdY > topPad && thresholdY < topPad + chartH) {
            canvas.drawLine(leftPad, thresholdY, width - rightPad, thresholdY, thresholdPaint)
            canvas.drawText("Fatigue Line", leftPad + 8f, thresholdY - 6f, thresholdLabelPaint)
        }
    }
}
