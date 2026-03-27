package com.example.fitguard.data.processing

import android.location.Location
import android.os.Environment
import android.util.Log
import org.json.JSONObject
import com.example.fitguard.data.model.UserProfile
import com.example.fitguard.features.activitytracking.LocationPoint
import com.google.firebase.firestore.FirebaseFirestore
import java.io.File
import java.util.*

object CsvWriter {
    private const val TAG = "CsvWriter"
    private const val DIR_NAME = "FitGuard_Data"
    private const val FILE_NAME = "features.csv"
    private const val ROUTE_FILE_NAME = "route.csv"
    private const val ROUTE_SUMMARY_FILE_NAME = "route_summary.csv"

    private val HEADER = listOf(
        "user_id", "timestamp", "timestamp_end", "sequence_id",
        "mean_hr_bpm", "hr_std_bpm", "hr_min_bpm", "hr_max_bpm", "hr_range_bpm",
        "hr_slope_bpm_per_s", "nn_quality_ratio",
        "sdnn_ms", "rmssd_ms", "pnn50_pct", "mean_nn_ms", "cv_nn",
        "lf_power_ms2", "hf_power_ms2", "lf_hf_ratio", "total_power_ms2",
        "spo2_mean_pct", "spo2_min_pct", "spo2_std_pct",
        "accel_x_mean", "accel_y_mean", "accel_z_mean",
        "accel_x_var", "accel_y_var", "accel_z_var",
        "accel_mag_mean", "accel_mag_var", "accel_peak",
        "total_steps", "cadence_spm",
        "activity_label", "fatigue_level", "rpe_raw"
    ).joinToString(",")

