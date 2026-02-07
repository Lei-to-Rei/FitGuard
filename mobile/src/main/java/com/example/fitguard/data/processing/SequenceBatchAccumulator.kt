package com.example.fitguard.data.processing

import android.util.Log

data class PpgSample(val timestamp: Long, val green: Int)

class SequenceBatchAccumulator(
    private val onSequenceReady: (sequenceId: String, samples: List<PpgSample>, totalBatches: Int) -> Unit
) {
    companion object {
        private const val TAG = "SeqBatchAccumulator"
    }

    private data class SequenceState(
        val totalBatches: Int,
        val receivedBatches: MutableSet<Int> = mutableSetOf(),
        val ppgSamples: MutableList<PpgSample> = mutableListOf()
    )

    private val sequences = mutableMapOf<String, SequenceState>()

    fun addPpgSamples(sequenceId: String, totalBatches: Int, samples: List<PpgSample>) {
        val state = sequences.getOrPut(sequenceId) { SequenceState(totalBatches) }
        synchronized(state) {
            state.ppgSamples.addAll(samples)
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
                val sorted = state.ppgSamples.sortedBy { it.timestamp }
                sequences.remove(sequenceId)
                onSequenceReady(sequenceId, sorted, totalBatches)
            }
        }
    }
}
