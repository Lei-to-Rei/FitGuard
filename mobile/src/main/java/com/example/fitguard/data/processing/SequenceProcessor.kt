package com.example.fitguard.data.processing

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SequenceProcessor(private val context: Context) {
    companion object {
        private const val TAG = "SequenceProcessor"
        const val ACTION_SEQUENCE_PROCESSED = "com.example.fitguard.SEQUENCE_PROCESSED"
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    val accumulator = SequenceBatchAccumulator { sequenceId, samples, spo2Samples, skinTempSamples, totalBatches ->
        processSequence(sequenceId, samples, spo2Samples, skinTempSamples)
    }

    private fun processSequence(sequenceId: String, samples: List<PpgSample>,
                                spo2Samples: List<SpO2Sample>, skinTempSamples: List<SkinTempSample>) {
        scope.launch {
            try {
                Log.d(TAG, "Processing sequence $sequenceId with ${samples.size} PPG samples")

                if (samples.size < 10) {
                    Log.w(TAG, "Too few PPG samples (${samples.size}), skipping processing")
                    return@launch
                }

                val greenValues = IntArray(samples.size) { samples[it].green }
                val timestamps = LongArray(samples.size) { samples[it].timestamp }

                val (hrvResult, processedSamples) = PpgProcessor.process(
                    greenValues, timestamps, sequenceId
                )

                Log.d(TAG, "HRV results for $sequenceId: HR=${String.format("%.1f", hrvResult.meanHrBpm)} " +
                        "SDNN=${String.format("%.2f", hrvResult.sdnnMs)} " +
                        "RMSSD=${String.format("%.2f", hrvResult.rmssdMs)} " +
                        "peaks=${hrvResult.peaksDetected} nn=${hrvResult.nnIntervalsUsed}")

                val bestSpO2 = spo2Samples.firstOrNull { it.spo2 > 0 }
                val bestSkinTemp = skinTempSamples.firstOrNull { !it.objectTemp.isNaN() }

                HrvCsvWriter.writeSequenceData(hrvResult, processedSamples, bestSpO2, bestSkinTemp)

                broadcastResult(hrvResult)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process sequence $sequenceId: ${e.message}", e)
            }
        }
    }

    private fun broadcastResult(result: HrvResult) {
        val intent = Intent(ACTION_SEQUENCE_PROCESSED).apply {
            putExtra("sequence_id", result.sequenceId)
            putExtra("duration_seconds", result.durationSeconds)
            putExtra("total_samples", result.totalSamples)
            putExtra("peaks_detected", result.peaksDetected)
            putExtra("nn_intervals_used", result.nnIntervalsUsed)
            putExtra("mean_hr_bpm", result.meanHrBpm)
            putExtra("sdnn_ms", result.sdnnMs)
            putExtra("rmssd_ms", result.rmssdMs)
            putExtra("pnn20_pct", result.pnn20Pct)
            putExtra("pnn50_pct", result.pnn50Pct)
            putExtra("sdsd_ms", result.sdsdMs)
        }
        context.sendBroadcast(intent)
        Log.d(TAG, "Broadcast SEQUENCE_PROCESSED for ${result.sequenceId}")
    }
}
