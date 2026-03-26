package com.example.fitguard.data.repository

import android.util.Log
import com.example.fitguard.data.processing.CsvWriter
import java.io.File

data class RoutePoint(val lat: Double, val lng: Double, val timeMs: Long, val cumulativeDistanceM: Double)
data class RouteSummary(val totalDistanceM: Float, val avgPaceMinPerKm: Double, val durationMs: Long, val pointCount: Int)
data class HrDataPoint(val timeMs: Long, val hrBpm: Float)
data class FatigueDataPoint(val timeMs: Long, val totalPowerMs2: Double)
data class SplitData(val kmIndex: Int, val paceMinPerKm: Double)

object SessionDetailRepository {
    private const val TAG = "SessionDetailRepo"

    fun loadRouteCsv(userId: String, sessionDir: String): List<RoutePoint> {
        return try {
            val file = File(CsvWriter.getOutputDir(userId, sessionDir), "route.csv")
            Log.d(TAG, "loadRouteCsv: path=${file.absolutePath}, exists=${file.exists()}")
            if (!file.exists()) return emptyList()
            val lines = file.readLines()
            if (lines.size < 2) return emptyList()
            val cols = lines[0].split(",")
            val latIdx = cols.indexOf("latitude")
            val lngIdx = cols.indexOf("longitude")
            val tsIdx = cols.indexOf("timestamp_ms")
            val distIdx = cols.indexOf("cumulative_distance_m")
            if (latIdx < 0 || lngIdx < 0) return emptyList()

            lines.drop(1).mapNotNull { line ->
                val parts = line.split(",")
                val lat = parts.getOrNull(latIdx)?.toDoubleOrNull() ?: return@mapNotNull null
                val lng = parts.getOrNull(lngIdx)?.toDoubleOrNull() ?: return@mapNotNull null
                val ts = parts.getOrNull(tsIdx)?.toLongOrNull() ?: 0L
                val dist = parts.getOrNull(distIdx)?.toDoubleOrNull() ?: 0.0
                RoutePoint(lat, lng, ts, dist)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load route CSV: ${e.message}")
            emptyList()
        }
    }

    fun loadRouteSummary(userId: String, sessionDir: String): RouteSummary {
        return try {
            val file = File(CsvWriter.getOutputDir(userId, sessionDir), "route_summary.csv")
            if (!file.exists()) return RouteSummary(0f, 0.0, 0L, 0)
            val lines = file.readLines()
            if (lines.size < 2) return RouteSummary(0f, 0.0, 0L, 0)
            val cols = lines[0].split(",")
            val parts = lines[1].split(",")
            val dist = parts.getOrNull(cols.indexOf("total_distance_m"))?.toFloatOrNull() ?: 0f
            val pace = parts.getOrNull(cols.indexOf("avg_pace_min_per_km"))?.toDoubleOrNull() ?: 0.0
            val dur = parts.getOrNull(cols.indexOf("duration_ms"))?.toLongOrNull() ?: 0L
            val count = parts.getOrNull(cols.indexOf("point_count"))?.toIntOrNull() ?: 0
            RouteSummary(dist, pace, dur, count)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load route summary: ${e.message}")
            RouteSummary(0f, 0.0, 0L, 0)
        }
    }

    fun loadHeartRateData(userId: String, sessionDir: String): List<HrDataPoint> {
        return try {
            val file = File(CsvWriter.getOutputDir(userId, sessionDir), "features.csv")
            if (!file.exists()) return emptyList()
            val lines = file.readLines()
            if (lines.size < 2) return emptyList()
            val cols = lines[0].split(",")
            val tsIdx = cols.indexOf("timestamp")
            val hrIdx = cols.indexOf("mean_hr_bpm")
            if (tsIdx < 0 || hrIdx < 0) return emptyList()

            lines.drop(1).mapNotNull { line ->
                val parts = line.split(",")
                val ts = parts.getOrNull(tsIdx)?.toLongOrNull() ?: return@mapNotNull null
                val hr = parts.getOrNull(hrIdx)?.toFloatOrNull() ?: return@mapNotNull null
                if (hr > 0f) HrDataPoint(ts, hr) else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load HR data: ${e.message}")
            emptyList()
        }
    }

    fun loadFatigueData(userId: String, sessionDir: String): List<FatigueDataPoint> {
        return try {
            val file = File(CsvWriter.getOutputDir(userId, sessionDir), "features.csv")
            if (!file.exists()) return emptyList()
            val lines = file.readLines()
            if (lines.size < 2) return emptyList()
            val cols = lines[0].split(",")
            val tsIdx = cols.indexOf("timestamp")
            val tpIdx = cols.indexOf("total_power_ms2")
            if (tsIdx < 0 || tpIdx < 0) return emptyList()

            lines.drop(1).mapNotNull { line ->
                val parts = line.split(",")
                val ts = parts.getOrNull(tsIdx)?.toLongOrNull() ?: return@mapNotNull null
                val tp = parts.getOrNull(tpIdx)?.toDoubleOrNull() ?: return@mapNotNull null
                if (tp > 0.0) FatigueDataPoint(ts, tp) else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load fatigue data: ${e.message}")
            emptyList()
        }
    }

    fun loadOverallFatigueLevel(userId: String, sessionDir: String): String {
        return try {
            val file = File(CsvWriter.getOutputDir(userId, sessionDir), "features.csv")
            if (!file.exists()) return "--"
            val lines = file.readLines()
            if (lines.size < 2) return "--"
            val cols = lines[0].split(",")
            val flIdx = cols.indexOf("fatigue_level")
            if (flIdx < 0) return "--"

            val levels = lines.drop(1).mapNotNull { line ->
                val parts = line.split(",")
                parts.getOrNull(flIdx)?.trim()?.ifEmpty { null }
            }
            if (levels.isEmpty()) return "--"

            val mostCommon = levels.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "--"
            when (mostCommon) {
                "0" -> "Very Light"
                "1" -> "Light"
                "2" -> "Moderate"
                "3" -> "Heavy"
                else -> mostCommon
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load fatigue level: ${e.message}")
            "--"
        }
    }

    fun computeSplits(routePoints: List<RoutePoint>): List<SplitData> {
        if (routePoints.size < 2) return emptyList()
        val splits = mutableListOf<SplitData>()
        var currentKm = 1
        var kmStartTime = routePoints.first().timeMs

        for (i in 1 until routePoints.size) {
            val p = routePoints[i]
            val distKm = p.cumulativeDistanceM / 1000.0
            if (distKm >= currentKm) {
                val elapsed = (p.timeMs - kmStartTime) / 60000.0
                splits.add(SplitData(currentKm, elapsed))
                kmStartTime = p.timeMs
                currentKm++
            }
        }
        return splits
    }
}
