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
    private val dateLabels = listOf("3/11", "4/11", "5/11", "6/11", "7/11", "8/11")

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

    private val axisLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCCCCC")
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#999999")
        textSize = 26f
        textAlign = Paint.Align.RIGHT
    }

    private val dateLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#999999")
        textSize = 24f
        textAlign = Paint.Align.CENTER
    }

    private val linePath = Path()

    init {
        // Stress levels over 6 days: peaks near "High" around 6/11 then recedes
        dataPoints.addAll(listOf(0.8f, 1.2f, 1.8f, 2.8f, 2.3f, 1.5f))
    }

    fun setData(points: List<Float>) {
        dataPoints.clear()
        dataPoints.addAll(points)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dataPoints.isEmpty()) return

        val leftPad = 72f
        val rightPad = 20f
        val topPad = 16f
        val bottomPad = 40f

        val chartW = width.toFloat() - leftPad - rightPad
        val chartH = height.toFloat() - topPad - bottomPad

        // Dashed grid lines and Y-axis labels
        for ((value, label) in levelLabels) {
            val y = topPad + chartH * (1f - (value - minY) / (maxY - minY))
            canvas.drawLine(leftPad, y, width.toFloat() - rightPad, y, gridPaint)
            canvas.drawText(label, leftPad - 8f, y + 9f, labelPaint)
        }

        // X-axis baseline
        val xAxisY = topPad + chartH
        canvas.drawLine(leftPad, xAxisY, width.toFloat() - rightPad, xAxisY, axisLinePaint)

        // Arrow at end of x-axis
        val arrowX = width.toFloat() - rightPad + 2f
        val arrowSize = 8f
        canvas.drawLine(arrowX - arrowSize, xAxisY - arrowSize / 2, arrowX, xAxisY, axisLinePaint)
        canvas.drawLine(arrowX - arrowSize, xAxisY + arrowSize / 2, arrowX, xAxisY, axisLinePaint)

        // Date labels
        val dateLabelStep = chartW / (dateLabels.size - 1)
        for (i in dateLabels.indices) {
            val x = leftPad + i * dateLabelStep
            canvas.drawText(dateLabels[i], x, height.toFloat() - 8f, dateLabelPaint)
        }

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
