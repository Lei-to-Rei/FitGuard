package com.example.fitguard.presentation

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

class SensorSequenceManager(
    private val context: Context,
    private val healthTrackerManager: HealthTrackerManager
) {
    enum class SequencePhase {
        IDLE, SKIN_TEMP, SPO2, CONTINUOUS, SENDING, COMPLETE, CANCELLED
    }

    companion object {
        private const val TAG = "SensorSequenceManager"
        private const val SEQUENCE_DURATION_SECONDS = 100
        private const val SKIN_TEMP_TIMEOUT_SECONDS = 15
        private const val SPO2_TIMEOUT_SECONDS = 60
        private const val SPO2_START_DELAY_SECONDS = 5
        private const val CONTINUOUS_START_DELAY_SECONDS = 40
        private const val MAX_BATCH_BYTES = 75_000
        private const val BATCH_SEND_DELAY_MS = 200L
    }

    private val ppgBuffer = CopyOnWriteArrayList<HealthTrackerManager.TrackerData.PPGData>()
    private val heartRateBuffer = CopyOnWriteArrayList<HealthTrackerManager.TrackerData.HeartRateData>()
    private val spO2Buffer = CopyOnWriteArrayList<HealthTrackerManager.TrackerData.SpO2Data>()
    private val skinTempBuffer = CopyOnWriteArrayList<HealthTrackerManager.TrackerData.SkinTemperatureData>()
    private val accelerometerBuffer = CopyOnWriteArrayList<HealthTrackerManager.TrackerData.AccelerometerData>()

    var onPhaseChanged: ((SequencePhase) -> Unit)? = null
    var onProgress: ((elapsedSeconds: Int, totalSeconds: Int) -> Unit)? = null
    var onComplete: (() -> Unit)? = null

    private var sequenceJob: Job? = null
    private var currentPhase = SequencePhase.IDLE
    private var sequenceId: String = ""

    val isRunning: Boolean get() = currentPhase != SequencePhase.IDLE &&
            currentPhase != SequencePhase.COMPLETE &&
            currentPhase != SequencePhase.CANCELLED

    private val bufferingCallback: (HealthTrackerManager.TrackerData) -> Unit = { data ->
        when (data) {
            is HealthTrackerManager.TrackerData.PPGData -> ppgBuffer.add(data)
            is HealthTrackerManager.TrackerData.HeartRateData -> heartRateBuffer.add(data)
            is HealthTrackerManager.TrackerData.SpO2Data -> spO2Buffer.add(data)
            is HealthTrackerManager.TrackerData.SkinTemperatureData -> skinTempBuffer.add(data)
            is HealthTrackerManager.TrackerData.AccelerometerData -> accelerometerBuffer.add(data)
        }
    }

    fun startSequence() {
        if (isRunning) return

        sequenceId = "seq_${System.currentTimeMillis()}"
        clearBuffers()

        healthTrackerManager.stopAllTrackers()
        healthTrackerManager.onDataCallback = bufferingCallback

        sequenceJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                runSequence()
            } catch (e: CancellationException) {
                Log.d(TAG, "Sequence cancelled")
                setPhase(SequencePhase.CANCELLED)
            } catch (e: Exception) {
                Log.e(TAG, "Sequence error: ${e.message}", e)
                setPhase(SequencePhase.CANCELLED)
            } finally {
                healthTrackerManager.stopAllTrackers()
                healthTrackerManager.restoreDefaultCallback()
            }
        }
    }

    private suspend fun runSequence() {
        val startTime = System.currentTimeMillis()

        // Phase 1: Start Accelerometer + Skin Temp
        setPhase(SequencePhase.SKIN_TEMP)
        Log.d(TAG, "Phase 1: Starting Accelerometer + Skin Temp")

        val accelStarted = healthTrackerManager.startAccelerometerContinuous()
        if (!accelStarted) Log.w(TAG, "Failed to start Accelerometer, continuing")

        val skinTempStarted = healthTrackerManager.startSkinTemperatureOnDemand()
        if (!skinTempStarted) Log.w(TAG, "Failed to start Skin Temp, skipping")

        if (skinTempStarted) {
            // Wait for skin temp reading or timeout
            val skinTempDeadline = System.currentTimeMillis() + SKIN_TEMP_TIMEOUT_SECONDS * 1000L
            while (!hasValidSkinTemp() && System.currentTimeMillis() < skinTempDeadline) {
                delay(500)
                updateProgress(startTime)
            }
            healthTrackerManager.stopTracker(HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND)
            if (!hasValidSkinTemp()) {
                Log.w(TAG, "Skin Temp timed out after ${SKIN_TEMP_TIMEOUT_SECONDS}s")
            } else {
                Log.d(TAG, "Skin Temp reading received")
            }
        }

        // Phase 2: Start SpO2
        setPhase(SequencePhase.SPO2)
        Log.d(TAG, "Phase 2: Starting SpO2")

        // Small delay before starting SpO2
        val elapsedSoFar = (System.currentTimeMillis() - startTime) / 1000
        val spo2Delay = (SPO2_START_DELAY_SECONDS - elapsedSoFar).coerceAtLeast(0)
        if (spo2Delay > 0) delay(spo2Delay * 1000)

        val spo2Started = healthTrackerManager.startSpO2OnDemand()
        if (!spo2Started) Log.w(TAG, "Failed to start SpO2, skipping")

        if (spo2Started) {
            val spo2Deadline = System.currentTimeMillis() + SPO2_TIMEOUT_SECONDS * 1000L
            while (!hasValidSpO2() && System.currentTimeMillis() < spo2Deadline) {
                delay(500)
                updateProgress(startTime)
            }
            healthTrackerManager.stopTracker(HealthTrackerType.SPO2_ON_DEMAND)
            if (!hasValidSpO2()) {
                Log.w(TAG, "SpO2 timed out after ${SPO2_TIMEOUT_SECONDS}s")
            } else {
                Log.d(TAG, "SpO2 reading received")
            }
        }

        // Phase 3: Start PPG + Heart Rate continuous
        setPhase(SequencePhase.CONTINUOUS)
        Log.d(TAG, "Phase 3: Starting PPG + Heart Rate continuous")

        // Wait until T=40s mark before starting optical continuous sensors
        val elapsedBeforeContinuous = (System.currentTimeMillis() - startTime) / 1000
        val continuousDelay = (CONTINUOUS_START_DELAY_SECONDS - elapsedBeforeContinuous).coerceAtLeast(0)
        if (continuousDelay > 0) delay(continuousDelay * 1000)

        val ppgStarted = healthTrackerManager.startPPGContinuous()
        if (!ppgStarted) Log.w(TAG, "Failed to start PPG, continuing")

        val hrStarted = healthTrackerManager.startHeartRateContinuous()
        if (!hrStarted) Log.w(TAG, "Failed to start Heart Rate, continuing")

        // Run until 5-minute mark with progress updates
        while (true) {
            val elapsed = ((System.currentTimeMillis() - startTime) / 1000).toInt()
            if (elapsed >= SEQUENCE_DURATION_SECONDS) break
            updateProgress(startTime)
            delay(1000)
        }

        // Phase 4: Stop all and send data
        Log.d(TAG, "Phase 4: Stopping trackers and sending data")
        healthTrackerManager.stopAllTrackers()

        setPhase(SequencePhase.SENDING)
        batchSendAllData()

        setPhase(SequencePhase.COMPLETE)
        onComplete?.invoke()
    }

    private fun updateProgress(startTime: Long) {
        val elapsed = ((System.currentTimeMillis() - startTime) / 1000).toInt()
            .coerceAtMost(SEQUENCE_DURATION_SECONDS)
        onProgress?.invoke(elapsed, SEQUENCE_DURATION_SECONDS)
    }

    private fun setPhase(phase: SequencePhase) {
        currentPhase = phase
        onPhaseChanged?.invoke(phase)
    }

    fun cancelSequence() {
        sequenceJob?.cancel()
        sequenceJob = null
        healthTrackerManager.stopAllTrackers()
        healthTrackerManager.restoreDefaultCallback()
        clearBuffers()
        setPhase(SequencePhase.CANCELLED)
    }

    private fun clearBuffers() {
        ppgBuffer.clear()
        heartRateBuffer.clear()
        spO2Buffer.clear()
        skinTempBuffer.clear()
        accelerometerBuffer.clear()
    }

    private suspend fun batchSendAllData() {
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

        heartRateBuffer.forEach { data ->
            allEntries.add(JSONObject().apply {
                put("type", "HeartRate")
                put("heart_rate", data.heartRate)
                put("ibi_list", JSONArray(data.ibiList))
                put("ibi_status_list", JSONArray(data.ibiStatusList))
                put("status", data.status)
                put("timestamp", data.timestamp)
            })
        }

        spO2Buffer.forEach { data ->
            allEntries.add(JSONObject().apply {
                put("type", "SpO2")
                put("spo2", data.spO2)
                put("heart_rate", data.heartRate)
                put("status", data.status)
                put("timestamp", data.timestamp)
            })
        }

        skinTempBuffer.forEach { data ->
            allEntries.add(JSONObject().apply {
                put("type", "SkinTemp")
                put("status", data.status)
                put("object_temp", data.objectTemperature ?: 0f)
                put("ambient_temp", data.ambientTemperature ?: 0f)
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
            Log.w(TAG, "No data to send")
            return
        }

        // Split into byte-size-based batches (max ~75KB JSON per batch)
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
        Log.d(TAG, "Sending $totalPoints data points in $totalBatches batches")

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

        Log.d(TAG, "Batch send complete")
    }

    private fun hasValidSpO2(): Boolean {
        return spO2Buffer.any { it.spO2 > 0 }
    }

    private fun hasValidSkinTemp(): Boolean {
        return skinTempBuffer.any { it.objectTemperature != null }
    }
}
