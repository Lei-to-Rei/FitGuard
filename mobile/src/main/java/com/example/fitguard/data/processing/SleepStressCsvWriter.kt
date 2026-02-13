package com.example.fitguard.data.processing

import android.os.Environment
import android.util.Log
import com.example.fitguard.features.sleep.SleepDetector
import com.example.fitguard.features.sleep.StressScorer
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class SleepStressCsvWriter {
    companion object {
        private const val TAG = "SleepStressCsvWriter"
        private const val DIR_NAME = "FitGuard_Data/Sleep_and_Stress_Data"

        private val HEADER = listOf(
            "timestamp", "sequence_id", "duration_seconds", "total_ppg_samples",
            "peaks_detected", "nn_intervals_used",
            "mean_hr_bpm", "sdnn_ms", "rmssd_ms", "pnn20_pct", "pnn50_pct", "sdsd_ms",
            "total_steps", "cadence_spm", "mean_accel_mag", "accel_variance", "peak_accel_mag",
            "stress_score", "stress_level", "sleep_state", "sleep_confidence"
        ).joinToString(",")
    }

    private val sleepDetector = SleepDetector()

    private fun getOutputDir(): File {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            DIR_NAME
        )
        dir.mkdirs()
        return dir
    }

    fun writeSequenceData(hrvResult: HrvResult, accelResult: AccelResult? = null) {
        try {
            val dir = getOutputDir()
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val file = File(dir, "SleepStress_data_$dateStr.csv")
            val needsHeader = !file.exists() || file.length() == 0L

            // Compute stress
            val stressResult = StressScorer.compute(
                hrvResult.rmssdMs, hrvResult.sdnnMs, hrvResult.meanHrBpm
            )

            // Compute sleep state
            val sleepResult = if (accelResult != null) {
                sleepDetector.update(
                    accelResult.accelVariance, hrvResult.meanHrBpm, hrvResult.rmssdMs
                )
            } else {
                sleepDetector.updateWithoutAccel(hrvResult.meanHrBpm, hrvResult.rmssdMs)
            }

            val sb = StringBuilder()
            if (needsHeader) sb.appendLine(HEADER)

            // Accel columns
            val accelPart = if (accelResult != null) {
                "${accelResult.totalSteps}," +
                    "${fmt1(accelResult.cadenceSpm)}," +
                    "${fmt2(accelResult.meanAccelMag)}," +
                    "${fmt4(accelResult.accelVariance)}," +
                    fmt2(accelResult.peakAccelMag)
            } else ",,,,"

            sb.appendLine(
                "${System.currentTimeMillis()},${hrvResult.sequenceId}," +
                    "${fmt1(hrvResult.durationSeconds)},${hrvResult.totalSamples}," +
                    "${hrvResult.peaksDetected},${hrvResult.nnIntervalsUsed}," +
                    "${fmt1(hrvResult.meanHrBpm)},${fmt2(hrvResult.sdnnMs)}," +
                    "${fmt2(hrvResult.rmssdMs)},${fmt1(hrvResult.pnn20Pct)}," +
                    "${fmt1(hrvResult.pnn50Pct)},${fmt2(hrvResult.sdsdMs)}," +
                    "$accelPart," +
                    "${stressResult.score},${stressResult.level}," +
                    "${sleepResult.state},${fmt2(sleepResult.confidence.toDouble())}"
            )

            file.appendText(sb.toString())

            Log.d(TAG, "Sleep/Stress summary written for ${hrvResult.sequenceId}: " +
                    "stress=${stressResult.score}(${stressResult.level}) " +
                    "sleep=${sleepResult.state}(${fmt2(sleepResult.confidence.toDouble())})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write sleep/stress CSV: ${e.message}", e)
        }
    }

    fun reset() {
        sleepDetector.reset()
    }

    private fun fmt1(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun fmt2(v: Double) = String.format(Locale.US, "%.2f", v)
    private fun fmt4(v: Double) = String.format(Locale.US, "%.4f", v)
}
