package com.example.fitguard.data.processing

import android.util.Log

data class PpgSample(val timestamp: Long, val green: Int, val ir: Int = 0, val red: Int = 0)
data class AccelSample(val timestamp: Long, val x: Float, val y: Float, val z: Float)
data class SkinTempSample(val timestamp: Long, val objectTemp: Float, val ambientTemp: Float)

data class SequenceData(
    val sequenceId: String,
    val ppgSamples: List<PpgSample>,
    val accelSamples: List<AccelSample>,
    val skinTempSamples: List<SkinTempSample>
)

class SequenceBatchAccumulator(
    private val onSequenceReady: (data: SequenceData) -> Unit
) {
    companion object {
        private const val TAG = "SeqBatchAccumulator"
    }

    private data class SequenceState(
        val totalBatches: Int,
        val receivedBatches: MutableSet<Int> = mutableSetOf(),
        val ppgSamples: MutableList<PpgSample> = mutableListOf(),
        val accelSamples: MutableList<AccelSample> = mutableListOf(),
        val skinTempSamples: MutableList<SkinTempSample> = mutableListOf()
    )

    private val sequences = mutableMapOf<String, SequenceState>()

    fun addPpgSamples(sequenceId: String, totalBatches: Int, samples: List<PpgSample>) {
        val state = sequences.getOrPut(sequenceId) { SequenceState(totalBatches) }
        synchronized(state) {
            state.ppgSamples.addAll(samples)
        }
    }

    fun addAccelSamples(sequenceId: String, totalBatches: Int, samples: List<AccelSample>) {
        val state = sequences.getOrPut(sequenceId) { SequenceState(totalBatches) }
        synchronized(state) {
            state.accelSamples.addAll(samples)
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
                    "(${state.receivedBatches.size}/$totalBatches complete, " +
                    "${state.ppgSamples.size} PPG, ${state.accelSamples.size} accel, " +
                    "${state.skinTempSamples.size} skinTemp samples)")

            if (state.receivedBatches.size >= totalBatches) {
                Log.d(TAG, "All $totalBatches batches received for $sequenceId, triggering processing")
                val data = SequenceData(
                    sequenceId = sequenceId,
                    ppgSamples = state.ppgSamples.sortedBy { it.timestamp },
                    accelSamples = state.accelSamples.sortedBy { it.timestamp },
                    skinTempSamples = state.skinTempSamples.sortedBy { it.timestamp }
                )
                sequences.remove(sequenceId)
                onSequenceReady(data)
            }
        }
    }
}
