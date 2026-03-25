package com.example.fitguard.data.processing

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.fitguard.features.activitytracking.ActivityTrackingViewModel
import com.example.fitguard.features.fatigue.FatigueAlertManager
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object RpeState {
    @Volatile var currentRpe: Int = -1

    fun update(rpe: Int) {
        currentRpe = rpe
    }

    fun reset() {
        currentRpe = -1
    }

    val fatigueLevel: String get() = fatigueLevelForRpe(currentRpe)

    fun fatigueLevelForRpe(rpe: Int): String = when (rpe) {
        in 0..2 -> "0"
        in 3..4 -> "1"
        in 5..7 -> "2"
        in 8..10 -> "3"
        else -> ""
    }
}

class SequenceProcessor(private val context: Context) {
    data class PendingWindow(val fv: FeatureVector, val userId: String, val sessionDir: String)

    companion object {
        private const val TAG = "SequenceProcessor"
        const val ACTION_SEQUENCE_PROCESSED = "com.example.fitguard.SEQUENCE_PROCESSED"
        private const val WINDOW_DURATION_MS = 60_000L
        private const val WINDOW_STEP_MS = 15_000L
        private const val MIN_PPG_SAMPLES = 10

        // Static so buffer survives WearableListenerService recreation between sequences
        private val ppgBuffer = mutableListOf<PpgSample>()
        private val accelBuffer = mutableListOf<AccelSample>()
        private var nextWindowStart: Long = -1L
        private var windowIndex: Int = 0
        private var currentActivityType: String = ""
        private val bufferMutex = Mutex()

        // Deferred-flush RPE state
        private val pendingWindows = mutableListOf<PendingWindow>()
        private var lastFlushedRpe: Int = -1
        private var pendingRpe: Int? = null

        fun clearBuffer() {
            ppgBuffer.clear()
            accelBuffer.clear()
            nextWindowStart = -1L
            windowIndex = 0
            currentActivityType = ""
            pendingWindows.clear()
            lastFlushedRpe = -1
            pendingRpe = null
            SequenceBatchAccumulator.clearAll()
            FatigueAlertManager.reset()
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    val accumulator = SequenceBatchAccumulator { data ->
        handleSequenceReady(data)
    }

    private fun handleSequenceReady(data: SequenceData) {
        scope.launch {
            bufferMutex.withLock {
                // Append new samples to buffers
                ppgBuffer.addAll(data.ppgSamples)
                accelBuffer.addAll(data.accelSamples)
                ppgBuffer.sortBy { it.timestamp }
                accelBuffer.sortBy { it.timestamp }
                currentActivityType = data.activityType

                if (ppgBuffer.isEmpty()) return@withLock

                // Initialize nextWindowStart from first sample on first call
                if (nextWindowStart < 0) {
                    nextWindowStart = ppgBuffer.first().timestamp
                }

                val bufferEnd = ppgBuffer.last().timestamp
                val bufferSpanMs = bufferEnd - nextWindowStart
                Log.d(TAG, "Buffer updated: ${ppgBuffer.size} PPG samples, " +
                        "span=${bufferSpanMs}ms, need=${WINDOW_DURATION_MS}ms, " +
                        "nextWindowStart=$nextWindowStart, bufferEnd=$bufferEnd")

                // Generate all possible 60s windows
                while (nextWindowStart + WINDOW_DURATION_MS <= bufferEnd) {
                    val windowEnd = nextWindowStart + WINDOW_DURATION_MS

                    val windowPpg = ppgBuffer.filter { it.timestamp in nextWindowStart..windowEnd }
                    val windowAccel = accelBuffer.filter { it.timestamp in nextWindowStart..windowEnd }

                    val windowData = SequenceData(
                        sequenceId = "W${windowIndex}_${nextWindowStart}",
                        ppgSamples = windowPpg,
                        accelSamples = windowAccel,
                        activityType = currentActivityType
                    )

                    processWindow(windowData)
                    windowIndex++
                    nextWindowStart += WINDOW_STEP_MS
                }

                // Trim samples older than nextWindowStart (can't participate in future windows)
                ppgBuffer.removeAll { it.timestamp < nextWindowStart }
                accelBuffer.removeAll { it.timestamp < nextWindowStart }

                // Flush with stored RPE that arrived before windows were ready (race condition)
                val storedRpe = pendingRpe
                if (storedRpe != null && pendingWindows.isNotEmpty()) {
                    Log.d(TAG, "Flushing ${pendingWindows.size} windows with stored pendingRpe=$storedRpe")
                    flushPendingWindows(storedRpe)
                    pendingRpe = null
                }
            }
        }
    }

    private suspend fun processWindow(data: SequenceData) {
        try {
            Log.d(TAG, "Processing window ${data.sequenceId} with " +
                    "${data.ppgSamples.size} PPG, ${data.accelSamples.size} accel samples")

            if (data.ppgSamples.size < MIN_PPG_SAMPLES) {
                Log.w(TAG, "Too few PPG samples (${data.ppgSamples.size}), skipping processing")
                return
            }

            // 1. Compute PPG features (1-19)
            val ppgFeatures = PpgProcessor.process(data.ppgSamples)

            // 2. Compute accel features (20-28, 32-33)
            val durationSeconds = if (data.ppgSamples.size >= 2) {
                (data.ppgSamples.last().timestamp - data.ppgSamples.first().timestamp) / 1000.0
            } else 0.0
            val accelFeatures = AccelProcessor.process(data.accelSamples, durationSeconds)

            // 3. Assemble FeatureVector (RPE filled later on flush)
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
                activityLabel = data.activityType
            )

            Log.d(TAG, "Features for ${data.sequenceId}: HR=${String.format("%.1f", ppgFeatures.meanHrBpm)} " +
                    "SDNN=${String.format("%.2f", ppgFeatures.sdnnMs)} " +
                    "RMSSD=${String.format("%.2f", ppgFeatures.rmssdMs)} " +
                    "LF/HF=${String.format("%.2f", ppgFeatures.lfHfRatio)} " +
                    "SpO2=${String.format("%.1f", ppgFeatures.spo2MeanPct)} " +
                    "Steps=${accelFeatures.totalSteps} (pending RPE)")

            // 4. Add to pending list — will be flushed when RPE arrives
            val userId = FirebaseAuth.getInstance().currentUser?.uid ?: ""
            val sessionDir = ActivityTrackingViewModel.activeSessionDir ?: ""
            pendingWindows.add(PendingWindow(featureVector, userId, sessionDir))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process window ${data.sequenceId}: ${e.message}", e)
        }
    }

    /**
     * Flush all pendingWindows to CSV with the given RPE value.
     * Must be called while holding bufferMutex.
     */
    private fun flushPendingWindows(rpe: Int) {
        val n = pendingWindows.size
        if (n == 0) return

        if (rpe >= 0) {
            for (i in pendingWindows.indices) {
                val roundedRpe = if (lastFlushedRpe < 0) {
                    rpe
                } else {
                    (lastFlushedRpe + (i + 1).toDouble() / n * (rpe - lastFlushedRpe)).toInt()
                }
                val fatigue = RpeState.fatigueLevelForRpe(roundedRpe)
                val pw = pendingWindows[i]
                val finalFv = pw.fv.copy(rpeRaw = roundedRpe, fatigueLevel = fatigue)
                CsvWriter.writeFeatureVector(finalFv, pw.userId, pw.sessionDir)
                CsvWriter.writeFeatureJsonl(finalFv, pw.userId, pw.sessionDir)
                broadcastResult(finalFv)
            }
            Log.d(TAG, "Flushed $n windows with RPE interpolation: " +
                    "lastFlushed=$lastFlushedRpe -> new=$rpe")
            lastFlushedRpe = rpe
        } else {
            val carryRpe = if (lastFlushedRpe >= 0) lastFlushedRpe else -1
            val fatigue = RpeState.fatigueLevelForRpe(carryRpe)
            for (pw in pendingWindows) {
                val finalFv = pw.fv.copy(
                    rpeRaw = carryRpe,
                    fatigueLevel = fatigue
                )
                CsvWriter.writeFeatureVector(finalFv, pw.userId, pw.sessionDir)
                CsvWriter.writeFeatureJsonl(finalFv, pw.userId, pw.sessionDir)
                broadcastResult(finalFv)
            }
            Log.d(TAG, "Flushed $n windows with carry-forward RPE=$carryRpe (skipped)")
        }
        pendingWindows.clear()
    }

    fun onRpeReceived(rpe: Int) {
        scope.launch {
            bufferMutex.withLock {
                if (pendingWindows.isEmpty()) {
                    Log.d(TAG, "onRpeReceived($rpe) but no pending windows, storing as pendingRpe")
                    pendingRpe = rpe
                    return@withLock
                }
                pendingRpe = null
                flushPendingWindows(rpe)
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
            putExtra("feature_array", fv.toFeatureFloatArray())
        }
        context.sendBroadcast(intent)
        FatigueAlertManager.onFeatureWindow(context, fv.toFeatureFloatArray())
        Log.d(TAG, "Broadcast SEQUENCE_PROCESSED for ${fv.sequenceId}")
    }
}
