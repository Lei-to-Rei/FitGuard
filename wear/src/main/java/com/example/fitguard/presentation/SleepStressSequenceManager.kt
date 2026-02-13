package com.example.fitguard.presentation

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

class SleepStressSequenceManager(
    private val context: Context,
    private val healthTrackerManager: HealthTrackerManager
) {
    companion object {
        private const val TAG = "SleepStressSeqMgr"
        private const val COLLECTION_SECONDS = 30
        private const val REST_SECONDS = 30
        private const val MAX_BATCH_BYTES = 75_000
        private const val BATCH_SEND_DELAY_MS = 200L
    }

    private val ppgBuffer = CopyOnWriteArrayList<HealthTrackerManager.TrackerData.PPGData>()
    private val accelerometerBuffer = CopyOnWriteArrayList<HealthTrackerManager.TrackerData.AccelerometerData>()

    private var repeatingJob: Job? = null
    private var cycleCount = 0

    val isRunning: Boolean get() = repeatingJob?.isActive == true

    private val bufferingCallback: (HealthTrackerManager.TrackerData) -> Unit = { data ->
        when (data) {
            is HealthTrackerManager.TrackerData.PPGData -> ppgBuffer.add(data)
            is HealthTrackerManager.TrackerData.AccelerometerData -> accelerometerBuffer.add(data)
            else -> { /* only PPG + Accel needed for sleep/stress */ }
        }
    }

    fun startRepeatingSequence() {
        if (isRunning) return
        cycleCount = 0
        Log.d(TAG, "Starting repeating sleep/stress sequence")

        repeatingJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                // Start sensors ONCE before the loop to avoid re-acquiring tracker instances
                healthTrackerManager.onDataCallback = bufferingCallback
                val ppgOk = healthTrackerManager.startPPGContinuous()
                val accelOk = healthTrackerManager.startAccelerometerContinuous()
                Log.d(TAG, "Sensors started once - PPG:$ppgOk Accel:$accelOk")

                while (isActive) {
                    cycleCount++
                    clearBuffers()
                    Log.d(TAG, "Cycle $cycleCount: collecting for ${COLLECTION_SECONDS}s")
                    delay(COLLECTION_SECONDS * 1000L)

                    val sequenceId = "ss_${System.currentTimeMillis()}"
                    batchSendAllData(sequenceId)

                    Log.d(TAG, "Cycle $cycleCount: resting for ${REST_SECONDS}s")
                    delay(REST_SECONDS * 1000L)
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Repeating sequence cancelled after $cycleCount cycles")
            } catch (e: Exception) {
                Log.e(TAG, "Repeating sequence error: ${e.message}", e)
            } finally {
                healthTrackerManager.stopAllTrackers()
                healthTrackerManager.restoreDefaultCallback()
                SessionManager.endSession()
            }
        }
    }

    fun stopRepeatingSequence() {
        Log.d(TAG, "Stopping repeating sequence (ran $cycleCount cycles)")
        repeatingJob?.cancel()
        repeatingJob = null
        healthTrackerManager.stopAllTrackers()
        healthTrackerManager.restoreDefaultCallback()
    }

    private fun clearBuffers() {
        ppgBuffer.clear()
        accelerometerBuffer.clear()
    }

    private suspend fun batchSendAllData(sequenceId: String) {
        val allEntries = mutableListOf<JSONObject>()

        ppgBuffer.forEach { data ->
            allEntries.add(JSONObject().apply {
                put("type", "PPG")
                put("green", data.green ?: 0)
                put("ir", data.ir ?: 0)
                put("red", data.red ?: 0)
                put("timestamp", data.timestamp)
            })
        }

        accelerometerBuffer.forEach { data ->
            allEntries.add(JSONObject().apply {
                put("type", "Accelerometer")
                put("x", data.x ?: 0)
                put("y", data.y ?: 0)
                put("z", data.z ?: 0)
                put("timestamp", data.timestamp)
            })
        }

        val totalPoints = allEntries.size
        if (totalPoints == 0) {
            Log.w(TAG, "No data collected in cycle $cycleCount")
            return
        }

        val batches = mutableListOf<JSONArray>()
        var currentBatch = JSONArray()
        var currentBatchBytes = 0

        for (entry in allEntries) {
            val entryBytes = entry.toString().toByteArray(Charsets.UTF_8).size
            if (currentBatchBytes + entryBytes > MAX_BATCH_BYTES && currentBatch.length() > 0) {
                batches.add(currentBatch)
                currentBatch = JSONArray()
                currentBatchBytes = 0
            }
            currentBatch.put(entry)
            currentBatchBytes += entryBytes
        }
        if (currentBatch.length() > 0) {
            batches.add(currentBatch)
        }

        val totalBatches = batches.size
        Log.d(TAG, "Sending $totalPoints points in $totalBatches batches (cycle $cycleCount)")

        for ((index, batch) in batches.withIndex()) {
            val batchNumber = index + 1
            try {
                val metadata = JSONObject().apply {
                    put("sequence_id", sequenceId)
                    put("batch_number", batchNumber)
                    put("total_batches", totalBatches)
                    put("points_in_batch", batch.length())
                    put("total_points", totalPoints)
                    put("sent_at", System.currentTimeMillis())
                }

                val payload = JSONObject().apply {
                    put("metadata", metadata)
                    put("data", batch)
                }

                val request = PutDataMapRequest.create("/health_tracker_batch").apply {
                    dataMap.putString("batch_json", payload.toString())
                    dataMap.putLong("unique_key", System.currentTimeMillis() + batchNumber)
                }.asPutDataRequest().setUrgent()

                Wearable.getDataClient(context).putDataItem(request)
                    .addOnSuccessListener {
                        Log.d(TAG, "Batch $batchNumber/$totalBatches sent (${batch.length()} points)")
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Batch $batchNumber/$totalBatches failed: ${e.message}")
                    }

                if (batchNumber < totalBatches) {
                    delay(BATCH_SEND_DELAY_MS)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending batch $batchNumber: ${e.message}", e)
            }
        }
    }
}
