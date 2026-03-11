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

    private val minY = 40f
    private val maxY = 100f
    private val gridLines = listOf(40f, 60f, 80f, 100f)

    init {
        dataPoints.addAll(listOf(72f, 68f, 75f, 80f, 65f, 70f, 78f, 72f, 85f, 60f, 68f, 74f))
        highlightIndex = 8
    }

    fun setData(points: List<Float>, highlight: Int = -1) {
        dataPoints.clear()
        dataPoints.addAll(points)
        highlightIndex = highlight
        invalidate()
    }

    fun addDataPoint(value: Float) {
        dataPoints.add(value)
        if (dataPoints.size > MAX_POINTS) {
            dataPoints.removeAt(0)
        }
        highlightIndex = dataPoints.lastIndex
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dataPoints.isEmpty()) return

        val leftPad = 60f
        val rightPad = 20f
        val topPad = 40f
        val bottomPad = 20f

        val chartW = width - leftPad - rightPad
        val chartH = height - topPad - bottomPad

        // Draw grid lines and labels
        for (value in gridLines) {
            val y = topPad + chartH * (1f - (value - minY) / (maxY - minY))
            canvas.drawLine(leftPad, y, width - rightPad, y, gridPaint)
            canvas.drawText(value.toInt().toString(), leftPad - 10f, y + 10f, gridTextPaint)
        }

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
            val tooltipText = "${dataPoints[highlightIndex].toInt()} bpm"
            val tooltipW = tooltipTextPaint.measureText(tooltipText) + 24f
            val tooltipH = 40f
            val tooltipX = hx - tooltipW / 2
            val tooltipY = hy - 30f - tooltipH

            val tooltipRect = RectF(tooltipX, tooltipY, tooltipX + tooltipW, tooltipY + tooltipH)
            canvas.drawRoundRect(tooltipRect, 8f, 8f, tooltipBgPaint)
            canvas.drawText(tooltipText, hx, tooltipY + tooltipH - 12f, tooltipTextPaint)
        }
    }
}
