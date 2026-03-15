package com.example.fitguard.ui.chart

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class FatigueWeekChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dataPoints = mutableListOf<Float>()

    // 0–100 percentage range
    private val minY = 0f
    private val maxY = 100f
    private val gridLines = listOf(0f, 25f, 50f, 75f, 100f)

    private val dayLabels  = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val dateLabels = listOf("01",  "02",  "03",  "04",  "05",  "06",  "07")

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

    private val dayLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#999999")
        textSize = 22f
        textAlign = Paint.Align.CENTER
    }

    private val sundayLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6EDB34")
        textSize = 22f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val linePath = Path()
    private val fillPath = Path()

    init {
        // Fatigue % over Mon–Sun
        dataPoints.addAll(listOf(50f, 55f, 65f, 75f, 72f, 68f, 60f))
    }

    fun setData(points: List<Float>) {
        dataPoints.clear()
        dataPoints.addAll(points)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dataPoints.isEmpty()) return

        val leftPad = 58f
        val rightPad = 16f
        val topPad = 16f
        val bottomPad = 52f  // room for two-line x-axis labels

        val chartW = width.toFloat() - leftPad - rightPad
        val chartH = height.toFloat() - topPad - bottomPad

        // Grid lines and Y-axis labels
        for (value in gridLines) {
            val y = topPad + chartH * (1f - (value - minY) / (maxY - minY))
            canvas.drawLine(leftPad, y, width.toFloat() - rightPad, y, gridPaint)
            canvas.drawText("${value.toInt()}%", leftPad - 6f, y + 8f, labelPaint)
        }

        // Line and fill paths
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
                // Smooth curve via cubic bezier
                val prevX = leftPad + (i - 1) * stepX
                val prevY = topPad + chartH * (1f - (dataPoints[i - 1] - minY) / (maxY - minY))
                val cpX = (prevX + x) / 2f
                linePath.cubicTo(cpX, prevY, cpX, y, x, y)
                fillPath.cubicTo(cpX, prevY, cpX, y, x, y)
            }
        }
        val lastX = leftPad + (dataPoints.size - 1) * stepX
        fillPath.lineTo(lastX, topPad + chartH)
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

        // Data point circles
        dataPoints.forEachIndexed { i, value ->
            val x = leftPad + i * stepX
            val y = topPad + chartH * (1f - (value - minY) / (maxY - minY))
            canvas.drawCircle(x, y, 6f, dotPaint)
            canvas.drawCircle(x, y, 6f, dotStrokePaint)
        }

        // X-axis labels (day name + date number, Sunday in green)
        val dayY   = height.toFloat() - 26f
        val dateY  = height.toFloat() - 8f
        for (i in dayLabels.indices) {
            val x = leftPad + i * stepX
            val isSunday = (i == 6)
            val paint = if (isSunday) sundayLabelPaint else dayLabelPaint
            canvas.drawText(dayLabels[i], x, dayY, paint)
            canvas.drawText(dateLabels[i], x, dateY, paint)
        }
    }
}
