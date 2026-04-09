package com.example.fitguard.data.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.temporal.ChronoUnit

class HealthConnectManager(private val context: Context) {

    val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    companion object {
        val PERMISSIONS = setOf(
            HealthPermission.getReadPermission(SleepSessionRecord::class)
        )

        fun isAvailable(context: Context): Boolean =
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }

    suspend fun checkPermissions(required: Set<String> = PERMISSIONS): Boolean {
        val granted = client.permissionController.getGrantedPermissions()
        return granted.containsAll(required)
    }

    // ─── Time helpers ───────────────────────────────────────────────────────

    private fun last48h() = TimeRangeFilter.between(
        Instant.now().minus(48, ChronoUnit.HOURS), Instant.now()
    )

    // ─── Sleep ──────────────────────────────────────────────────────────────

    suspend fun readLatestSleep(): SleepResult? {
        val response = client.readRecords(ReadRecordsRequest(
            recordType = SleepSessionRecord::class,
            timeRangeFilter = last48h()
        ))
        val latest = response.records.maxByOrNull { it.endTime } ?: return null
        val durationMs = latest.endTime.toEpochMilli() - latest.startTime.toEpochMilli()
        val stages = latest.stages
        val deepMs = stages.filter { it.stage == SleepSessionRecord.STAGE_TYPE_DEEP }
            .sumOf { it.endTime.toEpochMilli() - it.startTime.toEpochMilli() }
        val remMs = stages.filter { it.stage == SleepSessionRecord.STAGE_TYPE_REM }
            .sumOf { it.endTime.toEpochMilli() - it.startTime.toEpochMilli() }
        val lightMs = stages.filter {
            it.stage == SleepSessionRecord.STAGE_TYPE_LIGHT ||
            it.stage == SleepSessionRecord.STAGE_TYPE_SLEEPING
        }.sumOf { it.endTime.toEpochMilli() - it.startTime.toEpochMilli() }
        val awakeMs = stages.filter {
            it.stage == SleepSessionRecord.STAGE_TYPE_AWAKE ||
            it.stage == SleepSessionRecord.STAGE_TYPE_OUT_OF_BED
        }.sumOf { it.endTime.toEpochMilli() - it.startTime.toEpochMilli() }
        val qualityScore = calcSleepQuality(durationMs, deepMs, awakeMs)
        return SleepResult(
            durationMs = durationMs, deepMs = deepMs, remMs = remMs,
            lightMs = lightMs, awakeMs = awakeMs,
            qualityScore = qualityScore, qualityLabel = sleepQualityLabel(qualityScore),
            chartPoints = stagesToChartPoints(stages),
            startTime = latest.startTime, endTime = latest.endTime
        )
    }

    // ─── Private helpers ────────────────────────────────────────────────────

    private fun calcSleepQuality(durationMs: Long, deepMs: Long, awakeMs: Long): Float {
        val hours = durationMs / 3_600_000f
        val durationScore = when { hours >= 7 && hours <= 9 -> 100f; hours >= 6 -> 70f; hours >= 5 -> 40f; else -> 20f }
        val deepRatio = if (durationMs > 0) deepMs.toFloat() / durationMs else 0f
        val deepScore = when { deepRatio >= 0.15f && deepRatio <= 0.25f -> 100f; deepRatio >= 0.10f -> 70f; else -> 40f }
        val eff = if (durationMs > 0) (1f - awakeMs.toFloat() / durationMs).coerceIn(0f, 1f) else 1f
        return durationScore * 0.4f + deepScore * 0.3f + eff * 100f * 0.3f
    }

    private fun sleepQualityLabel(score: Float) = when {
        score >= 80 -> "Excellent"; score >= 60 -> "Good"; score >= 40 -> "Fair"; else -> "Poor"
    }

    private fun stagesToChartPoints(stages: List<SleepSessionRecord.Stage>): List<Float> {
        if (stages.isEmpty()) return emptyList()
        val points = stages.map { s ->
            when (s.stage) {
                SleepSessionRecord.STAGE_TYPE_DEEP -> 1f
                SleepSessionRecord.STAGE_TYPE_LIGHT, SleepSessionRecord.STAGE_TYPE_SLEEPING -> 2f
                SleepSessionRecord.STAGE_TYPE_REM -> 3f
                else -> 4f
            }
        }
        if (points.size <= 13) return points
        val step = points.size.toFloat() / 13f
        return (0 until 13).map { i -> points[(i * step).toInt()] }
    }
}

// ─── Data classes ───────────────────────────────────────────────────────────

data class SleepResult(
    val durationMs: Long, val deepMs: Long, val remMs: Long,
    val lightMs: Long, val awakeMs: Long,
    val qualityScore: Float, val qualityLabel: String,
    val chartPoints: List<Float>,
    val startTime: java.time.Instant, val endTime: java.time.Instant
)
