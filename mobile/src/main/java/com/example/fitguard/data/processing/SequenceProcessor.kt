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

class SequenceProcessor(private val context: Context) {
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

        fun clearBuffer() {
            ppgBuffer.clear()
            accelBuffer.clear()
            nextWindowStart = -1L
            windowIndex = 0
            currentActivityType = ""
            SequenceBatchAccumulator.clearAll()
            FatigueAlertManager.reset()
        }

        /**
         * Clear all buffers. Call this on session stop.
         */
        fun flushRemainingAndClear() {
            clearBuffer()
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
            }
        }
    }

    private fun processWindow(data: SequenceData) {
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

            // 3. Assemble FeatureVector
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
                    "Steps=${accelFeatures.totalSteps}")

            // 4. Write immediately to CSV/JSONL and broadcast
            val userId = FirebaseAuth.getInstance().currentUser?.uid ?: ""
            val sessionDir = ActivityTrackingViewModel.activeSessionDir ?: ""
            CsvWriter.writeFeatureVector(featureVector, userId, sessionDir)
            CsvWriter.writeFeatureJsonl(featureVector, userId, sessionDir)
            broadcastResult(featureVector)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process window ${data.sequenceId}: ${e.message}", e)
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
