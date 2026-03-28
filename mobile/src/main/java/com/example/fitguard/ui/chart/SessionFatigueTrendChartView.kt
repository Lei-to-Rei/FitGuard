package com.example.fitguard.ui.chart

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * Real-time chart showing fatigue percentage over the course of an active session.
 * Supports a dashed prediction line for future extrapolation.
 */
class SessionFatigueTrendChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class TrendPoint(val timeMs: Long, val percent: Float)

    private val dataPoints = mutableListOf<TrendPoint>()
    private val predictionPoints = mutableListOf<TrendPoint>()
    private var sessionStartTime = 0L

    private val minY = 0f
    private val maxY = 100f
    private val gridYValues = listOf(0f, 25f, 50f, 75f, 100f)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF8C00")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF8C00")
        style = Paint.Style.FILL
    }

    private val dotStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val predictionLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FF8C00")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E0E0")
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#999999")
        textSize = 22f
        textAlign = Paint.Align.RIGHT
    }

    private val xLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#999999")
        textSize = 22f
        textAlign = Paint.Align.CENTER
    }

    private val predictionLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FF8C00")
        textSize = 20f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val linePath = Path()
    private val fillPath = Path()
    private val predictionPath = Path()

    fun setSessionStartTime(timeMs: Long) {
        sessionStartTime = timeMs
    }

    fun setData(points: List<TrendPoint>) {
        dataPoints.clear()
        dataPoints.addAll(points)
        if (sessionStartTime == 0L && points.isNotEmpty()) {
            sessionStartTime = points.first().timeMs
        }
        invalidate()
    }

    fun setPrediction(points: List<TrendPoint>) {
        predictionPoints.clear()
        predictionPoints.addAll(points)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dataPoints.isEmpty()) return

        val leftPad = 58f
        val rightPad = 16f
        val topPad = 16f
        val bottomPad = 36f

        val chartW = width.toFloat() - leftPad - rightPad
        val chartH = height.toFloat() - topPad - bottomPad

        // Determine time range
        val startTime = sessionStartTime
        val allPoints = dataPoints + predictionPoints
        val maxTimeMs = allPoints.maxOf { it.timeMs }
        val timeRangeMs = (maxTimeMs - startTime).coerceAtLeast(60_000L)

        // Draw grid lines and Y-axis labels
        for (value in gridYValues) {
            val y = topPad + chartH * (1f - (value - minY) / (maxY - minY))
            canvas.drawLine(leftPad, y, width.toFloat() - rightPad, y, gridPaint)
            canvas.drawText("${value.toInt()}%", leftPad - 6f, y + 8f, labelPaint)
        }

        // Helper to map time to x-coordinate
        fun timeToX(timeMs: Long): Float {
            val fraction = (timeMs - startTime).toFloat() / timeRangeMs
            return leftPad + fraction * chartW
        }

        // Helper to map percent to y-coordinate
        fun percentToY(percent: Float): Float {
            return topPad + chartH * (1f - (percent.coerceIn(minY, maxY) - minY) / (maxY - minY))
        }

        // Draw actual data line and fill
        linePath.reset()
        fillPath.reset()

        dataPoints.forEachIndexed { i, point ->
            val x = timeToX(point.timeMs)
            val y = percentToY(point.percent)
            if (i == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, topPad + chartH)
                fillPath.lineTo(x, y)
            } else {
                val prevX = timeToX(dataPoints[i - 1].timeMs)
                val prevY = percentToY(dataPoints[i - 1].percent)
                val cpX = (prevX + x) / 2f
                linePath.cubicTo(cpX, prevY, cpX, y, x, y)
                fillPath.cubicTo(cpX, prevY, cpX, y, x, y)
            }
        }

        val lastDataX = timeToX(dataPoints.last().timeMs)
        fillPath.lineTo(lastDataX, topPad + chartH)
        fillPath.close()

        // Orange gradient fill
        fillPaint.shader = LinearGradient(
            0f, topPad, 0f, topPad + chartH,
            Color.parseColor("#60FF8C00"),
            Color.parseColor("#05FF8C00"),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint)

        // Data dots
        for (point in dataPoints) {
            val x = timeToX(point.timeMs)
            val y = percentToY(point.percent)
            canvas.drawCircle(x, y, 5f, dotPaint)
            canvas.drawCircle(x, y, 5f, dotStrokePaint)
        }

        // Draw prediction dashed line
        if (predictionPoints.isNotEmpty()) {
            predictionPath.reset()
            // Start from last data point
            val lastData = dataPoints.last()
            predictionPath.moveTo(timeToX(lastData.timeMs), percentToY(lastData.percent))

            for (point in predictionPoints) {
                val x = timeToX(point.timeMs)
                val y = percentToY(point.percent)
                predictionPath.lineTo(x, y)
            }
            canvas.drawPath(predictionPath, predictionLinePaint)

            // Label at the end
            val lastPred = predictionPoints.last()
            val labelX = timeToX(lastPred.timeMs)
            val labelY = percentToY(lastPred.percent) - 12f
            canvas.drawText("5min", labelX, labelY.coerceAtLeast(topPad + 16f), predictionLabelPaint)
        }

        // X-axis time labels (every minute or every 5 minutes depending on range)
        val totalMinutes = timeRangeMs / 60_000.0
        val stepMinutes = when {
            totalMinutes <= 5 -> 1
            totalMinutes <= 15 -> 2
            totalMinutes <= 30 -> 5
            else -> 10
        }
        val xLabelY = height.toFloat() - 8f
        var minuteMark = 0
        while (minuteMark * 60_000L <= timeRangeMs) {
            val timeMs = startTime + minuteMark * 60_000L
            val x = timeToX(timeMs)
            if (x >= leftPad && x <= width - rightPad) {
                canvas.drawText("${minuteMark}m", x, xLabelY, xLabelPaint)
            }
            minuteMark += stepMinutes
        }
    }
}
