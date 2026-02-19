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
        IDLE, SKIN_TEMP, CONTINUOUS, SENDING, COMPLETE, CANCELLED
    }

    companion object {
        private const val TAG = "SensorSequenceManager"
        private const val CONTINUOUS_DURATION_SECONDS = 60
        private const val SKIN_TEMP_TIMEOUT_SECONDS = 15
        private const val MAX_BATCH_BYTES = 75_000
        private const val BATCH_SEND_DELAY_MS = 200L
    }

    private val ppgBuffer = CopyOnWriteArrayList<HealthTrackerManager.TrackerData.PPGData>()
    private val skinTempBuffer = CopyOnWriteArrayList<HealthTrackerManager.TrackerData.SkinTemperatureData>()
    private val accelerometerBuffer = CopyOnWriteArrayList<HealthTrackerManager.TrackerData.AccelerometerData>()

    var onPhaseChanged: ((SequencePhase) -> Unit)? = null
    var onProgress: ((elapsedSeconds: Int, totalSeconds: Int) -> Unit)? = null
    var onComplete: (() -> Unit)? = null
    var onSequenceLoopComplete: ((sequenceCount: Int) -> Unit)? = null
    var onRpePromptNeeded: ((sequenceCount: Int) -> Unit)? = null

    var rpeIntervalSequences: Int = 8  // default ~10 min at 77s/seq

    private var sequenceJob: Job? = null
    private var currentPhase = SequencePhase.IDLE
    private var sequenceId: String = ""

    // Continuous session fields
    var isContinuousMode: Boolean = false
        private set
    var activityType: String = ""
        private set
    var sessionId: String = ""
        private set
    var sequenceCount: Int = 0
        private set

    val isRunning: Boolean get() = currentPhase != SequencePhase.IDLE &&
            currentPhase != SequencePhase.COMPLETE &&
            currentPhase != SequencePhase.CANCELLED

    private val bufferingCallback: (HealthTrackerManager.TrackerData) -> Unit = { data ->
        when (data) {
            is HealthTrackerManager.TrackerData.PPGData -> ppgBuffer.add(data)
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

    fun startContinuousSession(sessionId: String, activityType: String) {
        if (isRunning) return

        this.isContinuousMode = true
        this.sessionId = sessionId
        this.activityType = activityType
        this.sequenceCount = 0

        clearBuffers()
        healthTrackerManager.stopAllTrackers()
        healthTrackerManager.onDataCallback = bufferingCallback

        sequenceJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                while (isActive) {
                    sequenceId = "${sessionId}_seq_${System.currentTimeMillis()}"
                    runSequence()
                    sequenceCount++
                    onSequenceLoopComplete?.invoke(sequenceCount)
                    if (sequenceCount > 0 && sequenceCount % rpeIntervalSequences == 0) {
                        onRpePromptNeeded?.invoke(sequenceCount)
                    }
                    delay(2000)
                    clearBuffers()
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Continuous session cancelled after $sequenceCount sequences")
                setPhase(SequencePhase.CANCELLED)
            } catch (e: Exception) {
                Log.e(TAG, "Continuous session error: ${e.message}", e)
                setPhase(SequencePhase.CANCELLED)
            } finally {
                healthTrackerManager.stopAllTrackers()
                healthTrackerManager.restoreDefaultCallback()
                isContinuousMode = false
            }
        }
    }

    private suspend fun runSequence() {
        val startTime = System.currentTimeMillis()
        val totalSeconds = CONTINUOUS_DURATION_SECONDS + SKIN_TEMP_TIMEOUT_SECONDS

        // Phase 1: PPG + Accelerometer for 60s
        setPhase(SequencePhase.CONTINUOUS)
        Log.d(TAG, "Phase 1: Starting PPG + Accelerometer for ${CONTINUOUS_DURATION_SECONDS}s")

        val ppgStarted = healthTrackerManager.startPPGContinuous()
        if (!ppgStarted) Log.w(TAG, "Failed to start PPG, continuing")

        val accelStarted = healthTrackerManager.startAccelerometerContinuous()
        if (!accelStarted) Log.w(TAG, "Failed to start Accelerometer, continuing")

        // Run for 60s with progress updates
        while (true) {
            val elapsed = ((System.currentTimeMillis() - startTime) / 1000).toInt()
            if (elapsed >= CONTINUOUS_DURATION_SECONDS) break
            onProgress?.invoke(elapsed, totalSeconds)
            delay(1000)
        }

        // Stop continuous trackers
        healthTrackerManager.stopTracker(HealthTrackerType.PPG_CONTINUOUS)
        healthTrackerManager.stopTracker(HealthTrackerType.ACCELEROMETER_CONTINUOUS)
        Log.d(TAG, "Phase 1 complete, stopped PPG + Accelerometer")

        // Phase 2: Skin Temperature
        setPhase(SequencePhase.SKIN_TEMP)
        Log.d(TAG, "Phase 2: Starting Skin Temp")

        val skinTempStarted = healthTrackerManager.startSkinTemperatureOnDemand()
        if (!skinTempStarted) Log.w(TAG, "Failed to start Skin Temp, skipping")

        if (skinTempStarted) {
            val skinTempDeadline = System.currentTimeMillis() + SKIN_TEMP_TIMEOUT_SECONDS * 1000L
            while (!hasValidSkinTemp() && System.currentTimeMillis() < skinTempDeadline) {
                val elapsed = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                onProgress?.invoke(elapsed, totalSeconds)
                delay(500)
            }
            healthTrackerManager.stopTracker(HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND)
            if (!hasValidSkinTemp()) {
                Log.w(TAG, "Skin Temp timed out after ${SKIN_TEMP_TIMEOUT_SECONDS}s")
            } else {
                Log.d(TAG, "Skin Temp reading received")
            }
        }

        // Phase 3: Stop all and send data
        Log.d(TAG, "Phase 3: Sending data")
        healthTrackerManager.stopAllTrackers()

        setPhase(SequencePhase.SENDING)
        batchSendAllData()

        if (!isContinuousMode) {
            setPhase(SequencePhase.COMPLETE)
            onComplete?.invoke()
        }
    }

    private fun setPhase(phase: SequencePhase) {
        currentPhase = phase
        onPhaseChanged?.invoke(phase)
    }

    fun cancelSequence() {
        val wasContinuous = isContinuousMode
        sequenceJob?.cancel()
        sequenceJob = null
        healthTrackerManager.stopAllTrackers()
        healthTrackerManager.restoreDefaultCallback()
        clearBuffers()
        isContinuousMode = false
        setPhase(SequencePhase.CANCELLED)
    }

    private fun clearBuffers() {
        ppgBuffer.clear()
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
                    if (isContinuousMode) {
                        put("activity_type", activityType)
                        put("session_id", sessionId)
                    }
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

    private fun hasValidSkinTemp(): Boolean {
        return skinTempBuffer.any { it.objectTemperature != null }
    }
}
