package com.example.fitguard.ui.chart

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class StressGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var stressValue = 52f // 0–100

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val thumbStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#BBBBBB")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#999999")
        textSize = 26f
    }

    fun setStressValue(value: Float) {
        stressValue = value.coerceIn(0f, 100f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val leftPad = 16f
        val rightPad = 16f
        val trackW = width.toFloat() - leftPad - rightPad
        val trackY = height / 2f - 14f
        val trackHeight = 12f
        val thumbRadius = 14f

        // Draw gradient track
        val trackRect = RectF(leftPad, trackY - trackHeight / 2, leftPad + trackW, trackY + trackHeight / 2)
        trackPaint.shader = LinearGradient(
            leftPad, 0f, leftPad + trackW, 0f,
            intArrayOf(
                Color.HSVToColor(floatArrayOf(120f, 0.85f, 0.82f)),
                Color.HSVToColor(floatArrayOf(60f, 0.85f, 0.82f)),
                Color.HSVToColor(floatArrayOf(0f, 0.85f, 0.82f))
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(trackRect, trackHeight / 2, trackHeight / 2, trackPaint)

        // Draw thumb at stress value position
        val thumbX = leftPad + (stressValue / 100f) * trackW
        canvas.drawCircle(thumbX, trackY, thumbRadius, thumbPaint)
        canvas.drawCircle(thumbX, trackY, thumbRadius, thumbStrokePaint)

        // Labels
        val labelY = height.toFloat() - 6f
        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("Relaxed", leftPad, labelY, labelPaint)

        labelPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("Average", width / 2f, labelY, labelPaint)

        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("High", width.toFloat() - rightPad, labelY, labelPaint)
    }
}
