package com.example.fitguard.data.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class HealthConnectManager(private val context: Context) {

    val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    companion object {
        /** Permissions for Sleep & Stress screen only */
        val SLEEP_STRESS_PERMISSIONS = setOf(
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class)
        )

        /** All Samsung Health permissions */
        val ALL_PERMISSIONS = setOf(
            // Sleep & stress
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
            // Activity
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getReadPermission(FloorsClimbedRecord::class),
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            // Vitals
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(RestingHeartRateRecord::class),
            HealthPermission.getReadPermission(OxygenSaturationRecord::class),
            HealthPermission.getReadPermission(RespiratoryRateRecord::class),
            HealthPermission.getReadPermission(BodyTemperatureRecord::class),
            HealthPermission.getReadPermission(BloodPressureRecord::class),
            HealthPermission.getReadPermission(BloodGlucoseRecord::class),
            // Body
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.getReadPermission(HeightRecord::class),
            HealthPermission.getReadPermission(BodyFatRecord::class),
            HealthPermission.getReadPermission(LeanBodyMassRecord::class),
            HealthPermission.getReadPermission(BasalMetabolicRateRecord::class),
            HealthPermission.getReadPermission(Vo2MaxRecord::class)
        )

        // Keep PERMISSIONS as alias so existing code compiles
        val PERMISSIONS = SLEEP_STRESS_PERMISSIONS

        fun isAvailable(context: Context): Boolean =
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }

    suspend fun checkPermissions(required: Set<String> = SLEEP_STRESS_PERMISSIONS): Boolean {
        val granted = client.permissionController.getGrantedPermissions()
        return granted.containsAll(required)
    }

    // ─── Time helpers ───────────────────────────────────────────────────────

    private fun todayRange(): TimeRangeFilter {
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
        return TimeRangeFilter.between(start, Instant.now())
    }

    private fun last24h() = TimeRangeFilter.between(
        Instant.now().minus(24, ChronoUnit.HOURS), Instant.now()
    )

    private fun last30Days() = TimeRangeFilter.between(
        Instant.now().minus(30, ChronoUnit.DAYS), Instant.now()
    )

    private fun last48h() = TimeRangeFilter.between(
        Instant.now().minus(48, ChronoUnit.HOURS), Instant.now()
    )

    // ─── Sleep & Stress (existing) ──────────────────────────────────────────

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

    suspend fun readLatestStress(): StressResult? {
        val response = client.readRecords(ReadRecordsRequest(
            recordType = HeartRateVariabilityRmssdRecord::class,
            timeRangeFilter = last48h()
        ))
        if (response.records.isEmpty()) return null
        val latest = response.records.maxByOrNull { it.time } ?: return null
        val score = rmssdToStress(latest.heartRateVariabilityMillis)
        val last6 = response.records.sortedBy { it.time }.takeLast(6)
        val history = last6.map { mapStressToChartLevel(rmssdToStress(it.heartRateVariabilityMillis)) }
        val dateFormatter = DateTimeFormatter.ofPattern("M/d").withZone(ZoneId.systemDefault())
        val historyDates = last6.map { dateFormatter.format(it.time) }
        return StressResult(score = score, label = stressLabel(score), history = history, historyDates = historyDates)
    }

    // ─── All Samsung Health metrics ─────────────────────────────────────────

    suspend fun readAllMetrics(): SamsungHealthSnapshot = coroutineScope {
        val stepsD       = async { readTodaySteps() }
        val distD        = async { readTodayDistance() }
        val activeCalsD  = async { readTodayActiveCalories() }
        val totalCalsD   = async { readTodayTotalCalories() }
        val floorsD      = async { readTodayFloors() }
        val hrD          = async { readLatestHeartRateBpm() }
        val rhrD         = async { readRestingHeartRate() }
        val spo2D        = async { readLatestSpO2() }
        val respD        = async { readLatestRespiratoryRate() }
        val tempD        = async { readLatestBodyTemp() }
        val bpD          = async { readLatestBloodPressure() }
        val glucoseD     = async { readLatestBloodGlucose() }
        val weightD      = async { readLatestWeight() }
        val heightD      = async { readLatestHeight() }
        val fatD         = async { readLatestBodyFat() }
        val leanD        = async { readLatestLeanBodyMass() }
        val bmrD         = async { readLatestBMR() }
        val vo2D         = async { readLatestVo2Max() }

        SamsungHealthSnapshot(
            steps              = stepsD.await(),
            distanceMeters     = distD.await(),
            activeCaloriesKcal = activeCalsD.await(),
            totalCaloriesKcal  = totalCalsD.await(),
            floorsClimbed      = floorsD.await(),
            heartRateBpm       = hrD.await(),
            restingHeartRateBpm = rhrD.await(),
            spO2Percent        = spo2D.await(),
            respiratoryRate    = respD.await(),
            bodyTempCelsius    = tempD.await(),
            bloodPressure      = bpD.await(),
            bloodGlucoseMmolPerL = glucoseD.await(),
            weightKg           = weightD.await(),
            heightCm           = heightD.await(),
            bodyFatPercent     = fatD.await(),
            leanBodyMassKg     = leanD.await(),
            basalMetabolicRateKcal = bmrD.await(),
            vo2MaxMlPerMinPerKg = vo2D.await()
        )
    }

    // ─── Activity ───────────────────────────────────────────────────────────

    private suspend fun readTodaySteps(): Long? = runCatching {
        val agg = client.aggregate(AggregateRequest(
            metrics = setOf(StepsRecord.COUNT_TOTAL),
            timeRangeFilter = todayRange()
        ))
        agg[StepsRecord.COUNT_TOTAL]
    }.getOrNull()

    private suspend fun readTodayDistance(): Double? = runCatching {
        val agg = client.aggregate(AggregateRequest(
            metrics = setOf(DistanceRecord.DISTANCE_TOTAL),
            timeRangeFilter = todayRange()
        ))
        agg[DistanceRecord.DISTANCE_TOTAL]?.inMeters
    }.getOrNull()

    private suspend fun readTodayActiveCalories(): Double? = runCatching {
        val agg = client.aggregate(AggregateRequest(
            metrics = setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
            timeRangeFilter = todayRange()
        ))
        agg[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories
    }.getOrNull()

    private suspend fun readTodayTotalCalories(): Double? = runCatching {
        val agg = client.aggregate(AggregateRequest(
            metrics = setOf(TotalCaloriesBurnedRecord.ENERGY_TOTAL),
            timeRangeFilter = todayRange()
        ))
        agg[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories
    }.getOrNull()

    private suspend fun readTodayFloors(): Double? = runCatching {
        val agg = client.aggregate(AggregateRequest(
            metrics = setOf(FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL),
            timeRangeFilter = todayRange()
        ))
        agg[FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL]
    }.getOrNull()

    // ─── Vitals ─────────────────────────────────────────────────────────────

    private suspend fun readLatestHeartRateBpm(): Int? = runCatching {
        val r = client.readRecords(ReadRecordsRequest(
            recordType = HeartRateRecord::class, timeRangeFilter = last24h()
        ))
        r.records.maxByOrNull { it.endTime }?.samples?.lastOrNull()?.beatsPerMinute?.toInt()
    }.getOrNull()

    private suspend fun readRestingHeartRate(): Int? = runCatching {
        val r = client.readRecords(ReadRecordsRequest(
            recordType = RestingHeartRateRecord::class, timeRangeFilter = last30Days()
        ))
        r.records.maxByOrNull { it.time }?.beatsPerMinute?.toInt()
    }.getOrNull()

    private suspend fun readLatestSpO2(): Double? = runCatching {
        val r = client.readRecords(ReadRecordsRequest(
            recordType = OxygenSaturationRecord::class, timeRangeFilter = last24h()
        ))
        r.records.maxByOrNull { it.time }?.percentage?.value
    }.getOrNull()

    private suspend fun readLatestRespiratoryRate(): Double? = runCatching {
        val r = client.readRecords(ReadRecordsRequest(
            recordType = RespiratoryRateRecord::class, timeRangeFilter = last24h()
        ))
        r.records.maxByOrNull { it.time }?.rate
    }.getOrNull()

    private suspend fun readLatestBodyTemp(): Double? = runCatching {
        val r = client.readRecords(ReadRecordsRequest(
            recordType = BodyTemperatureRecord::class, timeRangeFilter = last24h()
        ))
        r.records.maxByOrNull { it.time }?.temperature?.inCelsius
    }.getOrNull()

    private suspend fun readLatestBloodPressure(): BloodPressureReading? = runCatching {
        val r = client.readRecords(ReadRecordsRequest(
            recordType = BloodPressureRecord::class, timeRangeFilter = last30Days()
        ))
        val latest = r.records.maxByOrNull { it.time } ?: return@runCatching null
        BloodPressureReading(
            systolic = latest.systolic.inMillimetersOfMercury.toInt(),
            diastolic = latest.diastolic.inMillimetersOfMercury.toInt()
        )
    }.getOrNull()

    private suspend fun readLatestBloodGlucose(): Double? = runCatching {
        val r = client.readRecords(ReadRecordsRequest(
            recordType = BloodGlucoseRecord::class, timeRangeFilter = last30Days()
        ))
        r.records.maxByOrNull { it.time }?.level?.inMillimolesPerLiter
    }.getOrNull()

    // ─── Body composition ───────────────────────────────────────────────────

    private suspend fun readLatestWeight(): Double? = runCatching {
        val r = client.readRecords(ReadRecordsRequest(
            recordType = WeightRecord::class, timeRangeFilter = last30Days()
        ))
        r.records.maxByOrNull { it.time }?.weight?.inKilograms
    }.getOrNull()

    private suspend fun readLatestHeight(): Double? = runCatching {
        val r = client.readRecords(ReadRecordsRequest(
            recordType = HeightRecord::class, timeRangeFilter = last30Days()
        ))
        r.records.maxByOrNull { it.time }?.height?.inMeters?.let { it * 100 }
    }.getOrNull()

    private suspend fun readLatestBodyFat(): Double? = runCatching {
        val r = client.readRecords(ReadRecordsRequest(
            recordType = BodyFatRecord::class, timeRangeFilter = last30Days()
        ))
        r.records.maxByOrNull { it.time }?.percentage?.value
    }.getOrNull()

    private suspend fun readLatestLeanBodyMass(): Double? = runCatching {
        val r = client.readRecords(ReadRecordsRequest(
            recordType = LeanBodyMassRecord::class, timeRangeFilter = last30Days()
        ))
        r.records.maxByOrNull { it.time }?.mass?.inKilograms
    }.getOrNull()

    private suspend fun readLatestBMR(): Double? = runCatching {
        val r = client.readRecords(ReadRecordsRequest(
            recordType = BasalMetabolicRateRecord::class, timeRangeFilter = last30Days()
        ))
        r.records.maxByOrNull { it.time }?.basalMetabolicRate?.inKilocaloriesPerDay
    }.getOrNull()

    private suspend fun readLatestVo2Max(): Double? = runCatching {
        val r = client.readRecords(ReadRecordsRequest(
            recordType = Vo2MaxRecord::class, timeRangeFilter = last30Days()
        ))
        r.records.maxByOrNull { it.time }?.vo2MillilitersPerMinuteKilogram
    }.getOrNull()

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

    private fun rmssdToStress(rmssd: Double): Float {
        val clamped = rmssd.coerceIn(10.0, 100.0)
        return ((1.0 - (clamped - 10.0) / 90.0) * 100.0).toFloat()
    }

    private fun stressLabel(score: Float) = when {
        score < 25 -> "Relaxed"; score < 50 -> "Normal"; score < 75 -> "Elevated"; else -> "High"
    }

    private fun mapStressToChartLevel(score: Float) = when {
        score < 33f -> 1.0f; score < 66f -> 2.0f; else -> 3.0f
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

data class StressResult(
    val score: Float,
    val label: String,
    val history: List<Float>,
    val historyDates: List<String> = emptyList()
)

data class BloodPressureReading(val systolic: Int, val diastolic: Int)

data class SamsungHealthSnapshot(
    // Activity (today)
    val steps: Long?,
    val distanceMeters: Double?,
    val activeCaloriesKcal: Double?,
    val totalCaloriesKcal: Double?,
    val floorsClimbed: Double?,
    // Vitals
    val heartRateBpm: Int?,
    val restingHeartRateBpm: Int?,
    val spO2Percent: Double?,
    val respiratoryRate: Double?,
    val bodyTempCelsius: Double?,
    // Blood
    val bloodPressure: BloodPressureReading?,
    val bloodGlucoseMmolPerL: Double?,
    // Body composition
    val weightKg: Double?,
    val heightCm: Double?,
    val bodyFatPercent: Double?,
    val leanBodyMassKg: Double?,
    val basalMetabolicRateKcal: Double?,
    val vo2MaxMlPerMinPerKg: Double?,
    val syncedAt: java.time.Instant = java.time.Instant.now()
) {
    val bmi: Double? get() = if (weightKg != null && heightCm != null && heightCm > 0) {
        val hM = heightCm / 100.0; weightKg / (hM * hM)
    } else null
}
