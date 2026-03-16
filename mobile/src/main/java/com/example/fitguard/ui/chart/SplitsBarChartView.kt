package com.example.fitguard.ui.chart

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.example.fitguard.data.repository.SplitData
import java.util.Locale

class SplitsBarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var splits: List<SplitData> = emptyList()

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50")
        style = Paint.Style.FILL
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#333333")
        textSize = 32f
        textAlign = Paint.Align.RIGHT
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#333333")
        textSize = 28f
        textAlign = Paint.Align.LEFT
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E0E0")
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    fun setData(splits: List<SplitData>) {
        this.splits = splits
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val desiredHeight = if (splits.isEmpty()) 100 else (splits.size * 60 + 40)
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (splits.isEmpty()) return

        val leftPad = 80f
        val rightPad = 100f
        val topPad = 10f
        val barHeight = 32f
        val rowHeight = 52f

        val maxPace = splits.maxOf { it.paceMinPerKm }.coerceAtLeast(1.0)
        val chartWidth = width - leftPad - rightPad

        splits.forEachIndexed { i, split ->
            val y = topPad + i * rowHeight
            val barWidth = (split.paceMinPerKm / maxPace * chartWidth).toFloat().coerceAtLeast(4f)

            // Label
            canvas.drawText("Km ${split.kmIndex}", leftPad - 10f, y + barHeight - 6f, labelPaint)

            // Bar
            canvas.drawRoundRect(leftPad, y, leftPad + barWidth, y + barHeight, 6f, 6f, barPaint)

            // Value
            val min = split.paceMinPerKm.toInt()
            val sec = ((split.paceMinPerKm - min) * 60).toInt()
            val paceText = String.format(Locale.US, "%d:%02d", min, sec)
            canvas.drawText(paceText, leftPad + barWidth + 8f, y + barHeight - 6f, valuePaint)
        }
    }
}
