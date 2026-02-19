package com.example.fitguard.data.processing

import android.content.Context
import android.content.Intent
import android.util.Log
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
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    val accumulator = SequenceBatchAccumulator { data ->
        processSequence(data)
    }

    private fun processSequence(data: SequenceData) {
        scope.launch {
            try {
                Log.d(TAG, "Processing sequence ${data.sequenceId} with " +
                        "${data.ppgSamples.size} PPG, ${data.accelSamples.size} accel, " +
                        "${data.skinTempSamples.size} skinTemp samples")

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

                // 3. Extract skin temp values (29-31)
                val validSkinTemps = data.skinTempSamples.filter {
                    it.objectTemp > 0 && it.ambientTemp > 0
                }
                val skinTempObj = if (validSkinTemps.isNotEmpty()) {
                    validSkinTemps.map { it.objectTemp.toDouble() }.average()
                } else 0.0
                val skinTempAmbient = if (validSkinTemps.isNotEmpty()) {
                    validSkinTemps.map { it.ambientTemp.toDouble() }.average()
                } else 0.0
                val skinTempDelta = skinTempObj - skinTempAmbient

                // 4. Assemble FeatureVector
                val featureVector = FeatureVector(
                    timestamp = System.currentTimeMillis(),
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
                    skinTempObj = skinTempObj,
                    skinTempDelta = skinTempDelta,
                    skinTempAmbient = skinTempAmbient,
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
                        "Steps=${accelFeatures.totalSteps} SkinT=${String.format("%.1f", skinTempObj)}")

                // 5. Write to CSV
                CsvWriter.writeFeatureVector(featureVector)

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
            putExtra("skin_temp_obj", fv.skinTempObj)
            putExtra("total_steps", fv.totalSteps)
            putExtra("cadence_spm", fv.cadenceSpm)
        }
        context.sendBroadcast(intent)
        Log.d(TAG, "Broadcast SEQUENCE_PROCESSED for ${fv.sequenceId}")
    }
}
