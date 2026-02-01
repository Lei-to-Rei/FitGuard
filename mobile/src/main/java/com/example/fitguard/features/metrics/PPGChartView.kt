package com.example.fitguard.features.metrics

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * Custom view for displaying PPG signal waveform with rolling window
 */
class PPGChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var data: DoubleArray = doubleArrayOf()
    private var windowSize: Int = 200 // Show last N samples (about 8 seconds at 25Hz)

    private val linePaint = Paint().apply {
        color = Color.parseColor("#4CAF50")
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val gridPaint = Paint().apply {
        color = Color.parseColor("#E0E0E0")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val axisPaint = Paint().apply {
        color = Color.GRAY
        textSize = 24f
        isAntiAlias = true
    }

    private val backgroundPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val path = Path()

    /**
     * Set data to display - will show only the most recent windowSize samples
     */
    fun setData(signal: DoubleArray) {
        data = signal
        invalidate()
    }

    /**
     * Set how many samples to display (rolling window size)
     * @param samples Number of samples to show (default 200)
     */
    fun setWindowSize(samples: Int) {
        windowSize = samples.coerceAtLeast(50)
        invalidate()
    }

    fun setColor(color: Int) {
        linePaint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        if (data.isEmpty()) {
            axisPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("No data", width / 2f, height / 2f, axisPaint)
            return
        }

        val padding = 50f
        val chartWidth = width - 2 * padding
        val chartHeight = height - 2 * padding

        // Get only the most recent samples (rolling window)
        val displayData = if (data.size > windowSize) {
            data.sliceArray((data.size - windowSize) until data.size)
        } else {
            data
        }

        // Draw grid
        for (i in 0..4) {
            val y = padding + i * chartHeight / 4
            canvas.drawLine(padding, y, width - padding, y, gridPaint)
        }

        for (i in 0..4) {
            val x = padding + i * chartWidth / 4
            canvas.drawLine(x, padding, x, height - padding, gridPaint)
        }

        // Normalize data based on visible window
        val min = displayData.minOrNull() ?: 0.0
        val max = displayData.maxOrNull() ?: 1.0
        val range = (max - min).coerceAtLeast(1.0)

        // Draw signal
        path.reset()
        val stepX = chartWidth / (displayData.size - 1).coerceAtLeast(1)

        displayData.forEachIndexed { index, value ->
            val x = padding + index * stepX
            val normalizedY = (value - min) / range
            val y = height - padding - normalizedY.toFloat() * chartHeight

            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        canvas.drawPath(path, linePaint)

        // Draw axis labels
        axisPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("Max: ${String.format("%.0f", max)}", padding + 10, padding + 20, axisPaint)
        canvas.drawText("Min: ${String.format("%.0f", min)}", padding + 10, height - padding - 10, axisPaint)

        // Draw sample count info
        axisPaint.textAlign = Paint.Align.RIGHT
        val totalSamples = data.size
        val showingSamples = displayData.size
        canvas.drawText("Showing: $showingSamples / $totalSamples", width - padding, padding + 20, axisPaint)
    }
}

/**
 * Custom view for displaying histograms
 */
class HistogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var bins = mutableListOf<Int>()
    private var binLabels = mutableListOf<String>()
    private var unit = ""

    private val barPaint = Paint().apply {
        color = Color.parseColor("#9C27B0")
        style = Paint.Style.FILL
    }

    private val textPaint = Paint().apply {
        color = Color.GRAY
        textSize = 20f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    private val gridPaint = Paint().apply {
        color = Color.parseColor("#E0E0E0")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val backgroundPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    fun setData(values: List<Double>, unit: String, numBins: Int = 10) {
        this.unit = unit

        if (values.isEmpty()) {
            bins.clear()
            binLabels.clear()
            invalidate()
            return
        }

        val min = values.minOrNull() ?: 0.0
        val max = values.maxOrNull() ?: 1.0
        val range = (max - min).coerceAtLeast(1.0)
        val binWidth = range / numBins

        bins = MutableList(numBins) { 0 }
        binLabels = MutableList(numBins) { i ->
            String.format("%.0f", min + i * binWidth + binWidth / 2)
        }

        values.forEach { value ->
            val binIndex = ((value - min) / binWidth).toInt().coerceIn(0, numBins - 1)
            bins[binIndex]++
        }

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        if (bins.isEmpty()) {
            canvas.drawText("No data", width / 2f, height / 2f, textPaint)
            return
        }

        val padding = 60f
        val chartWidth = width - 2 * padding
        val chartHeight = height - 2 * padding

        val maxBin = bins.maxOrNull() ?: 1
        val barWidth = chartWidth / bins.size * 0.8f
        val gap = chartWidth / bins.size * 0.2f

        // Draw grid lines
        for (i in 0..4) {
            val y = padding + i * chartHeight / 4
            canvas.drawLine(padding, y, width - padding, y, gridPaint)
        }

        // Draw bars
        bins.forEachIndexed { index, count ->
            val x = padding + index * (barWidth + gap) + gap / 2
            val barHeight = (count.toFloat() / maxBin) * chartHeight
            val top = height - padding - barHeight

            canvas.drawRect(x, top, x + barWidth, height - padding, barPaint)
        }

        // Draw labels (show every other label to prevent overlap)
        binLabels.forEachIndexed { index, label ->
            if (index % 2 == 0 || bins.size <= 5) {
                val x = padding + index * (barWidth + gap) + gap / 2 + barWidth / 2
                canvas.drawText(label, x, height - padding + 30, textPaint)
            }
        }

        // Draw unit label
        canvas.drawText(unit, width / 2f, height - 5f, textPaint)
    }
}

/**
 * Custom view for displaying line charts with time axis
 */
class LineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var data: List<Pair<Long, Float>> = emptyList()
    private var unit = ""

    private val linePaint = Paint().apply {
        color = Color.parseColor("#2196F3")
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val pointPaint = Paint().apply {
        color = Color.parseColor("#2196F3")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val gridPaint = Paint().apply {
        color = Color.parseColor("#E0E0E0")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val textPaint = Paint().apply {
        color = Color.GRAY
        textSize = 20f
        isAntiAlias = true
    }

    private val backgroundPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val path = Path()

    fun setData(points: List<Pair<Long, Float>>, unit: String, color: Int) {
        this.data = points.sortedBy { it.first }
        this.unit = unit
        linePaint.color = color
        pointPaint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        if (data.isEmpty()) {
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("No data", width / 2f, height / 2f, textPaint)
            return
        }

        val padding = 60f
        val chartWidth = width - 2 * padding
        val chartHeight = height - 2 * padding

        // Calculate ranges
        val minTime = data.first().first
        val maxTime = data.last().first
        val timeRange = (maxTime - minTime).coerceAtLeast(1L)

        val values = data.map { it.second }
        val minValue = values.minOrNull() ?: 0f
        val maxValue = values.maxOrNull() ?: 1f
        val valueRange = (maxValue - minValue).coerceAtLeast(1f)

        // Add some padding to value range
        val paddedMin = minValue - valueRange * 0.1f
        val paddedMax = maxValue + valueRange * 0.1f
        val paddedRange = paddedMax - paddedMin

        // Draw grid
        for (i in 0..4) {
            val y = padding + i * chartHeight / 4
            canvas.drawLine(padding, y, width - padding, y, gridPaint)

            // Y-axis labels
            val labelValue = paddedMax - i * paddedRange / 4
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(String.format("%.0f", labelValue), padding - 5, y + 5, textPaint)
        }

        // Draw line
        path.reset()

        data.forEachIndexed { index, (time, value) ->
            val x = padding + ((time - minTime).toFloat() / timeRange) * chartWidth
            val y = padding + ((paddedMax - value) / paddedRange) * chartHeight

            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        canvas.drawPath(path, linePaint)

        // Draw points (if not too many)
        if (data.size <= 50) {
            data.forEach { (time, value) ->
                val x = padding + ((time - minTime).toFloat() / timeRange) * chartWidth
                val y = padding + ((paddedMax - value) / paddedRange) * chartHeight
                canvas.drawCircle(x, y, 4f, pointPaint)
            }
        }

        // Draw unit label
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(unit, width - 30f, padding - 10, textPaint)

        // Draw time labels
        val timeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(timeFormat.format(java.util.Date(minTime)), padding, height - padding + 30, textPaint)
        canvas.drawText(timeFormat.format(java.util.Date(maxTime)), width - padding, height - padding + 30, textPaint)
    }
}