    fun getOutputDir(userId: String = "", sessionDir: String = ""): File {
        val base = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            DIR_NAME
        )
        var dir = if (userId.isNotEmpty()) File(base, userId) else base
        if (sessionDir.isNotEmpty()) dir = File(dir, sessionDir)
        dir.mkdirs()
        return dir
    }

    fun writeFeatureVector(fv: FeatureVector, userId: String = "", sessionDir: String = "") {
        try {
            val dir = getOutputDir(userId, sessionDir)
            val file = File(dir, FILE_NAME)
            val needsHeader = !file.exists() || file.length() == 0L

            val row = listOf(
                userId,
                fv.timestamp.toString(),
                fv.timestampEnd.toString(),
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

    fun writeFeatureJsonl(fv: FeatureVector, userId: String = "", sessionDir: String = "") {
        try {
            val dir = getOutputDir(userId, sessionDir)
            val file = File(dir, "features.jsonl")
            val json = JSONObject().apply {
                put("timestamp", fv.timestamp)
                put("sequence_id", fv.sequenceId)
                put("mean_hr_bpm", fv.ppg.meanHrBpm)
                put("hr_std_bpm", fv.ppg.hrStdBpm)
                put("hr_min_bpm", fv.ppg.hrMinBpm)
                put("hr_max_bpm", fv.ppg.hrMaxBpm)
                put("hr_range_bpm", fv.ppg.hrRangeBpm)
                put("hr_slope_bpm_per_s", fv.ppg.hrSlopeBpmPerS)
                put("nn_quality_ratio", fv.ppg.nnQualityRatio)
                put("sdnn_ms", fv.ppg.sdnnMs)
                put("rmssd_ms", fv.ppg.rmssdMs)
                put("pnn50_pct", fv.ppg.pnn50Pct)
                put("mean_nn_ms", fv.ppg.meanNnMs)
                put("cv_nn", fv.ppg.cvNn)
                put("lf_power_ms2", fv.ppg.lfPowerMs2)
                put("hf_power_ms2", fv.ppg.hfPowerMs2)
                put("lf_hf_ratio", fv.ppg.lfHfRatio)
                put("total_power_ms2", fv.ppg.totalPowerMs2)
                put("spo2_mean_pct", fv.ppg.spo2MeanPct)
                put("spo2_min_pct", fv.ppg.spo2MinPct)
                put("spo2_std_pct", fv.ppg.spo2StdPct)
                put("accel_x_mean", fv.accelXMean)
                put("accel_y_mean", fv.accelYMean)
                put("accel_z_mean", fv.accelZMean)
                put("accel_x_var", fv.accelXVar)
                put("accel_y_var", fv.accelYVar)
                put("accel_z_var", fv.accelZVar)
                put("accel_mag_mean", fv.accelMagMean)
                put("accel_mag_var", fv.accelMagVar)
                put("accel_peak", fv.accelPeak)
                put("total_steps", fv.totalSteps)
                put("cadence_spm", fv.cadenceSpm)
                put("activity_label", fv.activityLabel)
                put("fatigue_level", fv.fatigueLevel)
                put("rpe_raw", fv.rpeRaw)
            }
            file.appendText(json.toString() + "\n")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write features JSONL: ${e.message}", e)
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
        "timestampEnd" to timestampEnd,
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
        "totalSteps" to totalSteps,
        "cadenceSpm" to cadenceSpm,
        "activityLabel" to activityLabel,
        "fatigueLevel" to fatigueLevel,
        "rpeRaw" to rpeRaw
    )

    fun writeRouteCsv(points: List<LocationPoint>, userId: String = "", sessionDir: String = "") {
        try {
            val dir = getOutputDir(userId, sessionDir)
            val file = File(dir, ROUTE_FILE_NAME)
            val header = "latitude,longitude,timestamp_ms,cumulative_distance_m"

            val sb = StringBuilder()
            sb.appendLine(header)
            var cumDist = 0.0
            for (i in points.indices) {
                val p = points[i]
                if (i > 0) {
                    val prev = points[i - 1]
                    val results = FloatArray(1)
                    Location.distanceBetween(prev.lat, prev.lng, p.lat, p.lng, results)
                    cumDist += results[0]
                }
                sb.appendLine("${fmt(p.lat)},${fmt(p.lng)},${p.timeMs},${fmt(cumDist)}")
            }
            file.writeText(sb.toString())
            Log.d(TAG, "Route CSV written to ${file.absolutePath} (${points.size} points)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write route CSV: ${e.message}", e)
        }
    }

    fun writeRouteSummary(
        totalDistanceM: Float,
        avgPaceMinPerKm: Double,
        durationMs: Long,
        pointCount: Int,
        userId: String = "",
        sessionDir: String = ""
    ) {
        try {
            val dir = getOutputDir(userId, sessionDir)
            val file = File(dir, ROUTE_SUMMARY_FILE_NAME)
            val header = "total_distance_m,avg_pace_min_per_km,duration_ms,point_count"
            val row = "${fmt(totalDistanceM.toDouble())},${fmt(avgPaceMinPerKm)},${durationMs},${pointCount}"
            file.writeText("$header\n$row\n")
            Log.d(TAG, "Route summary written to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write route summary: ${e.message}", e)
        }
    }

    fun writeProfileCsv(profile: UserProfile) {
        try {
            val dir = getOutputDir(profile.uid)
            val file = File(dir, "profile.csv")

            val heightM = profile.heightCm / 100.0
            val bmi = if (heightM > 0) profile.currentWeightKg / (heightM * heightM) else 0.0

            val header = listOf(
                "uid", "displayName", "email", "gender", "dateOfBirth",
                "heightCm", "currentWeightKg", "targetWeightKg",
                "fitnessGoal", "fitnessLevel", "restingHeartRateBpm", "bmi",
                "caloriesGoal", "waterGoalGlasses", "sleepGoalHours", "activityGoalHours"
            ).joinToString(",")

            val row = listOf(
                csvEscape(profile.uid),
                csvEscape(profile.displayName),
                csvEscape(profile.email),
                csvEscape(profile.gender),
                csvEscape(profile.dateOfBirth),
                fmt(profile.heightCm.toDouble()),
                fmt(profile.currentWeightKg.toDouble()),
                fmt(profile.targetWeightKg.toDouble()),
                csvEscape(profile.fitnessGoal),
                csvEscape(profile.fitnessLevel),
                profile.restingHeartRateBpm.toString(),
                fmt(bmi),
                profile.caloriesGoal.toString(),
                profile.waterGoalGlasses.toString(),
                fmt(profile.sleepGoalHours.toDouble()),
                fmt(profile.activityGoalHours.toDouble())
            ).joinToString(",")

            file.writeText("$header\n$row\n")
            Log.d(TAG, "Profile CSV written to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write profile CSV: ${e.message}", e)
        }
    }

    private fun csvEscape(value: String): String {
        return if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }

    private fun fmt(value: Double): String = String.format(Locale.US, "%.4f", value)
}
