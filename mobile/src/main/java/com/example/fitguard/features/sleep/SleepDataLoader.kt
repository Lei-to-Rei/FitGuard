package com.example.fitguard.features.sleep

import android.util.Log
import com.example.fitguard.data.processing.CsvWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object SleepDataLoader {

    private const val TAG = "SleepDataLoader"

    fun saveSleepSession(session: SleepSession, userId: String) {
        try {
            val dateFolder = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(java.util.Date(session.sessionEndMs))
            val baseDir = CsvWriter.getOutputDir(userId, "")
            val dir = File(baseDir, dateFolder)
            dir.mkdirs()

            val json = JSONObject().apply {
                put("session_id", session.sessionId)
                put("session_start", session.sessionStartMs)
                put("session_end", session.sessionEndMs)
                put("sleep_onset", session.sleepOnsetMs)
                put("sleep_offset", session.sleepOffsetMs)
                put("total_sleep_duration_ms", session.totalSleepDurationMs)
                put("quality_score", session.qualityScore.toDouble())
                put("quality_label", session.qualityLabel)
                put("avg_hr", session.avgHr.toDouble())
                put("avg_spo2", session.avgSpO2.toDouble())
                put("min_spo2", session.minSpO2)
                put("avg_skin_temp", session.avgSkinTemp.toDouble())
                put("epochs", JSONArray().apply {
                    session.epochs.forEach { e ->
                        put(JSONObject().apply {
                            put("start", e.startMs)
                            put("end", e.endMs)
                            put("stage", e.stage)
                            put("hr", e.avgHr.toDouble())
                            put("rmssd", e.rmssd.toDouble())
                            put("movement", e.movementIndex.toDouble())
                            put("temp", e.avgSkinTemp.toDouble())
                            put("spo2", e.avgSpO2.toDouble())
                        })
                    }
                })
            }

            File(dir, "Sleep.jsonl").appendText(json.toString() + "\n")
            Log.d(TAG, "Saved sleep session to ${dir.absolutePath}/Sleep.jsonl")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save sleep session: ${e.message}", e)
        }
    }

    fun loadLatestSession(userId: String, date: String): SleepSession? {
        return try {
            val baseDir = CsvWriter.getOutputDir(userId, "")
            val file = File(File(baseDir, date), "Sleep.jsonl")
            if (!file.exists()) return null

            val lastLine = file.readLines().filter { it.isNotBlank() }.lastOrNull() ?: return null
            parseSleepSession(JSONObject(lastLine))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load sleep session: ${e.message}", e)
            null
        }
    }

    private fun parseSleepSession(json: JSONObject): SleepSession {
        val epochsArray = json.optJSONArray("epochs") ?: JSONArray()
        val epochs = (0 until epochsArray.length()).map { i ->
            val e = epochsArray.getJSONObject(i)
            ClassifiedEpoch(
                startMs = e.getLong("start"),
                endMs = e.getLong("end"),
                stage = e.getInt("stage"),
                avgHr = e.optDouble("hr", 0.0).toFloat(),
                rmssd = e.optDouble("rmssd", 0.0).toFloat(),
                movementIndex = e.optDouble("movement", 0.0).toFloat(),
                avgSkinTemp = e.optDouble("temp", 0.0).toFloat(),
                avgSpO2 = e.optDouble("spo2", 0.0).toFloat()
            )
        }

        return SleepSession(
            sessionId = json.optString("session_id", ""),
            sessionStartMs = json.optLong("session_start", 0),
            sessionEndMs = json.optLong("session_end", 0),
            sleepOnsetMs = json.optLong("sleep_onset", 0),
            sleepOffsetMs = json.optLong("sleep_offset", 0),
            totalSleepDurationMs = json.optLong("total_sleep_duration_ms", 0),
            qualityScore = json.optDouble("quality_score", 0.0).toFloat(),
            qualityLabel = json.optString("quality_label", "No Data"),
            epochs = epochs,
            avgHr = json.optDouble("avg_hr", 0.0).toFloat(),
            avgSpO2 = json.optDouble("avg_spo2", 0.0).toFloat(),
            minSpO2 = json.optInt("min_spo2", 0),
            avgSkinTemp = json.optDouble("avg_skin_temp", 0.0).toFloat()
        )
    }

    fun sessionToChartPoints(session: SleepSession): List<Float> {
        if (session.epochs.isEmpty()) return emptyList()

        // Downsample to ~13 points for the SleepChartView
        val targetPoints = 13
        val epochs = session.epochs
        if (epochs.size <= targetPoints) {
            return epochs.map { it.stage.toFloat() }
        }

        val step = epochs.size.toFloat() / targetPoints
        return (0 until targetPoints).map { i ->
            val index = (i * step).toInt().coerceAtMost(epochs.size - 1)
            epochs[index].stage.toFloat()
        }
    }

    fun formatDuration(durationMs: Long): String {
        val hours = durationMs / 3_600_000
        val minutes = (durationMs % 3_600_000) / 60_000
        return "${hours}hr ${minutes}min"
    }
}
