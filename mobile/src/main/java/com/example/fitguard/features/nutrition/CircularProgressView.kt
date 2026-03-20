package com.example.fitguard.features.nutrition

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class CircularProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var progress = 0f
    private var centerText = "0"
    private var subText = "of 0"

    private val strokeWidth = 24f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        this.strokeWidth = this@CircularProgressView.strokeWidth
        color = Color.parseColor("#E8E8E8")
        strokeCap = Paint.Cap.ROUND
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        this.strokeWidth = this@CircularProgressView.strokeWidth
        color = Color.parseColor("#6EDB34")
        strokeCap = Paint.Cap.ROUND
    }

    private val centerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A1A1A")
        textSize = 48f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#999999")
        textSize = 24f
        textAlign = Paint.Align.CENTER
    }

    private val arcRect = RectF()

    fun setProgress(consumed: Int, goal: Int) {
        progress = if (goal > 0) (consumed.toFloat() / goal).coerceAtMost(1f) else 0f
        centerText = "%,d".format(consumed)
        subText = "of %,d".format(goal)

        if (consumed > goal) {
            progressPaint.color = Color.parseColor("#F44336")
        } else if (consumed > goal * 0.85) {
            progressPaint.color = Color.parseColor("#FF9800")
        } else {
            progressPaint.color = Color.parseColor("#6EDB34")
        }

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val radius = (minOf(width, height) / 2f) - strokeWidth

        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // Background circle
        canvas.drawArc(arcRect, 0f, 360f, false, bgPaint)

        // Progress arc
        val sweepAngle = progress * 360f
        canvas.drawArc(arcRect, -90f, sweepAngle, false, progressPaint)

        // Center text
        val textY = cy - 4f
        canvas.drawText(centerText, cx, textY, centerTextPaint)

        // Sub text
        canvas.drawText(subText, cx, textY + 32f, subTextPaint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredSize = 400
        val width = resolveSize(desiredSize, widthMeasureSpec)
        val height = resolveSize(desiredSize, heightMeasureSpec)
        val size = minOf(width, height)
        setMeasuredDimension(size, size)
    }
}
