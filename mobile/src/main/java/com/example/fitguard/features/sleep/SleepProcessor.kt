package com.example.fitguard.features.sleep

import kotlin.math.sqrt

data class ClassifiedEpoch(
    val startMs: Long,
    val endMs: Long,
    val stage: Int,        // 1=Deep, 2=Light, 3=REM, 4=Awake
    val avgHr: Float,
    val rmssd: Float,
    val movementIndex: Float,
    val avgSkinTemp: Float,
    val avgSpO2: Float
)

data class SleepSession(
    val sessionId: String,
    val sessionStartMs: Long,
    val sessionEndMs: Long,
    val sleepOnsetMs: Long,
    val sleepOffsetMs: Long,
    val totalSleepDurationMs: Long,
    val qualityScore: Float,
    val qualityLabel: String,
    val epochs: List<ClassifiedEpoch>,
    val avgHr: Float,
    val avgSpO2: Float,
    val minSpO2: Int,
    val avgSkinTemp: Float
)

object SleepProcessor {

    // Movement thresholds (accelerometer magnitude variance)
    private const val MOVEMENT_HIGH = 5000f
    private const val MOVEMENT_LOW = 500f

    // HR delta from baseline
    private const val HR_DEEP_THRESHOLD = -5f
    private const val HR_AWAKE_THRESHOLD = 5f

    // HRV (RMSSD in ms)
    private const val RMSSD_HIGH = 50f
    private const val RMSSD_LOW = 20f

    // Sustained epochs for onset/offset detection
    private const val SUSTAINED_EPOCHS = 3  // 15 min at 5-min epochs

    fun process(epochs: List<SleepEpoch>, sessionStartMs: Long, sessionEndMs: Long): SleepSession {
        val sessionId = "sleep_${sessionStartMs}"

        if (epochs.isEmpty()) return emptySession(sessionId, sessionStartMs, sessionEndMs)

        // Step 1: Compute per-epoch features
        val features = epochs.map { computeFeatures(it) }

        // Step 2: Compute baselines
        val baselineHr = features
            .mapNotNull { it.avgHr.takeIf { hr -> hr > 0f } }
            .let { if (it.isNotEmpty()) it.average().toFloat() else 0f }

        // Step 3: Classify each epoch
        val classified = features.map { ef ->
            ClassifiedEpoch(
                startMs = ef.epoch.epochStartMs,
                endMs = ef.epoch.epochEndMs,
                stage = classifyEpoch(ef, baselineHr),
                avgHr = ef.avgHr,
                rmssd = ef.rmssd,
                movementIndex = ef.movementVar,
                avgSkinTemp = ef.avgSkinTemp,
                avgSpO2 = ef.avgSpO2
            )
        }

        // Step 4: Smooth isolated stages
        val smoothed = smoothStages(classified)

        // Step 5: Detect sleep onset and offset
        val sleepOnset = detectSleepOnset(smoothed)
        val sleepOffset = detectSleepOffset(smoothed)

        // Step 6: Calculate total sleep duration (non-Awake epochs between onset and offset)
        val totalSleepMs = smoothed
            .filter { it.startMs >= sleepOnset && it.endMs <= sleepOffset && it.stage != 4 }
            .sumOf { it.endMs - it.startMs }

        // Step 7: Quality score
        val qualityScore = calculateQuality(smoothed, totalSleepMs, sleepOnset, sleepOffset)
        val qualityLabel = when {
            qualityScore >= 85f -> "Excellent"
            qualityScore >= 70f -> "Good"
            qualityScore >= 50f -> "Fair"
            else -> "Poor"
        }

        val validHr = smoothed.map { it.avgHr }.filter { it > 0f }
        val validSpO2 = smoothed.map { it.avgSpO2 }.filter { it > 0f }
        val validTemp = smoothed.map { it.avgSkinTemp }.filter { it > 0f }

        return SleepSession(
            sessionId = sessionId,
            sessionStartMs = sessionStartMs,
            sessionEndMs = sessionEndMs,
            sleepOnsetMs = sleepOnset,
            sleepOffsetMs = sleepOffset,
            totalSleepDurationMs = totalSleepMs,
            qualityScore = qualityScore,
            qualityLabel = qualityLabel,
            epochs = smoothed,
            avgHr = if (validHr.isNotEmpty()) validHr.average().toFloat() else 0f,
            avgSpO2 = if (validSpO2.isNotEmpty()) validSpO2.average().toFloat() else 0f,
            minSpO2 = validSpO2.minOfOrNull { it.toInt() } ?: 0,
            avgSkinTemp = if (validTemp.isNotEmpty()) validTemp.average().toFloat() else 0f
        )
    }

    private data class EpochFeatures(
        val epoch: SleepEpoch,
        val avgHr: Float,
        val rmssd: Float,
        val movementVar: Float,
        val avgSkinTemp: Float,
        val avgSpO2: Float
    )

    private fun computeFeatures(epoch: SleepEpoch): EpochFeatures {
        val avgHr = if (epoch.hrValues.isNotEmpty()) epoch.hrValues.average().toFloat() else 0f

        // RMSSD from IBI values
        val rmssd = if (epoch.ibiValues.size >= 5) {
            val diffs = (1 until epoch.ibiValues.size).map {
                (epoch.ibiValues[it] - epoch.ibiValues[it - 1]).toDouble()
            }
            sqrt(diffs.map { it * it }.average()).toFloat()
        } else 0f

        val avgTemp = if (epoch.skinTempValues.isNotEmpty()) epoch.skinTempValues.average().toFloat() else 0f
        val avgSpO2 = if (epoch.spo2Values.isNotEmpty()) epoch.spo2Values.average().toFloat() else 0f

        return EpochFeatures(
            epoch = epoch,
            avgHr = avgHr,
            rmssd = rmssd,
            movementVar = epoch.accelMovementVar,
            avgSkinTemp = avgTemp,
            avgSpO2 = avgSpO2
        )
    }

