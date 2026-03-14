package com.example.fitguard.ui.chart

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.example.fitguard.R

class BloodPressureChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val systolicData = mutableListOf<Float>()
    private val diastolicData = mutableListOf<Float>()

    private companion object {
        const val MAX_POINTS = 12
    }

    private val systolicPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.chart_blue_systolic)
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val diastolicPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.chart_red_diastolic)
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

    private val gridTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#999999")
        textSize = 28f
        textAlign = Paint.Align.RIGHT
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val legendTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#666666")
        textSize = 26f
    }

    private val systolicPath = Path()
    private val diastolicPath = Path()

    private val minY = 60f
    private val maxY = 160f
    private val gridLines = listOf(60f, 80f, 100f, 120f, 140f, 160f)

    init {
        systolicData.addAll(listOf(120f, 125f, 118f, 130f, 122f, 128f, 115f, 120f, 135f, 119f, 124f, 120f))
        diastolicData.addAll(listOf(80f, 82f, 78f, 85f, 80f, 83f, 76f, 80f, 88f, 79f, 81f, 80f))
    }

    fun setData(systolic: List<Float>, diastolic: List<Float>) {
        systolicData.clear()
        systolicData.addAll(systolic)
        diastolicData.clear()
        diastolicData.addAll(diastolic)
        invalidate()
    }

    fun addDataPoint(systolic: Float, diastolic: Float) {
        systolicData.add(systolic)
        diastolicData.add(diastolic)
        if (systolicData.size > MAX_POINTS) {
            systolicData.removeAt(0)
            diastolicData.removeAt(0)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (systolicData.isEmpty()) return

        val leftPad = 60f
        val rightPad = 20f
        val topPad = 20f
        val bottomPad = 40f

        val chartW = width - leftPad - rightPad
        val chartH = height - topPad - bottomPad

        // Grid
        for (value in gridLines) {
            val y = topPad + chartH * (1f - (value - minY) / (maxY - minY))
            canvas.drawLine(leftPad, y, width - rightPad, y, gridPaint)
            canvas.drawText(value.toInt().toString(), leftPad - 10f, y + 10f, gridTextPaint)
        }

        val count = systolicData.size.coerceAtMost(diastolicData.size)
        val stepX = chartW / (count - 1).coerceAtLeast(1)

        // Systolic line
        systolicPath.reset()
        for (i in 0 until count) {
            val x = leftPad + i * stepX
            val y = topPad + chartH * (1f - (systolicData[i] - minY) / (maxY - minY))
            if (i == 0) systolicPath.moveTo(x, y) else systolicPath.lineTo(x, y)
        }
        canvas.drawPath(systolicPath, systolicPaint)

        // Diastolic line
        diastolicPath.reset()
        for (i in 0 until count) {
            val x = leftPad + i * stepX
            val y = topPad + chartH * (1f - (diastolicData[i] - minY) / (maxY - minY))
            if (i == 0) diastolicPath.moveTo(x, y) else diastolicPath.lineTo(x, y)
        }
        canvas.drawPath(diastolicPath, diastolicPaint)

        // Data dots
        for (i in 0 until count) {
            val x = leftPad + i * stepX

            val sysY = topPad + chartH * (1f - (systolicData[i] - minY) / (maxY - minY))
            dotPaint.color = context.getColor(R.color.chart_blue_systolic)
            canvas.drawCircle(x, sysY, 5f, dotPaint)

            val diaY = topPad + chartH * (1f - (diastolicData[i] - minY) / (maxY - minY))
            dotPaint.color = context.getColor(R.color.chart_red_diastolic)
            canvas.drawCircle(x, diaY, 5f, dotPaint)
        }

        // Legend
        val legendY = height - 8f
        dotPaint.color = context.getColor(R.color.chart_blue_systolic)
        canvas.drawCircle(leftPad + 10f, legendY - 8f, 6f, dotPaint)
        canvas.drawText("Systolic", leftPad + 24f, legendY, legendTextPaint)

        dotPaint.color = context.getColor(R.color.chart_red_diastolic)
        canvas.drawCircle(leftPad + 140f, legendY - 8f, 6f, dotPaint)
        canvas.drawText("Diastolic", leftPad + 154f, legendY, legendTextPaint)
    }
}
