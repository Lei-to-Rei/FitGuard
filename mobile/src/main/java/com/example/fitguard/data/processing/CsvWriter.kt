package com.example.fitguard.data.processing

import android.os.Environment
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
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

    fun getOutputDir(userId: String = ""): File {
        val base = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            DIR_NAME
        )
        val dir = if (userId.isNotEmpty()) File(base, userId) else base
        dir.mkdirs()
        return dir
    }

    fun writeFeatureVector(fv: FeatureVector, userId: String = "") {
        try {
            val dir = getOutputDir(userId)
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

            // Backup to Firestore
            if (userId.isNotEmpty()) {
                pushToFirestore(fv, userId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write features CSV: ${e.message}", e)
        }
    }

    private fun pushToFirestore(fv: FeatureVector, userId: String) {
        FirebaseFirestore.getInstance()
            .collection("users").document(userId)
            .collection("feature_vectors").document(fv.sequenceId)
            .set(fv.toFirestoreMap())
            .addOnFailureListener { e ->
                Log.e(TAG, "Firestore backup failed for ${fv.sequenceId}: ${e.message}", e)
            }
    }

    private fun FeatureVector.toFirestoreMap(): Map<String, Any> = mapOf(
        "timestamp" to timestamp,
        "sequenceId" to sequenceId,
        "meanHrBpm" to ppg.meanHrBpm,
        "hrStdBpm" to ppg.hrStdBpm,
        "hrMinBpm" to ppg.hrMinBpm,
        "hrMaxBpm" to ppg.hrMaxBpm,
        "hrRangeBpm" to ppg.hrRangeBpm,
        "hrSlopeBpmPerS" to ppg.hrSlopeBpmPerS,
        "nnQualityRatio" to ppg.nnQualityRatio,
        "sdnnMs" to ppg.sdnnMs,
        "rmssdMs" to ppg.rmssdMs,
        "pnn50Pct" to ppg.pnn50Pct,
        "meanNnMs" to ppg.meanNnMs,
        "cvNn" to ppg.cvNn,
        "lfPowerMs2" to ppg.lfPowerMs2,
        "hfPowerMs2" to ppg.hfPowerMs2,
        "lfHfRatio" to ppg.lfHfRatio,
        "totalPowerMs2" to ppg.totalPowerMs2,
        "spo2MeanPct" to ppg.spo2MeanPct,
        "spo2MinPct" to ppg.spo2MinPct,
        "spo2StdPct" to ppg.spo2StdPct,
        "accelXMean" to accelXMean,
        "accelYMean" to accelYMean,
        "accelZMean" to accelZMean,
        "accelXVar" to accelXVar,
        "accelYVar" to accelYVar,
        "accelZVar" to accelZVar,
        "accelMagMean" to accelMagMean,
        "accelMagVar" to accelMagVar,
        "accelPeak" to accelPeak,
        "skinTempObj" to skinTempObj,
        "skinTempDelta" to skinTempDelta,
        "skinTempAmbient" to skinTempAmbient,
        "totalSteps" to totalSteps,
        "cadenceSpm" to cadenceSpm,
        "activityLabel" to activityLabel,
        "fatigueLevel" to fatigueLevel,
        "rpeRaw" to rpeRaw
    )

    private fun fmt(value: Double): String = String.format(Locale.US, "%.4f", value)
}
