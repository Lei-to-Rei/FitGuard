package com.example.fitguard.ui.chart

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.example.fitguard.R

class SkinTemperatureChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val skinData = mutableListOf<Float>()
    private val ambientData = mutableListOf<Float>()

    private companion object {
        const val MAX_POINTS = 12
    }

    private val skinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.chart_blue_systolic)
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val ambientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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

    private val skinPath = Path()
    private val ambientPath = Path()

    private val minY = 20f
    private val maxY = 42f
    private val gridLines = listOf(20f, 25f, 30f, 35f, 40f)

    fun setData(skin: List<Float>, ambient: List<Float>) {
        skinData.clear()
        skinData.addAll(skin)
        ambientData.clear()
        ambientData.addAll(ambient)
        invalidate()
    }

    fun addDataPoint(skin: Float, ambient: Float) {
        skinData.add(skin)
        ambientData.add(ambient)
        if (skinData.size > MAX_POINTS) {
            skinData.removeAt(0)
            ambientData.removeAt(0)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val leftPad = 60f
        val rightPad = 20f
        val topPad = 20f
        val bottomPad = 40f

        val chartW = width - leftPad - rightPad
        val chartH = height - topPad - bottomPad

        // Grid (always visible)
        for (value in gridLines) {
            val y = topPad + chartH * (1f - (value - minY) / (maxY - minY))
            canvas.drawLine(leftPad, y, width - rightPad, y, gridPaint)
            canvas.drawText("${value.toInt()}°", leftPad - 10f, y + 10f, gridTextPaint)
        }

        if (skinData.isEmpty()) return

        val count = skinData.size.coerceAtMost(ambientData.size)
        val stepX = chartW / (count - 1).coerceAtLeast(1)

        // Skin temp line
        skinPath.reset()
        for (i in 0 until count) {
            val x = leftPad + i * stepX
            val y = topPad + chartH * (1f - (skinData[i] - minY) / (maxY - minY))
            if (i == 0) skinPath.moveTo(x, y) else skinPath.lineTo(x, y)
        }
        canvas.drawPath(skinPath, skinPaint)

        // Ambient temp line
        ambientPath.reset()
        for (i in 0 until count) {
            val x = leftPad + i * stepX
            val y = topPad + chartH * (1f - (ambientData[i] - minY) / (maxY - minY))
            if (i == 0) ambientPath.moveTo(x, y) else ambientPath.lineTo(x, y)
        }
        canvas.drawPath(ambientPath, ambientPaint)

        // Data dots
        for (i in 0 until count) {
            val x = leftPad + i * stepX

            val skinY = topPad + chartH * (1f - (skinData[i] - minY) / (maxY - minY))
            dotPaint.color = context.getColor(R.color.chart_blue_systolic)
            canvas.drawCircle(x, skinY, 5f, dotPaint)

            val ambY = topPad + chartH * (1f - (ambientData[i] - minY) / (maxY - minY))
            dotPaint.color = context.getColor(R.color.chart_red_diastolic)
            canvas.drawCircle(x, ambY, 5f, dotPaint)
        }

        // Legend
        val legendY = height - 8f
        dotPaint.color = context.getColor(R.color.chart_blue_systolic)
        canvas.drawCircle(leftPad + 10f, legendY - 8f, 6f, dotPaint)
        canvas.drawText("Skin", leftPad + 24f, legendY, legendTextPaint)

        dotPaint.color = context.getColor(R.color.chart_red_diastolic)
        canvas.drawCircle(leftPad + 100f, legendY - 8f, 6f, dotPaint)
        canvas.drawText("Ambient", leftPad + 114f, legendY, legendTextPaint)
    }
}
