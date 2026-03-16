package com.example.fitguard.ui.chart

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class RecoveryGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var recoveryPercent = 70f
    private var statusText = "Moderate"
    private var gaugeColor = Color.parseColor("#FF8C00")

    // Arc: starts at 135° (bottom-left), sweeps 270° clockwise (gap at bottom)
    private val startAngle = 135f
    private val sweepTotal = 270f

    private val bgArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E0E0")
        style = Paint.Style.STROKE
        strokeWidth = 22f
        strokeCap = Paint.Cap.ROUND
    }

    private val fgArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF8C00")
        style = Paint.Style.STROKE
        strokeWidth = 22f
        strokeCap = Paint.Cap.ROUND
    }

    private val percentTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A1A1A")
        textSize = 52f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val statusTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF8C00")
        textSize = 26f
        textAlign = Paint.Align.CENTER
    }

    fun setRecovery(percent: Float, status: String, color: Int = Color.parseColor("#FF8C00")) {
        recoveryPercent = percent.coerceIn(0f, 100f)
        statusText = status
        gaugeColor = color
        fgArcPaint.color = color
        statusTextPaint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val strokePad = 24f
        val size = minOf(width, height).toFloat() - strokePad * 2
        val left = (width - size) / 2f
        val top = (height - size) / 2f
        val oval = RectF(left, top, left + size, top + size)

        // Background arc
        canvas.drawArc(oval, startAngle, sweepTotal, false, bgArcPaint)

        // Foreground arc
        val sweep = sweepTotal * (recoveryPercent / 100f)
        canvas.drawArc(oval, startAngle, sweep, false, fgArcPaint)

        // Centered text
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawText("${recoveryPercent.toInt()}%", cx, cy + 16f, percentTextPaint)
        canvas.drawText(statusText, cx, cy + 46f, statusTextPaint)
    }
}
