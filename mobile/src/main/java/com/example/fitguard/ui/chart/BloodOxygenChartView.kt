package com.example.fitguard.ui.chart

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.example.fitguard.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BloodOxygenChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dataPoints = mutableListOf<Float>()
    private val timestamps = mutableListOf<Long>()
    private var highlightIndex = -1
    private val lowThreshold = 90f
    private var lastAddTime = 0L

    private companion object {
        const val MAX_POINTS = 12
        const val THROTTLE_MS = 2000L
    }

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6EDB34")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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

    private val xLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#999999")
        textSize = 22f
        textAlign = Paint.Align.CENTER
    }

    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.highlight_yellow)
        style = Paint.Style.FILL
    }

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6EDB34")
        style = Paint.Style.FILL
    }

    private val circleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val lowDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E53935")
        style = Paint.Style.FILL
    }

    private val tooltipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.tooltip_bg)
        style = Paint.Style.FILL
    }

    private val tooltipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val linePath = Path()
    private val fillPath = Path()

    private var minY = 90f
    private var maxY = 100f

    private fun recalculateYRange() {
        if (dataPoints.isEmpty()) {
            minY = 90f
            maxY = 100f
            return
        }
        val dataMin = dataPoints.min()
        val dataMax = dataPoints.max()
        minY = (((dataMin - 2f) / 5f).toInt() * 5f).coerceAtLeast(0f)
        maxY = ((((dataMax + 2f) / 5f).toInt() + 1) * 5f).coerceAtMost(100f)
        if (maxY - minY < 10f) {
            minY = (maxY - 10f).coerceAtLeast(0f)
        }
    }

    private fun computeGridLines(): List<Float> {
        val range = maxY - minY
        val step = when {
            range <= 10f -> 2f
            range <= 20f -> 5f
            else -> 10f
        }
        val lines = mutableListOf<Float>()
        var v = minY
        while (v <= maxY) {
            lines.add(v)
            v += step
        }
        return lines
    }

    fun setData(points: List<Float>, times: List<Long> = emptyList()) {
        dataPoints.clear()
        dataPoints.addAll(points)
        timestamps.clear()
        timestamps.addAll(times)
        highlightIndex = if (points.isNotEmpty()) points.lastIndex else -1
        recalculateYRange()
        invalidate()
    }

    fun addDataPoint(value: Float) {
        val now = System.currentTimeMillis()
        if (now - lastAddTime < THROTTLE_MS && dataPoints.isNotEmpty()) {
            dataPoints[dataPoints.lastIndex] = value
            if (timestamps.isNotEmpty()) timestamps[timestamps.lastIndex] = now
        } else {
            dataPoints.add(value)
            timestamps.add(now)
            if (dataPoints.size > MAX_POINTS) {
                dataPoints.removeAt(0)
                timestamps.removeAt(0)
            }
            lastAddTime = now
        }
        highlightIndex = dataPoints.lastIndex
        recalculateYRange()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val leftPad = 60f
        val rightPad = 40f
        val topPad = 40f
        val bottomPad = 40f

        val chartW = width - leftPad - rightPad
        val chartH = height - topPad - bottomPad

        // Draw grid lines and labels
        for (value in computeGridLines()) {
            val y = topPad + chartH * (1f - (value - minY) / (maxY - minY))
            canvas.drawLine(leftPad, y, width - rightPad, y, gridPaint)
            canvas.drawText(value.toInt().toString(), leftPad - 10f, y + 10f, gridTextPaint)
        }

        if (dataPoints.isEmpty()) return

        // Build path
        val stepX = chartW / (dataPoints.size - 1).coerceAtLeast(1)

        linePath.reset()
        fillPath.reset()

        dataPoints.forEachIndexed { i, value ->
            val x = leftPad + i * stepX
            val y = topPad + chartH * (1f - (value - minY) / (maxY - minY))
            if (i == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, topPad + chartH)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }

        // Close fill path
        val lastX = leftPad + (dataPoints.size - 1) * stepX
        fillPath.lineTo(lastX, topPad + chartH)
        fillPath.close()

        // Fill gradient
        fillPaint.shader = LinearGradient(
            0f, topPad, 0f, topPad + chartH,
            context.getColor(R.color.chart_green_fill),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(fillPath, fillPaint)

        // Draw line
        canvas.drawPath(linePath, linePaint)

        // X-axis time labels
        if (timestamps.size == dataPoints.size) {
            val labelStep = when {
                dataPoints.size <= 4 -> 1
                dataPoints.size <= 8 -> 2
                else -> 3
            }
            for (i in dataPoints.indices) {
                if (i % labelStep == 0 || i == dataPoints.lastIndex) {
                    val x = leftPad + i * stepX
                    val label = timeFormat.format(Date(timestamps[i]))
                    canvas.drawText(label, x, topPad + chartH + 30f, xLabelPaint)
                }
            }
        }

        // Red dots for low readings
        dataPoints.forEachIndexed { i, value ->
            if (value < lowThreshold) {
                val x = leftPad + i * stepX
                val y = topPad + chartH * (1f - (value - minY) / (maxY - minY))
                canvas.drawCircle(x, y, 8f, lowDotPaint)
            }
        }

        // Draw highlight
        if (highlightIndex in dataPoints.indices) {
            val hx = leftPad + highlightIndex * stepX
            val hy = topPad + chartH * (1f - (dataPoints[highlightIndex] - minY) / (maxY - minY))

            // Yellow highlight bar
            canvas.drawRect(hx - 12f, topPad, hx + 12f, topPad + chartH, highlightPaint)

            // Circle
            canvas.drawCircle(hx, hy, 10f, circlePaint)
            canvas.drawCircle(hx, hy, 10f, circleStrokePaint)

            // Tooltip
            val tooltipText = "${dataPoints[highlightIndex].toInt()}%"
            val tooltipW = tooltipTextPaint.measureText(tooltipText) + 24f
            val tooltipH = 40f
            val tooltipAboveY = hy - 30f - tooltipH
            val tooltipY = if (tooltipAboveY < 0f) hy + 30f else tooltipAboveY
            val tooltipX = (hx - tooltipW / 2).coerceIn(0f, width - tooltipW)

            val tooltipRect = RectF(tooltipX, tooltipY, tooltipX + tooltipW, tooltipY + tooltipH)
            canvas.drawRoundRect(tooltipRect, 8f, 8f, tooltipBgPaint)
            canvas.drawText(tooltipText, tooltipX + tooltipW / 2, tooltipY + tooltipH - 12f, tooltipTextPaint)
        }
    }
}
