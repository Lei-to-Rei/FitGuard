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
        "mean_hr_bpm", "sdnn_ms", "rmssd_ms", "pnn20_pct", "pnn50_pct", "sdsd_ms",
        "spo2_pct", "spo2_hr_bpm", "skin_obj_temp", "skin_amb_temp"
    ).joinToString(",")

    private fun getOutputDir(): File {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            DIR_NAME
        )
        dir.mkdirs()
        return dir
    }

    fun writeSequenceData(result: HrvResult, samples: List<PpgProcessedSample>,
                          spo2: SpO2Sample?, skinTemp: SkinTempSample?) {
        try {
            val dir = getOutputDir()
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val file = File(dir, "PPG_data_$dateStr.csv")
            val needsHeader = !file.exists() || file.length() == 0L

            val sb = StringBuilder()
            if (needsHeader) sb.appendLine(HEADER)

            val spo2Part = if (spo2 != null) "${spo2.spo2},${spo2.heartRate}" else ","
            val skinTempPart = if (skinTemp != null) {
                "${String.format(Locale.US, "%.2f", skinTemp.objectTemp)}," +
                    String.format(Locale.US, "%.2f", skinTemp.ambientTemp)
            } else ","

            val lastIndex = samples.lastIndex
            for ((i, s) in samples.withIndex()) {
                val ppgPart = "${s.timestamp},${s.sequenceId},${s.rawGreen}," +
                    String.format(Locale.US, "%.4f", s.filteredGreen)
                if (i == lastIndex) {
                    // Last signal row: append HRV + SpO2 + SkinTemp results
                    sb.appendLine("$ppgPart,${
                        String.format(Locale.US, "%.1f", result.durationSeconds)},${
                        result.totalSamples},${result.peaksDetected},${result.nnIntervalsUsed},${
                        String.format(Locale.US, "%.1f", result.meanHrBpm)},${
                        String.format(Locale.US, "%.2f", result.sdnnMs)},${
                        String.format(Locale.US, "%.2f", result.rmssdMs)},${
                        String.format(Locale.US, "%.1f", result.pnn20Pct)},${
                        String.format(Locale.US, "%.1f", result.pnn50Pct)},${
                        String.format(Locale.US, "%.2f", result.sdsdMs)},$spo2Part,$skinTempPart")
                } else {
                    sb.appendLine("$ppgPart,,,,,,,,,,,,,,")
                }
            }

            file.appendText(sb.toString())

            Log.d(TAG, "Sequence data written (${samples.size} rows, HRV+SpO2+SkinTemp on last row) to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write sequence CSV: ${e.message}", e)
        }
    }
}
