package com.example.fitguard.data.processing

import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object HrvCsvWriter {
    private const val TAG = "HrvCsvWriter"
    private const val DIR_NAME = "FitGuard_Data"

    private val HRV_HEADER = listOf(
        "timestamp", "sequence_id", "duration_seconds", "total_ppg_samples",
        "peaks_detected", "nn_intervals_used", "mean_hr_bpm",
        "sdnn_ms", "rmssd_ms", "pnn20_pct", "pnn50_pct", "sdsd_ms"
    ).joinToString(",")

    private val PPG_HEADER = listOf(
        "timestamp", "sequence_id", "ppg_green_raw", "ppg_green_filtered"
    ).joinToString(",")

    private fun getOutputDir(): File {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            DIR_NAME
        )
        dir.mkdirs()
        return dir
    }

    fun writeHrvResult(result: HrvResult) {
        try {
            val dir = getOutputDir()
            val file = File(dir, "hrv_results.csv")
            val needsHeader = !file.exists() || file.length() == 0L

            val row = listOf(
                System.currentTimeMillis().toString(),
                result.sequenceId,
                String.format(Locale.US, "%.1f", result.durationSeconds),
                result.totalSamples.toString(),
                result.peaksDetected.toString(),
                result.nnIntervalsUsed.toString(),
                String.format(Locale.US, "%.1f", result.meanHrBpm),
                String.format(Locale.US, "%.2f", result.sdnnMs),
                String.format(Locale.US, "%.2f", result.rmssdMs),
                String.format(Locale.US, "%.1f", result.pnn20Pct),
                String.format(Locale.US, "%.1f", result.pnn50Pct),
                String.format(Locale.US, "%.2f", result.sdsdMs)
            ).joinToString(",")

            val content = buildString {
                if (needsHeader) appendLine(HRV_HEADER)
                appendLine(row)
            }
            file.appendText(content)

            Log.d(TAG, "HRV result written to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write HRV CSV: ${e.message}", e)
        }
    }

    fun writePpgProcessed(samples: List<PpgProcessedSample>) {
        if (samples.isEmpty()) return
        try {
            val dir = getOutputDir()
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val file = File(dir, "PPG_processed_$dateStr.csv")
            val needsHeader = !file.exists() || file.length() == 0L

            val sb = StringBuilder()
            if (needsHeader) sb.appendLine(PPG_HEADER)
            for (s in samples) {
                sb.appendLine("${s.timestamp},${s.sequenceId},${s.rawGreen},${String.format(Locale.US, "%.4f", s.filteredGreen)}")
            }
            file.appendText(sb.toString())

            Log.d(TAG, "PPG processed data written (${samples.size} samples) to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write PPG CSV: ${e.message}", e)
        }
    }
}
