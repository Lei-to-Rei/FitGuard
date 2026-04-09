package com.example.fitguard.ui.chart

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.example.fitguard.R

class HeartRateChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dataPoints = mutableListOf<Float>()
    private var highlightIndex = -1
    var showGrid = true

    private companion object {
        const val MAX_POINTS = 12
    }

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

    private var minY = 40f
    private var maxY = 100f

    private fun recalculateYRange() {
        if (dataPoints.isEmpty()) {
            minY = 40f
            maxY = 100f
            return
        }
        val dataMin = dataPoints.min()
        val dataMax = dataPoints.max()
        // Round down/up to nearest 10 with padding
        minY = (((dataMin - 10f) / 10f).toInt() * 10f).coerceAtLeast(0f)
        maxY = (((dataMax + 10f) / 10f).toInt() + 1) * 10f
        // Ensure at least 40 range for readability
        if (maxY - minY < 40f) {
            val mid = (minY + maxY) / 2f
            minY = (mid - 20f).coerceAtLeast(0f)
            maxY = minY + 40f
        }
    }

    private fun computeGridLines(): List<Float> {
        val range = maxY - minY
        val step = when {
            range <= 40f -> 10f
            range <= 80f -> 20f
            else -> 30f
        }
        val lines = mutableListOf<Float>()
        var v = minY
        while (v <= maxY) {
            lines.add(v)
            v += step
        }
        return lines
    }

    fun setData(points: List<Float>, highlight: Int = -1) {
        dataPoints.clear()
        dataPoints.addAll(points)
        highlightIndex = highlight
        recalculateYRange()
        invalidate()
    }

    fun addDataPoint(value: Float) {
        dataPoints.add(value)
        if (dataPoints.size > MAX_POINTS) {
            dataPoints.removeAt(0)
        }
        highlightIndex = dataPoints.lastIndex
        recalculateYRange()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val leftPad = if (showGrid) 60f else 8f
        val rightPad = if (showGrid) 20f else 8f
        val topPad = if (showGrid) 40f else 8f
        val bottomPad = if (showGrid) 20f else 8f

        val chartW = width - leftPad - rightPad
        val chartH = height - topPad - bottomPad

        // Draw grid lines and labels
        if (showGrid) {
            for (value in computeGridLines()) {
                val y = topPad + chartH * (1f - (value - minY) / (maxY - minY))
                canvas.drawLine(leftPad, y, width - rightPad, y, gridPaint)
                canvas.drawText(value.toInt().toString(), leftPad - 10f, y + 10f, gridTextPaint)
            }
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

        // Draw highlight (skip in compact mode)
        if (showGrid && highlightIndex in dataPoints.indices) {
            val hx = leftPad + highlightIndex * stepX
            val hy = topPad + chartH * (1f - (dataPoints[highlightIndex] - minY) / (maxY - minY))

            // Yellow highlight bar
            canvas.drawRect(hx - 12f, topPad, hx + 12f, topPad + chartH, highlightPaint)

            // Circle
            canvas.drawCircle(hx, hy, 10f, circlePaint)
            canvas.drawCircle(hx, hy, 10f, circleStrokePaint)

            // Tooltip
            val tooltipText = "${dataPoints[highlightIndex].toInt()} bpm"
            val tooltipW = tooltipTextPaint.measureText(tooltipText) + 24f
            val tooltipH = 40f
            // Place above the point; flip below if it would be clipped
            val tooltipAboveY = hy - 30f - tooltipH
            val tooltipY = if (tooltipAboveY < 0f) hy + 30f else tooltipAboveY
            // Clamp horizontally so tooltip stays within the view
            val tooltipX = (hx - tooltipW / 2).coerceIn(0f, width - tooltipW)

            val tooltipRect = RectF(tooltipX, tooltipY, tooltipX + tooltipW, tooltipY + tooltipH)
            canvas.drawRoundRect(tooltipRect, 8f, 8f, tooltipBgPaint)
            canvas.drawText(tooltipText, tooltipX + tooltipW / 2, tooltipY + tooltipH - 12f, tooltipTextPaint)
        }
    }
}
