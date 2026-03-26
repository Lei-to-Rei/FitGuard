package com.example.fitguard.data.processing

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Calculates stress score from HR tracker IBI (inter-beat interval) data.
 * Uses RMSSD (Root Mean Square of Successive Differences) mapped to a 0-100 stress scale.
 * Does NOT depend on PpgProcessor — works purely from HR tracker IBI values.
 */
class StressCalculator {
    private val ibiBuffer = mutableListOf<Int>()
    private val ibiTimestamps = mutableListOf<Long>()

    companion object {
        private const val WINDOW_MS = 60_000L  // 60-second sliding window
        private const val MIN_IBIS = 10        // Minimum IBIs for meaningful RMSSD
        private const val MIN_IBI_MS = 300     // Filter: min valid IBI (300ms = 200bpm)
        private const val MAX_IBI_MS = 2000    // Filter: max valid IBI (2000ms = 30bpm)
        private const val RMSSD_MIN = 10.0     // ln(10) maps to stress ~100
        private const val RMSSD_MAX = 100.0    // ln(100) maps to stress ~0
    }

    fun addIbiValues(ibis: List<Int>, timestamp: Long) {
        for (ibi in ibis) {
            if (ibi in MIN_IBI_MS..MAX_IBI_MS) {
                ibiBuffer.add(ibi)
                ibiTimestamps.add(timestamp)
            }
        }
        trimToWindow()
    }

    private fun trimToWindow() {
        if (ibiTimestamps.isEmpty()) return
        val cutoff = ibiTimestamps.last() - WINDOW_MS
        while (ibiTimestamps.isNotEmpty() && ibiTimestamps.first() < cutoff) {
            ibiTimestamps.removeAt(0)
            ibiBuffer.removeAt(0)
        }
    }

    fun getRmssd(): Float? {
        if (ibiBuffer.size < MIN_IBIS) return null

        var sumSquaredDiffs = 0.0
        var count = 0
        for (i in 1 until ibiBuffer.size) {
            val diff = (ibiBuffer[i] - ibiBuffer[i - 1]).toDouble()
            sumSquaredDiffs += diff * diff
            count++
        }
        if (count == 0) return null
        return sqrt(sumSquaredDiffs / count).toFloat()
    }

    fun getStressScore(): Float? {
        val rmssd = getRmssd() ?: return null
        if (rmssd <= 0f) return 100f

        // Log-scale mapping: RMSSD 10ms → stress 100, RMSSD 100ms → stress 0
        val logRmssd = ln(rmssd.toDouble().coerceIn(RMSSD_MIN, RMSSD_MAX))
        val logMin = ln(RMSSD_MIN)
        val logMax = ln(RMSSD_MAX)
        val normalized = (logRmssd - logMin) / (logMax - logMin) // 0 to 1
        return (100f - (normalized * 100f).toFloat()).coerceIn(0f, 100f)
    }

    fun getStressLabel(score: Float): String {
        return when {
            score < 25f -> "Relaxed"
            score < 50f -> "Normal"
            score < 75f -> "Elevated"
            else -> "High"
        }
    }

    fun clear() {
        ibiBuffer.clear()
        ibiTimestamps.clear()
    }
}
