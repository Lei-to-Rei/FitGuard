package com.example.fitguard.data.repository

import android.util.Log
import com.example.fitguard.data.model.ActivityHistoryItem
import com.example.fitguard.data.processing.CsvWriter
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

object ActivityHistoryRepository {
    private const val TAG = "ActivityHistoryRepo"
    private val DIR_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US)

    fun loadSessions(userId: String): List<ActivityHistoryItem> {
        val userDir = CsvWriter.getOutputDir(userId)
        if (!userDir.exists() || !userDir.isDirectory) return emptyList()

        return userDir.listFiles()
            ?.filter { it.isDirectory && File(it, "features.csv").exists() }
            ?.mapNotNull { dir -> parseSessionDir(dir) }
            ?.sortedByDescending { it.startTimeMillis }
            ?: emptyList()
    }

    /**
     * Syncs workout history from Firestore to local storage.
     * Downloads session data and reconstructs local CSV files so the existing
     * local reading code works unchanged. Skips sessions that already exist locally.
     */
    suspend fun syncFromFirestore(userId: String) {
        try {
            val db = FirebaseFirestore.getInstance()
            val snapshot = db.collection("users").document(userId)
                .collection("workout_history")
                .get()
                .await()

            var synced = 0
            for (doc in snapshot.documents) {
                val sessionDir = doc.id
                val localDir = CsvWriter.getOutputDir(userId, sessionDir)

                // Skip if local data already exists
                if (File(localDir, "features.csv").exists()) continue

                syncFeatureVectors(db, userId, sessionDir, localDir)
                syncRouteData(db, userId, sessionDir, localDir, doc)
                syncFatigueHistory(db, userId, sessionDir, localDir)
                synced++
            }
            if (synced > 0) {
                Log.d(TAG, "Synced $synced sessions from Firestore")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync workout history from Firestore: ${e.message}")
        }
    }

    private suspend fun syncFeatureVectors(
        db: FirebaseFirestore, userId: String, sessionDir: String, localDir: File
    ) {
        val fvDocs = db.collection("users").document(userId)
            .collection("workout_history").document(sessionDir)
            .collection("feature_vectors")
            .orderBy("timestamp")
            .get().await()

        if (fvDocs.isEmpty) return

        // Firestore camelCase keys in order matching CSV columns
        val numericKeys = listOf(
            "meanHrBpm", "hrStdBpm", "hrMinBpm", "hrMaxBpm", "hrRangeBpm",
            "hrSlopeBpmPerS", "nnQualityRatio",
            "sdnnMs", "rmssdMs", "pnn50Pct", "meanNnMs", "cvNn",
            "lfPowerMs2", "hfPowerMs2", "lfHfRatio", "totalPowerMs2",
            "spo2MeanPct", "spo2MinPct", "spo2StdPct",
            "accelXMean", "accelYMean", "accelZMean",
            "accelXVar", "accelYVar", "accelZVar",
            "accelMagMean", "accelMagVar", "accelPeak"
        )

        // JSONL snake_case keys matching CsvWriter.writeFeatureJsonl
        val jsonlKeys = listOf(
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
        )

        val csvHeader = "user_id,timestamp,timestamp_end,sequence_id," +
            "mean_hr_bpm,hr_std_bpm,hr_min_bpm,hr_max_bpm,hr_range_bpm," +
            "hr_slope_bpm_per_s,nn_quality_ratio," +
            "sdnn_ms,rmssd_ms,pnn50_pct,mean_nn_ms,cv_nn," +
            "lf_power_ms2,hf_power_ms2,lf_hf_ratio,total_power_ms2," +
            "spo2_mean_pct,spo2_min_pct,spo2_std_pct," +
            "accel_x_mean,accel_y_mean,accel_z_mean," +
            "accel_x_var,accel_y_var,accel_z_var," +
            "accel_mag_mean,accel_mag_var,accel_peak," +
            "total_steps,cadence_spm," +
            "activity_label,fatigue_level,rpe_raw"

        val csvSb = StringBuilder()
        csvSb.appendLine(csvHeader)

        val jsonlSb = StringBuilder()

        for (d in fvDocs.documents) {
            val ts = d.getLong("timestamp") ?: 0L
            val tsEnd = d.getLong("timestampEnd") ?: 0L
            val seqId = d.getString("sequenceId") ?: ""
            val totalSteps = d.getLong("totalSteps") ?: 0
            val cadence = d.getDouble("cadenceSpm") ?: 0.0
            val actLabel = d.getString("activityLabel") ?: ""
            val fatLevel = d.getString("fatigueLevel") ?: ""
            val rpe = d.getLong("rpeRaw") ?: -1

            // Build CSV row
            val numericValues = numericKeys.joinToString(",") { key ->
                fmt(d.getDouble(key) ?: 0.0)
            }
            val rpeStr = if (rpe >= 0) rpe.toString() else ""
            csvSb.appendLine("$userId,$ts,$tsEnd,$seqId,$numericValues,$totalSteps,${fmt(cadence)},$actLabel,$fatLevel,$rpeStr")

            // Build JSONL row
            val json = JSONObject()
            json.put("timestamp", ts)
            json.put("sequence_id", seqId)
            for (i in numericKeys.indices) {
                json.put(jsonlKeys[i], d.getDouble(numericKeys[i]) ?: 0.0)
            }
            json.put("total_steps", totalSteps)
            json.put("cadence_spm", cadence)
            json.put("activity_label", actLabel)
            json.put("fatigue_level", fatLevel)
            json.put("rpe_raw", rpe)
            jsonlSb.appendLine(json.toString())
        }

        File(localDir, "features.csv").writeText(csvSb.toString())
        File(localDir, "features.jsonl").writeText(jsonlSb.toString())
    }

    private suspend fun syncRouteData(
        db: FirebaseFirestore, userId: String, sessionDir: String,
        localDir: File, sessionDoc: com.google.firebase.firestore.DocumentSnapshot
    ) {
        // Route points
        val routeDocs = db.collection("users").document(userId)
            .collection("workout_history").document(sessionDir)
            .collection("route_points")
            .orderBy("index")
            .get().await()

        if (routeDocs.documents.isNotEmpty()) {
            val sb = StringBuilder()
            sb.appendLine("latitude,longitude,timestamp_ms,cumulative_distance_m")
            for (d in routeDocs.documents) {
                sb.appendLine("${fmt(d.getDouble("latitude") ?: 0.0)}," +
                    "${fmt(d.getDouble("longitude") ?: 0.0)}," +
                    "${d.getLong("timestamp_ms") ?: 0}," +
                    fmt(d.getDouble("cumulative_distance_m") ?: 0.0))
            }
            File(localDir, "route.csv").writeText(sb.toString())
        }

        // Route summary from session metadata
        val dist = sessionDoc.getDouble("totalDistanceMeters") ?: 0.0
        val pace = sessionDoc.getDouble("avgPaceMinPerKm") ?: 0.0
        val dur = sessionDoc.getLong("durationMillis") ?: 0L
        val count = (sessionDoc.getLong("pointCount") ?: 0).toInt()
        if (dist > 0 || dur > 0) {
            File(localDir, "route_summary.csv").writeText(
                "total_distance_m,avg_pace_min_per_km,duration_ms,point_count\n" +
                    "${fmt(dist)},${fmt(pace)},${dur},${count}\n"
            )
        }
    }

    private suspend fun syncFatigueHistory(
        db: FirebaseFirestore, userId: String, sessionDir: String, localDir: File
    ) {
        val fatigueDocs = db.collection("users").document(userId)
            .collection("workout_history").document(sessionDir)
            .collection("fatigue_history")
            .orderBy("timestamp")
            .get().await()

        if (fatigueDocs.isEmpty) return

        val header = "timestamp,fatigue_percent," +
            "global_pLow,global_pHigh,global_level,global_levelIndex," +
            "ext_pLow,ext_pHigh,ext_level,ext_levelIndex," +
            "ondev_pLow,ondev_pHigh,ondev_level,ondev_levelIndex," +
            "current_hr,current_rmssd,baseline_hr,baseline_rmssd"

        val sb = StringBuilder()
        sb.appendLine(header)
        for (d in fatigueDocs.documents) {
            sb.appendLine("${d.getLong("timestamp") ?: 0},${fmt(d.getDouble("fatiguePercent") ?: 0.0)}," +
                "${fmt(d.getDouble("globalPLow") ?: 0.0)},${fmt(d.getDouble("globalPHigh") ?: 0.0)},${d.getString("globalLevel") ?: ""},${d.getLong("globalLevelIndex") ?: -1}," +
                "${fmt(d.getDouble("extPLow") ?: 0.0)},${fmt(d.getDouble("extPHigh") ?: 0.0)},${d.getString("extLevel") ?: ""},${d.getLong("extLevelIndex") ?: -1}," +
                "${fmt(d.getDouble("ondevPLow") ?: 0.0)},${fmt(d.getDouble("ondevPHigh") ?: 0.0)},${d.getString("ondevLevel") ?: ""},${d.getLong("ondevLevelIndex") ?: -1}," +
                "${fmt(d.getDouble("currentHr") ?: 0.0)},${fmt(d.getDouble("currentRmssd") ?: 0.0)},${fmt(d.getDouble("baselineHr") ?: 0.0)},${fmt(d.getDouble("baselineRmssd") ?: 0.0)}")
        }
        File(localDir, "fatigue_history.csv").writeText(sb.toString())
    }

    private fun parseSessionDir(dir: File): ActivityHistoryItem? {
        return try {
            val name = dir.name
            // Format: yyyy-MM-dd_HH-mm_ActivityType
            // First 16 chars = "yyyy-MM-dd_HH-mm"
            if (name.length < 17 || name[16] != '_') return null

            val datePart = name.substring(0, 16)
            val activityType = name.substring(17).replace('_', ' ')
            val startTimeMillis = DIR_DATE_FORMAT.parse(datePart)?.time ?: return null

            val (distance, pace, routeDuration) = parseRouteSummary(dir)
            val durationMillis = if (routeDuration > 0) routeDuration
                                 else parseDurationFromCsv(File(dir, "features.csv"))

            ActivityHistoryItem(
                activityType = activityType,
                startTimeMillis = startTimeMillis,
                durationMillis = durationMillis,
                sessionDirName = name,
                totalDistanceMeters = distance,
                avgPaceMinPerKm = pace
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse session dir ${dir.name}: ${e.message}")
            null
        }
    }

    private fun parseRouteSummary(dir: File): Triple<Float, Double, Long> {
        return try {
            val file = File(dir, "route_summary.csv")
            if (!file.exists()) return Triple(0f, 0.0, 0L)
            val lines = file.readLines()
            if (lines.size < 2) return Triple(0f, 0.0, 0L)
            val cols = lines[0].split(",")
            val distIdx = cols.indexOf("total_distance_m")
            val paceIdx = cols.indexOf("avg_pace_min_per_km")
            val durIdx = cols.indexOf("duration_ms")
            if (distIdx < 0 || paceIdx < 0) return Triple(0f, 0.0, 0L)
            val parts = lines[1].split(",")
            val dist = parts.getOrNull(distIdx)?.toFloatOrNull() ?: 0f
            val pace = parts.getOrNull(paceIdx)?.toDoubleOrNull() ?: 0.0
            val dur = if (durIdx >= 0) parts.getOrNull(durIdx)?.toLongOrNull() ?: 0L else 0L
            Triple(dist, pace, dur)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse route summary: ${e.message}")
            Triple(0f, 0.0, 0L)
        }
    }

    private fun parseDurationFromCsv(csvFile: File): Long {
        return try {
            val lines = csvFile.readLines()
            if (lines.size < 2) return 0L

            val columns = lines[0].split(",")
            val tsIndex = columns.indexOf("timestamp")
            val tsEndIndex = columns.indexOf("timestamp_end")
            if (tsIndex < 0 || tsEndIndex < 0) return 0L

            var firstTimestamp = Long.MAX_VALUE
            var lastTimestampEnd = Long.MIN_VALUE

            for (i in 1 until lines.size) {
                val parts = lines[i].split(",")
                if (parts.size <= maxOf(tsIndex, tsEndIndex)) continue

                val ts = parseTimestamp(parts[tsIndex].trim())
                val tsEnd = parseTimestamp(parts[tsEndIndex].trim())
                if (ts != null && ts < firstTimestamp) firstTimestamp = ts
                if (tsEnd != null && tsEnd > lastTimestampEnd) lastTimestampEnd = tsEnd
            }

            if (firstTimestamp != Long.MAX_VALUE && lastTimestampEnd != Long.MIN_VALUE) {
                lastTimestampEnd - firstTimestamp
            } else {
                0L
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse CSV duration: ${e.message}")
            0L
        }
    }

    private fun parseTimestamp(value: String): Long? {
        return value.toLongOrNull()
            ?: value.toDoubleOrNull()?.toLong()
    }

    private fun fmt(value: Double): String = String.format(Locale.US, "%.4f", value)
}
