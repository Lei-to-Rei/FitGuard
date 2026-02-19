package com.example.fitguard.data.processing

import android.os.Environment
import android.util.Log
import java.io.File
import java.util.*

object CsvWriter {
    private const val TAG = "CsvWriter"
    private const val DIR_NAME = "FitGuard_Data"
    private const val FILE_NAME = "features.csv"

    private val HEADER = listOf(
        "timestamp", "sequence_id",
        "mean_hr_bpm", "hr_std_bpm", "hr_min_bpm", "hr_max_bpm", "hr_range_bpm",
        "hr_slope_bpm_per_s", "nn_quality_ratio",
        "sdnn_ms", "rmssd_ms", "pnn50_pct", "mean_nn_ms", "cv_nn",
        "lf_power_ms2", "hf_power_ms2", "lf_hf_ratio", "total_power_ms2",
        "spo2_mean_pct", "spo2_min_pct", "spo2_std_pct",
        "accel_x_mean", "accel_y_mean", "accel_z_mean",
        "accel_x_var", "accel_y_var", "accel_z_var",
        "accel_mag_mean", "accel_mag_var", "accel_peak",
        "skin_temp_obj", "skin_temp_delta", "skin_temp_ambient",
        "total_steps", "cadence_spm",
        "activity_label", "fatigue_level", "rpe_raw"
    ).joinToString(",")

    private fun getOutputDir(): File {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            DIR_NAME
        )
        dir.mkdirs()
        return dir
    }

    fun writeFeatureVector(fv: FeatureVector) {
        try {
            val dir = getOutputDir()
            val file = File(dir, FILE_NAME)
            val needsHeader = !file.exists() || file.length() == 0L

            val row = listOf(
                fv.timestamp.toString(),
                fv.sequenceId,
                fmt(fv.ppg.meanHrBpm),
                fmt(fv.ppg.hrStdBpm),
                fmt(fv.ppg.hrMinBpm),
                fmt(fv.ppg.hrMaxBpm),
                fmt(fv.ppg.hrRangeBpm),
                fmt(fv.ppg.hrSlopeBpmPerS),
                fmt(fv.ppg.nnQualityRatio),
                fmt(fv.ppg.sdnnMs),
                fmt(fv.ppg.rmssdMs),
                fmt(fv.ppg.pnn50Pct),
                fmt(fv.ppg.meanNnMs),
                fmt(fv.ppg.cvNn),
                fmt(fv.ppg.lfPowerMs2),
                fmt(fv.ppg.hfPowerMs2),
                fmt(fv.ppg.lfHfRatio),
                fmt(fv.ppg.totalPowerMs2),
                fmt(fv.ppg.spo2MeanPct),
                fmt(fv.ppg.spo2MinPct),
                fmt(fv.ppg.spo2StdPct),
                fmt(fv.accelXMean),
                fmt(fv.accelYMean),
                fmt(fv.accelZMean),
                fmt(fv.accelXVar),
                fmt(fv.accelYVar),
                fmt(fv.accelZVar),
                fmt(fv.accelMagMean),
                fmt(fv.accelMagVar),
                fmt(fv.accelPeak),
                fmt(fv.skinTempObj),
                fmt(fv.skinTempDelta),
                fmt(fv.skinTempAmbient),
                fv.totalSteps.toString(),
                fmt(fv.cadenceSpm),
                fv.activityLabel,
                fv.fatigueLevel,
                if (fv.rpeRaw >= 0) fv.rpeRaw.toString() else ""
            ).joinToString(",")

            val content = buildString {
                if (needsHeader) appendLine(HEADER)
                appendLine(row)
            }
            file.appendText(content)

            Log.d(TAG, "Feature vector written to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write features CSV: ${e.message}", e)
        }
    }

    private fun fmt(value: Double): String = String.format(Locale.US, "%.4f", value)
}
