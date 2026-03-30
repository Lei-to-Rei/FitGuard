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
        "activity_label"
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
                fv.activityLabel
            ).joinToString(",")

            val content = buildString {
                if (needsHeader) appendLine(HEADER)
                appendLine(row)
            }
            file.appendText(content)

            Log.d(TAG, "Feature vector written to ${file.absolutePath}")

            // Backup to Firestore under session-scoped path
            if (userId.isNotEmpty() && sessionDir.isNotEmpty()) {
                pushToFirestore(fv, userId, sessionDir)
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
            }
            file.appendText(json.toString() + "\n")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write features JSONL: ${e.message}", e)
        }
    }

    private fun pushToFirestore(fv: FeatureVector, userId: String, sessionDir: String) {
        FirebaseFirestore.getInstance()
            .collection("users").document(userId)
            .collection("workout_history").document(sessionDir)
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
        "activityLabel" to activityLabel
    )

    fun pushSessionMetadata(
        userId: String,
        sessionDir: String,
        activityType: String,
        startTimeMillis: Long,
        durationMillis: Long,
        totalDistanceMeters: Float,
        avgPaceMinPerKm: Double,
        pointCount: Int
    ) {
        if (userId.isEmpty() || sessionDir.isEmpty()) return
        FirebaseFirestore.getInstance()
            .collection("users").document(userId)
            .collection("workout_history").document(sessionDir)
            .set(mapOf(
                "activityType" to activityType,
                "startTimeMillis" to startTimeMillis,
                "durationMillis" to durationMillis,
                "totalDistanceMeters" to totalDistanceMeters,
                "avgPaceMinPerKm" to avgPaceMinPerKm,
                "pointCount" to pointCount,
                "sessionDirName" to sessionDir
            ))
            .addOnFailureListener { e ->
                Log.e(TAG, "Firestore session metadata failed: ${e.message}", e)
            }
    }

    fun pushRouteToFirestore(points: List<LocationPoint>, userId: String, sessionDir: String) {
        if (userId.isEmpty() || sessionDir.isEmpty() || points.isEmpty()) return
        val db = FirebaseFirestore.getInstance()
        val routeRef = db.collection("users").document(userId)
            .collection("workout_history").document(sessionDir)
            .collection("route_points")

        var cumDist = 0.0
        points.chunked(500).forEachIndexed { chunkIdx, chunk ->
            val batch = db.batch()
            chunk.forEachIndexed { i, p ->
                val idx = chunkIdx * 500 + i
                if (idx > 0) {
                    val prev = points[idx - 1]
                    val results = FloatArray(1)
                    Location.distanceBetween(prev.lat, prev.lng, p.lat, p.lng, results)
                    cumDist += results[0]
                }
                batch.set(
                    routeRef.document(idx.toString().padStart(6, '0')),
                    mapOf(
                        "latitude" to p.lat,
                        "longitude" to p.lng,
                        "timestamp_ms" to p.timeMs,
                        "cumulative_distance_m" to cumDist,
                        "index" to idx
                    )
                )
            }
            batch.commit().addOnFailureListener { e ->
                Log.e(TAG, "Firestore route batch failed: ${e.message}", e)
            }
        }
    }

    private fun pushFatigueHistoryToFirestore(row: FatigueHistoryRow, userId: String, sessionDir: String) {
        FirebaseFirestore.getInstance()
            .collection("users").document(userId)
            .collection("workout_history").document(sessionDir)
            .collection("fatigue_history").document(row.timestamp.toString())
            .set(mapOf(
                "timestamp" to row.timestamp,
                "fatiguePercent" to row.fatiguePercent,
                "globalPLow" to (row.global?.pLow ?: 0f),
                "globalPHigh" to (row.global?.pHigh ?: 0f),
                "globalLevel" to (row.global?.level ?: ""),
                "globalLevelIndex" to (row.global?.levelIndex ?: -1),
                "extPLow" to (row.external?.pLow ?: 0f),
                "extPHigh" to (row.external?.pHigh ?: 0f),
                "extLevel" to (row.external?.level ?: ""),
                "extLevelIndex" to (row.external?.levelIndex ?: -1),
                "ondevPLow" to (row.onDevice?.pLow ?: 0f),
                "ondevPHigh" to (row.onDevice?.pHigh ?: 0f),
                "ondevLevel" to (row.onDevice?.level ?: ""),
                "ondevLevelIndex" to (row.onDevice?.levelIndex ?: -1),
                "currentHr" to row.currentHr,
                "currentRmssd" to row.currentRmssd,
                "baselineHr" to row.baselineHr,
                "baselineRmssd" to row.baselineRmssd
            ))
            .addOnFailureListener { e ->
                Log.e(TAG, "Firestore fatigue history failed: ${e.message}", e)
            }
    }

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

    fun writeComparisonRow(
        timestamp: Long,
        windowIndex: Int,
        global: FatigueResult?,
        external: FatigueResult?,
        onDevice: FatigueResult?,
        userId: String,
        sessionDir: String
    ) {
        try {
            val dir = getOutputDir(userId, sessionDir)
            val file = File(dir, "scaler_comparison.csv")
            val needsHeader = !file.exists() || file.length() == 0L

            val header = "timestamp,window_index," +
                "global_pLow,global_pHigh,global_level,global_percent," +
                "external_pLow,external_pHigh,external_level,external_percent," +
                "ondevice_pLow,ondevice_pHigh,ondevice_level,ondevice_percent"

            fun resultCols(r: FatigueResult?): String {
                return if (r != null) {
                    "${fmt(r.pLow.toDouble())},${fmt(r.pHigh.toDouble())},${r.level},${r.percentDisplay}"
                } else {
                    ",,,"
                }
            }

            val row = "$timestamp,$windowIndex,${resultCols(global)},${resultCols(external)},${resultCols(onDevice)}"
            val content = buildString {
                if (needsHeader) appendLine(header)
                appendLine(row)
            }
            file.appendText(content)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write comparison CSV: ${e.message}", e)
        }
    }

    data class FatigueHistoryRow(
        val timestamp: Long,
        val fatiguePercent: Float,
        val global: FatigueResult?,
        val external: FatigueResult?,
        val onDevice: FatigueResult?,
        val currentHr: Double,
        val currentRmssd: Double,
        val baselineHr: Double,
        val baselineRmssd: Double
    )

    private const val HISTORY_FILE = "fatigue_history.csv"
    private const val HISTORY_HEADER = "timestamp,fatigue_percent," +
        "global_pLow,global_pHigh,global_level,global_levelIndex," +
        "ext_pLow,ext_pHigh,ext_level,ext_levelIndex," +
        "ondev_pLow,ondev_pHigh,ondev_level,ondev_levelIndex," +
        "current_hr,current_rmssd,baseline_hr,baseline_rmssd"

    fun writeFatigueHistoryRow(
        row: FatigueHistoryRow,
        userId: String,
        sessionDir: String
    ) {
        try {
            val dir = getOutputDir(userId, sessionDir)
            val file = File(dir, HISTORY_FILE)
            val needsHeader = !file.exists() || file.length() == 0L

            fun resCols(r: FatigueResult?): String {
                return if (r != null) {
                    "${fmt(r.pLow.toDouble())},${fmt(r.pHigh.toDouble())},${r.level},${r.levelIndex}"
                } else {
                    ",,,"
                }
            }

            val line = "${row.timestamp},${fmt(row.fatiguePercent.toDouble())}," +
                "${resCols(row.global)},${resCols(row.external)},${resCols(row.onDevice)}," +
                "${fmt(row.currentHr)},${fmt(row.currentRmssd)},${fmt(row.baselineHr)},${fmt(row.baselineRmssd)}"

            val content = buildString {
                if (needsHeader) appendLine(HISTORY_HEADER)
                appendLine(line)
            }
            file.appendText(content)

            // Backup to Firestore
            if (userId.isNotEmpty() && sessionDir.isNotEmpty()) {
                pushFatigueHistoryToFirestore(row, userId, sessionDir)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write fatigue history: ${e.message}", e)
        }
    }

    fun readFatigueHistory(userId: String, sessionDir: String): List<FatigueHistoryRow> {
        val dir = getOutputDir(userId, sessionDir)
        val file = File(dir, HISTORY_FILE)
        if (!file.exists() || file.length() == 0L) return emptyList()

        val rows = mutableListOf<FatigueHistoryRow>()
        try {
            val lines = file.readLines()
            // Skip header
            for (i in 1 until lines.size) {
                val cols = lines[i].split(",")
                if (cols.size < 18) continue

                fun parseResult(offset: Int): FatigueResult? {
                    val pLow = cols.getOrNull(offset)?.toFloatOrNull() ?: return null
                    val pHigh = cols.getOrNull(offset + 1)?.toFloatOrNull() ?: return null
                    val level = cols.getOrNull(offset + 2)?.takeIf { it.isNotEmpty() } ?: return null
                    val levelIndex = cols.getOrNull(offset + 3)?.toIntOrNull() ?: return null
                    val percentDisplay = (pHigh * 100).toInt().coerceIn(0, 100)
                    return FatigueResult(pLow, pHigh, level, levelIndex, percentDisplay)
                }

                rows.add(FatigueHistoryRow(
                    timestamp = cols[0].toLongOrNull() ?: continue,
                    fatiguePercent = cols[1].toFloatOrNull() ?: continue,
                    global = parseResult(2),
                    external = parseResult(6),
                    onDevice = parseResult(10),
                    currentHr = cols.getOrNull(14)?.toDoubleOrNull() ?: 0.0,
                    currentRmssd = cols.getOrNull(15)?.toDoubleOrNull() ?: 0.0,
                    baselineHr = cols.getOrNull(16)?.toDoubleOrNull() ?: 0.0,
                    baselineRmssd = cols.getOrNull(17)?.toDoubleOrNull() ?: 0.0
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read fatigue history: ${e.message}", e)
        }
        return rows
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
