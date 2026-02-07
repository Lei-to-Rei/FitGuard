package com.example.fitguard.data.processing

import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object HrvCsvWriter {
    private const val TAG = "HrvCsvWriter"
    private const val DIR_NAME = "FitGuard_Data"

    private val HEADER = listOf(
        "timestamp", "sequence_id", "ppg_green_raw", "ppg_green_filtered",
        "duration_seconds", "total_ppg_samples", "peaks_detected", "nn_intervals_used",
        "mean_hr_bpm", "sdnn_ms", "rmssd_ms", "pnn20_pct", "pnn50_pct", "sdsd_ms"
    ).joinToString(",")

    private fun getOutputDir(): File {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            DIR_NAME
        )
        dir.mkdirs()
        return dir
    }

    fun writeSequenceData(result: HrvResult, samples: List<PpgProcessedSample>) {
        try {
            val dir = getOutputDir()
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val file = File(dir, "PPG_data_$dateStr.csv")
            val needsHeader = !file.exists() || file.length() == 0L

            val sb = StringBuilder()
            if (needsHeader) sb.appendLine(HEADER)

            // PPG signal rows (HRV columns left blank)
            for (s in samples) {
                sb.appendLine("${s.timestamp},${s.sequenceId},${s.rawGreen}," +
                    "${String.format(Locale.US, "%.4f", s.filteredGreen)},,,,,,,,,,")
            }

            // HRV summary row (PPG columns left blank)
            sb.appendLine("${System.currentTimeMillis()},${result.sequenceId},,,${
                String.format(Locale.US, "%.1f", result.durationSeconds)},${
                result.totalSamples},${result.peaksDetected},${result.nnIntervalsUsed},${
                String.format(Locale.US, "%.1f", result.meanHrBpm)},${
                String.format(Locale.US, "%.2f", result.sdnnMs)},${
                String.format(Locale.US, "%.2f", result.rmssdMs)},${
                String.format(Locale.US, "%.1f", result.pnn20Pct)},${
                String.format(Locale.US, "%.1f", result.pnn50Pct)},${
                String.format(Locale.US, "%.2f", result.sdsdMs)}")

            file.appendText(sb.toString())

            Log.d(TAG, "Sequence data written (${samples.size} PPG rows + 1 HRV row) to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write sequence CSV: ${e.message}", e)
        }
    }
}
