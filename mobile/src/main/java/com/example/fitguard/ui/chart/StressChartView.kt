package com.example.fitguard.ui.chart

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class StressChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dataPoints = mutableListOf<Float>()

    // 1=Relaxed, 2=Average, 3=High
    private val minY = 0.5f
    private val maxY = 3.5f

    private val levelLabels = listOf(
        3f to "High",
        2f to "Average",
        1f to "Relaxed"
    )
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6EDB34")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCCCCC")
        style = Paint.Style.STROKE
        strokeWidth = 1f
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#999999")
        textSize = 26f
        textAlign = Paint.Align.RIGHT
    }

    private val linePath = Path()

    fun setData(points: List<Float>) {
        dataPoints.clear()
        dataPoints.addAll(points)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val leftPad = levelLabels.maxOf { (_, label) -> labelPaint.measureText(label) } + 16f
        val rightPad = 20f
        val topPad = 16f
        val bottomPad = 16f

        val chartW = width.toFloat() - leftPad - rightPad
        val chartH = height.toFloat() - topPad - bottomPad

        // Dashed grid lines and Y-axis labels
        for ((value, label) in levelLabels) {
            val y = topPad + chartH * (1f - (value - minY) / (maxY - minY))
            canvas.drawLine(leftPad, y, width.toFloat() - rightPad, y, gridPaint)
            canvas.drawText(label, leftPad - 8f, y + 9f, labelPaint)
        }

        if (dataPoints.isEmpty()) return

        // Smooth curve using cubic bezier
        val dataStepX = chartW / (dataPoints.size - 1).coerceAtLeast(1)
        linePath.reset()
        dataPoints.forEachIndexed { i, value ->
            val x = leftPad + i * dataStepX
            val y = topPad + chartH * (1f - (value - minY) / (maxY - minY))
            if (i == 0) {
                linePath.moveTo(x, y)
            } else {
                val prevX = leftPad + (i - 1) * dataStepX
                val prevY = topPad + chartH * (1f - (dataPoints[i - 1] - minY) / (maxY - minY))
                val cpX = (prevX + x) / 2f
                linePath.cubicTo(cpX, prevY, cpX, y, x, y)
            }
        }
        canvas.drawPath(linePath, linePaint)
    }
}
