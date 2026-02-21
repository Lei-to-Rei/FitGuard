package com.example.fitguard.data.processing

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object RpeState {
    @Volatile var currentRpe: Int = -1
    @Volatile var lastTimestamp: Long = 0

    fun update(rpe: Int) {
        currentRpe = rpe
        lastTimestamp = System.currentTimeMillis()
    }

    fun reset() {
        currentRpe = -1
        lastTimestamp = 0
    }

    val fatigueLevel: String get() = when (currentRpe) {
        in 0..2 -> "0"
        in 3..4 -> "1"
        in 5..7 -> "2"
        in 8..10 -> "3"
        else -> ""
    }
}

class SequenceProcessor(private val context: Context) {
    companion object {
        private const val TAG = "SequenceProcessor"
        const val ACTION_SEQUENCE_PROCESSED = "com.example.fitguard.SEQUENCE_PROCESSED"
        private const val MAX_GAP_MS = 90_000L
        private const val OVERLAP_STEP_MS = 15_000L
        private const val OVERLAP_COUNT = 3
        private const val WINDOW_DURATION_MS = 60_000L
        private const val MIN_OVERLAP_PPG_SAMPLES = 10
        @Volatile private var previousSequenceData: SequenceData? = null
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    val accumulator = SequenceBatchAccumulator { data ->
        handleSequenceReady(data)
    }

    private fun handleSequenceReady(currentData: SequenceData) {
        val previous = previousSequenceData
        if (previous != null) {
            for (window in createOverlapWindows(previous, currentData)) {
                processSequence(window)
            }
        }
        processSequence(currentData)
        previousSequenceData = currentData
    }

    /**
     * Creates 3 sliding overlap windows between consecutive 60s sequences.
     * Given REG₁ (0-60s) and REG₂ (60-120s), produces:
     *   OVL1 = 15-75s,  OVL2 = 30-90s,  OVL3 = 45-105s
     * Each window is 60s wide, stepped by 15s.
     */
    private fun createOverlapWindows(previous: SequenceData, current: SequenceData): List<SequenceData> {
        if (previous.ppgSamples.isEmpty() || current.ppgSamples.isEmpty()) return emptyList()

        val prevEnd = previous.ppgSamples.last().timestamp
        val currStart = current.ppgSamples.first().timestamp
        val gap = currStart - prevEnd
        if (gap < 0 || gap > MAX_GAP_MS) return emptyList()

        val prevStart = previous.ppgSamples.first().timestamp
        val results = mutableListOf<SequenceData>()

        for (i in 1..OVERLAP_COUNT) {
            val windowStart = prevStart + i * OVERLAP_STEP_MS
            val windowEnd = windowStart + WINDOW_DURATION_MS

            val prevPpg = previous.ppgSamples.filter { it.timestamp >= windowStart }
            val currPpg = current.ppgSamples.filter { it.timestamp <= windowEnd }
            val combinedPpg = prevPpg + currPpg

            if (combinedPpg.size < MIN_OVERLAP_PPG_SAMPLES) continue

            val prevAccel = previous.accelSamples.filter { it.timestamp >= windowStart }
            val currAccel = current.accelSamples.filter { it.timestamp <= windowEnd }
            val combinedAccel = prevAccel + currAccel

            results.add(SequenceData(
                sequenceId = "OVL${i}_${previous.sequenceId}_${current.sequenceId}",
                ppgSamples = combinedPpg,
                accelSamples = combinedAccel,
                activityType = current.activityType
            ))
        }

        return results
    }

    fun clearPreviousData() {
        previousSequenceData = null
    }

    private fun processSequence(data: SequenceData) {
        scope.launch {
            try {
                Log.d(TAG, "Processing sequence ${data.sequenceId} with " +
                        "${data.ppgSamples.size} PPG, ${data.accelSamples.size} accel samples")

                if (data.ppgSamples.size < 10) {
                    Log.w(TAG, "Too few PPG samples (${data.ppgSamples.size}), skipping processing")
                    return@launch
                }

                // 1. Compute PPG features (1-19)
                val ppgFeatures = PpgProcessor.process(data.ppgSamples)

                // 2. Compute accel features (20-28, 32-33)
                val durationSeconds = if (data.ppgSamples.size >= 2) {
                    (data.ppgSamples.last().timestamp - data.ppgSamples.first().timestamp) / 1000.0
                } else 0.0
                val accelFeatures = AccelProcessor.process(data.accelSamples, durationSeconds)

                // 3. Assemble FeatureVector
                // Timestamp = midpoint of the actual data window so rows
                // sort into correct temporal order when sorted by timestamp:
                // REG₁(30) → OVL1(45) → OVL2(60) → OVL3(75) → REG₂(90) → …
                val dataTimestamp = if (data.ppgSamples.size >= 2) {
                    val first = data.ppgSamples.first().timestamp
                    val last = data.ppgSamples.last().timestamp
                    first + (last - first) / 2
                } else {
                    System.currentTimeMillis()
                }
                val featureVector = FeatureVector(
                    timestamp = dataTimestamp,
                    timestampEnd = data.ppgSamples.last().timestamp,
                    sequenceId = data.sequenceId,
                    ppg = ppgFeatures,
                    accelXMean = accelFeatures.xMean,
                    accelYMean = accelFeatures.yMean,
                    accelZMean = accelFeatures.zMean,
                    accelXVar = accelFeatures.xVar,
                    accelYVar = accelFeatures.yVar,
                    accelZVar = accelFeatures.zVar,
                    accelMagMean = accelFeatures.magMean,
                    accelMagVar = accelFeatures.magVar,
                    accelPeak = accelFeatures.magPeak,
                    totalSteps = accelFeatures.totalSteps,
                    cadenceSpm = accelFeatures.cadenceSpm,
                    activityLabel = data.activityType,
                    fatigueLevel = RpeState.fatigueLevel,
                    rpeRaw = RpeState.currentRpe
                )

                Log.d(TAG, "Features for ${data.sequenceId}: HR=${String.format("%.1f", ppgFeatures.meanHrBpm)} " +
                        "SDNN=${String.format("%.2f", ppgFeatures.sdnnMs)} " +
                        "RMSSD=${String.format("%.2f", ppgFeatures.rmssdMs)} " +
                        "LF/HF=${String.format("%.2f", ppgFeatures.lfHfRatio)} " +
                        "SpO2=${String.format("%.1f", ppgFeatures.spo2MeanPct)} " +
                        "Steps=${accelFeatures.totalSteps}")

                // 5. Write to CSV
                val userId = FirebaseAuth.getInstance().currentUser?.uid ?: ""
                CsvWriter.writeFeatureVector(featureVector, userId)

                // 6. Broadcast key metrics
                broadcastResult(featureVector)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process sequence ${data.sequenceId}: ${e.message}", e)
            }
        }
    }

    private fun broadcastResult(fv: FeatureVector) {
        val intent = Intent(ACTION_SEQUENCE_PROCESSED).apply {
            putExtra("sequence_id", fv.sequenceId)
            putExtra("mean_hr_bpm", fv.ppg.meanHrBpm)
            putExtra("sdnn_ms", fv.ppg.sdnnMs)
            putExtra("rmssd_ms", fv.ppg.rmssdMs)
            putExtra("pnn50_pct", fv.ppg.pnn50Pct)
            putExtra("lf_hf_ratio", fv.ppg.lfHfRatio)
            putExtra("spo2_mean_pct", fv.ppg.spo2MeanPct)
            putExtra("total_steps", fv.totalSteps)
            putExtra("cadence_spm", fv.cadenceSpm)
        }
        context.sendBroadcast(intent)
        Log.d(TAG, "Broadcast SEQUENCE_PROCESSED for ${fv.sequenceId}")
    }
}
