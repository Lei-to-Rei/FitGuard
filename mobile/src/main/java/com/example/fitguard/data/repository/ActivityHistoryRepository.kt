package com.example.fitguard.data.repository

import android.util.Log
import com.example.fitguard.data.model.ActivityHistoryItem
import com.example.fitguard.data.processing.CsvWriter
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
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
}