    private fun classifyEpoch(ef: EpochFeatures, baselineHr: Float): Int {
        val hrDelta = if (baselineHr > 0f && ef.avgHr > 0f) ef.avgHr - baselineHr else 0f

        // High movement = Awake
        if (ef.movementVar > MOVEMENT_HIGH) return 4

        // Very low movement
        if (ef.movementVar < MOVEMENT_LOW) {
            // Deep: low HR + high HRV
            if (hrDelta < HR_DEEP_THRESHOLD && ef.rmssd > RMSSD_HIGH) return 1
            // REM: HR near/above baseline + high HRV + still body
            if (hrDelta > HR_DEEP_THRESHOLD && ef.rmssd > RMSSD_HIGH) return 3
            // Light: moderate/low HRV, still
            return 2
        }

        // Moderate movement
        if (hrDelta < HR_AWAKE_THRESHOLD && ef.rmssd > RMSSD_LOW) return 2
        return 4
    }

    private fun smoothStages(epochs: List<ClassifiedEpoch>): List<ClassifiedEpoch> {
        if (epochs.size < 3) return epochs
        val result = epochs.toMutableList()
        for (i in 1 until result.size - 1) {
            if (result[i].stage != result[i - 1].stage &&
                result[i].stage != result[i + 1].stage &&
                result[i - 1].stage == result[i + 1].stage
            ) {
                result[i] = result[i].copy(stage = result[i - 1].stage)
            }
        }
        return result
    }

    private fun detectSleepOnset(epochs: List<ClassifiedEpoch>): Long {
        var count = 0
        for ((i, epoch) in epochs.withIndex()) {
            if (epoch.stage != 4) {
                count++
                if (count >= SUSTAINED_EPOCHS) {
                    return epochs[i - SUSTAINED_EPOCHS + 1].startMs
                }
            } else {
                count = 0
            }
        }
        return epochs.firstOrNull()?.startMs ?: 0L
    }

    private fun detectSleepOffset(epochs: List<ClassifiedEpoch>): Long {
        var count = 0
        for (i in epochs.indices.reversed()) {
            if (epochs[i].stage != 4) {
                count++
                if (count >= SUSTAINED_EPOCHS) {
                    return epochs[minOf(i + SUSTAINED_EPOCHS, epochs.size - 1)].endMs
                }
            } else {
                count = 0
            }
        }
        return epochs.lastOrNull()?.endMs ?: 0L
    }

    private fun calculateQuality(
        epochs: List<ClassifiedEpoch>,
        totalSleepMs: Long,
        onsetMs: Long,
        offsetMs: Long
    ): Float {
        // 1. Duration score (25 pts)
        val durationHours = totalSleepMs / 3_600_000.0
        val durationScore = when {
            durationHours in 7.0..9.0 -> 25f
            durationHours in 6.0..7.0 || durationHours in 9.0..10.0 -> 20f
            durationHours in 5.0..6.0 -> 15f
            else -> 5f
        }

        // 2. Sleep efficiency (25 pts)
        val timeInBedMs = (offsetMs - onsetMs).coerceAtLeast(1L)
        val efficiency = totalSleepMs.toFloat() / timeInBedMs
        val efficiencyScore = (efficiency * 25f).coerceIn(0f, 25f)

        // 3. Deep sleep ratio (25 pts)
        val sleepEpochs = epochs.filter {
            it.startMs >= onsetMs && it.endMs <= offsetMs && it.stage != 4
        }
        val deepCount = sleepEpochs.count { it.stage == 1 }
        val deepRatio = if (sleepEpochs.isNotEmpty()) deepCount.toFloat() / sleepEpochs.size else 0f
        val deepScore = when {
            deepRatio in 0.15f..0.25f -> 25f
            deepRatio in 0.10f..0.15f || deepRatio in 0.25f..0.30f -> 20f
            deepRatio > 0.05f -> 15f
            else -> 5f
        }

        // 4. Awakenings penalty (25 pts)
        val awakenings = countAwakenings(epochs, onsetMs, offsetMs)
        val awakeScore = when {
            awakenings <= 1 -> 25f
            awakenings <= 3 -> 20f
            awakenings <= 5 -> 15f
            else -> 5f
        }

        return (durationScore + efficiencyScore + deepScore + awakeScore).coerceIn(0f, 100f)
    }

    private fun countAwakenings(epochs: List<ClassifiedEpoch>, onsetMs: Long, offsetMs: Long): Int {
        var count = 0
        var wasSleep = false
        for (epoch in epochs) {
            if (epoch.startMs < onsetMs || epoch.endMs > offsetMs) continue
            if (epoch.stage == 4 && wasSleep) count++
            wasSleep = epoch.stage != 4
        }
        return count
    }

    private fun emptySession(sessionId: String, startMs: Long, endMs: Long) = SleepSession(
        sessionId = sessionId,
        sessionStartMs = startMs,
        sessionEndMs = endMs,
        sleepOnsetMs = startMs,
        sleepOffsetMs = endMs,
        totalSleepDurationMs = 0L,
        qualityScore = 0f,
        qualityLabel = "No Data",
        epochs = emptyList(),
        avgHr = 0f,
        avgSpO2 = 0f,
        minSpO2 = 0,
        avgSkinTemp = 0f
    )
}
