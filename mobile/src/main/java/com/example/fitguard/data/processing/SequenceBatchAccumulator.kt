package com.example.fitguard.data.processing

import android.util.Log

data class PpgSample(val timestamp: Long, val green: Int)
data class SpO2Sample(val spo2: Int, val heartRate: Int, val status: Int, val timestamp: Long)
data class SkinTempSample(val objectTemp: Float, val ambientTemp: Float, val status: Int, val timestamp: Long)

class SequenceBatchAccumulator(
    private val onSequenceReady: (sequenceId: String, ppgSamples: List<PpgSample>,
                                  spo2Samples: List<SpO2Sample>, skinTempSamples: List<SkinTempSample>,
                                  totalBatches: Int) -> Unit
) {
    companion object {
        private const val TAG = "SeqBatchAccumulator"
    }

    private data class SequenceState(
        val totalBatches: Int,
        val receivedBatches: MutableSet<Int> = mutableSetOf(),
        val ppgSamples: MutableList<PpgSample> = mutableListOf(),
        val spo2Samples: MutableList<SpO2Sample> = mutableListOf(),
        val skinTempSamples: MutableList<SkinTempSample> = mutableListOf()
    )

    private val sequences = mutableMapOf<String, SequenceState>()

    fun addPpgSamples(sequenceId: String, totalBatches: Int, samples: List<PpgSample>) {
        val state = sequences.getOrPut(sequenceId) { SequenceState(totalBatches) }
        synchronized(state) {
            state.ppgSamples.addAll(samples)
        }
    }

    fun addSpO2Samples(sequenceId: String, totalBatches: Int, samples: List<SpO2Sample>) {
        val state = sequences.getOrPut(sequenceId) { SequenceState(totalBatches) }
        synchronized(state) {
            state.spo2Samples.addAll(samples)
        }
    }

    fun addSkinTempSamples(sequenceId: String, totalBatches: Int, samples: List<SkinTempSample>) {
        val state = sequences.getOrPut(sequenceId) { SequenceState(totalBatches) }
        synchronized(state) {
            state.skinTempSamples.addAll(samples)
        }
    }

    fun markBatchReceived(sequenceId: String, batchNumber: Int, totalBatches: Int) {
        val state = sequences.getOrPut(sequenceId) { SequenceState(totalBatches) }
        synchronized(state) {
            state.receivedBatches.add(batchNumber)
            Log.d(TAG, "Batch $batchNumber/$totalBatches received for $sequenceId " +
                    "(${state.receivedBatches.size}/$totalBatches complete, ${state.ppgSamples.size} PPG samples)")

            if (state.receivedBatches.size >= totalBatches) {
                Log.d(TAG, "All $totalBatches batches received for $sequenceId, triggering processing")
                val sortedPpg = state.ppgSamples.sortedBy { it.timestamp }
                val spo2 = state.spo2Samples.toList()
                val skinTemp = state.skinTempSamples.toList()
                sequences.remove(sequenceId)
                onSequenceReady(sequenceId, sortedPpg, spo2, skinTemp, totalBatches)
            }
        }
    }
}
