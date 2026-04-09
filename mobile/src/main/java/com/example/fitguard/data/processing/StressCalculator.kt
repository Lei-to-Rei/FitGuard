package com.example.fitguard.data.processing

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Accumulates IBI (Inter-Beat Interval) data from the HR sensor
 * and calculates stress via RMSSD (Root Mean Square of Successive Differences).
 *
 * RMSSD reflects parasympathetic (vagal) tone:
 *   - High RMSSD  → relaxed  → low stress
 *   - Low  RMSSD  → tense    → high stress
 */
class StressCalculator(private val context: Context) {

    companion object {
        private const val TAG = "StressCalculator"
        const val ACTION_STRESS_RESULT = "com.example.fitguard.STRESS_RESULT"
        const val MIN_IBI_COUNT = 30       // minimum IBIs for a valid calculation
        const val PREFS_NAME = "stress_calculator"

        // RMSSD → stress mapping boundaries (milliseconds)
        private const val RMSSD_LOW_STRESS = 80.0   // RMSSD >= 80 → stress ~0
        private const val RMSSD_HIGH_STRESS = 15.0   // RMSSD <= 15 → stress ~100
    }

    private val ibiValues = mutableListOf<Int>()
    @Volatile var isCollecting = false
        private set

    val ibiCount: Int get() = ibiValues.size
    val hasEnoughData: Boolean get() = ibiValues.size >= MIN_IBI_COUNT

    fun startCollection() {
        ibiValues.clear()
        isCollecting = true
        Log.d(TAG, "IBI collection started")
    }

    fun stopCollection() {
        isCollecting = false
        Log.d(TAG, "IBI collection stopped with ${ibiValues.size} IBIs")
    }

    /**
     * Feed IBI values from a HeartRate reading.
     * Only accepts valid IBIs (status == 0 in ibiStatusList means valid).
     */
    fun addIbiValues(ibiList: List<Int>, ibiStatusList: List<Int>) {
        if (!isCollecting) return
        for (i in ibiList.indices) {
            val ibi = ibiList[i]
            val status = ibiStatusList.getOrNull(i) ?: -1
            // status 0 = valid IBI
            if (status == 0 && ibi in 300..2000) {
                ibiValues.add(ibi)
            }
        }
    }

    /**
     * Calculate stress score from accumulated IBI data.
     * Returns null if not enough data.
     */
    fun calculateStress(): StressResult? {
        if (ibiValues.size < MIN_IBI_COUNT) {
            Log.w(TAG, "Not enough IBI data: ${ibiValues.size}/$MIN_IBI_COUNT")
            return null
        }

        val rmssd = calculateRmssd(ibiValues)
        if (rmssd == null) {
            Log.w(TAG, "RMSSD calculation failed")
            return null
        }

        val meanIbi = ibiValues.average()
        val meanHr = 60000.0 / meanIbi

        val score = rmssdToStressScore(rmssd)
        val label = when {
            score < 33f  -> "Low"
            score < 66f  -> "Moderate"
            else         -> "High"
        }

        Log.d(TAG, "Stress calculated: RMSSD=%.1f score=%.0f label=%s meanHR=%.0f ibiCount=%d"
            .format(rmssd, score, label, meanHr, ibiValues.size))

        val result = StressResult(score, label, rmssd.toFloat(), meanHr.toFloat(), ibiValues.size)
        saveResult(result)
        return result
    }

    private fun calculateRmssd(ibis: List<Int>): Double? {
        if (ibis.size < 2) return null
        var sumSquaredDiff = 0.0
        var count = 0
        for (i in 1 until ibis.size) {
            val diff = (ibis[i] - ibis[i - 1]).toDouble()
            sumSquaredDiff += diff * diff
            count++
        }
        if (count == 0) return null
        return sqrt(sumSquaredDiff / count)
    }

    /**
     * Maps RMSSD (ms) to a 0–100 stress score.
     * Higher RMSSD = lower stress, lower RMSSD = higher stress.
     */
    private fun rmssdToStressScore(rmssd: Double): Float {
        // Linear mapping: RMSSD_HIGH_STRESS(15ms)→100, RMSSD_LOW_STRESS(80ms)→0
        val normalized = (rmssd - RMSSD_HIGH_STRESS) / (RMSSD_LOW_STRESS - RMSSD_HIGH_STRESS)
        val score = (1.0 - normalized) * 100.0
        return max(0f, min(100f, score.toFloat()))
    }

    private fun saveResult(result: StressResult) {
        try {
            // Save to SharedPreferences for quick access
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putFloat("last_score", result.score)
                .putString("last_label", result.label)
                .putFloat("last_rmssd", result.rmssd)
                .putFloat("last_mean_hr", result.meanHr)
                .putInt("last_ibi_count", result.ibiCount)
                .putLong("last_timestamp", System.currentTimeMillis())
                .apply()

            // Save to daily JSONL file for history
            val userId = FirebaseAuth.getInstance().currentUser?.uid ?: ""
            val dateFolder = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val baseDir = CsvWriter.getOutputDir(userId, "")
            val dir = File(baseDir, dateFolder)
            dir.mkdirs()
            val json = JSONObject().apply {
                put("score", result.score)
                put("label", result.label)
                put("rmssd", result.rmssd)
                put("mean_hr", result.meanHr)
                put("ibi_count", result.ibiCount)
                put("timestamp", System.currentTimeMillis())
                put("ibi_values", JSONArray(ibiValues))
            }
            File(dir, "WatchStress.jsonl").appendText(json.toString() + "\n")
            Log.d(TAG, "Stress result saved")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save stress result: ${e.message}")
        }
    }

    fun reset() {
        ibiValues.clear()
        isCollecting = false
    }

    data class StressResult(
        val score: Float,
        val label: String,
        val rmssd: Float,
        val meanHr: Float,
        val ibiCount: Int
    )
}
