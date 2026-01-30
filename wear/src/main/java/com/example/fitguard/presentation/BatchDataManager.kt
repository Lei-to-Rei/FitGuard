package com.example.fitguard.presentation

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Manages batch data collection and transfer from watch to phone
 * Transfers data when:
 * 1. Buffer reaches size threshold (KB)
 * 2. Time interval elapses (minutes)
 */
class BatchDataManager(private val context: Context) {
    private val dataBuffer = ConcurrentLinkedQueue<JSONObject>()
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var transferJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    companion object {
        private const val TAG = "BatchDataManager"
        private const val PREFS_NAME = "batch_transfer_prefs"
        private const val KEY_SIZE_THRESHOLD_KB = "size_threshold_kb"
        private const val KEY_TIME_INTERVAL_MINUTES = "time_interval_minutes"

        // Default values
        private const val DEFAULT_SIZE_THRESHOLD_KB = 50 // 50 KB
        private const val DEFAULT_TIME_INTERVAL_MINUTES = 5 // 5 minutes

        // Maximum buffer size to prevent memory issues
        private const val MAX_BUFFER_SIZE_KB = 500 // 500 KB
    }

    init {
        startPeriodicTransfer()
    }

    /**
     * Get current size threshold in KB
     */
    fun getSizeThresholdKB(): Int {
        return prefs.getInt(KEY_SIZE_THRESHOLD_KB, DEFAULT_SIZE_THRESHOLD_KB)
    }

    /**
     * Set size threshold in KB
     */
    fun setSizeThresholdKB(kb: Int) {
        prefs.edit().putInt(KEY_SIZE_THRESHOLD_KB, kb.coerceIn(10, MAX_BUFFER_SIZE_KB)).apply()
        Log.d(TAG, "Size threshold set to: $kb KB")
    }

    /**
     * Get current time interval in minutes
     */
    fun getTimeIntervalMinutes(): Int {
        return prefs.getInt(KEY_TIME_INTERVAL_MINUTES, DEFAULT_TIME_INTERVAL_MINUTES)
    }

    /**
     * Set time interval in minutes
     */
    fun setTimeIntervalMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_TIME_INTERVAL_MINUTES, minutes.coerceIn(1, 60)).apply()
        Log.d(TAG, "Time interval set to: $minutes minutes")

        // Restart periodic transfer with new interval
        transferJob?.cancel()
        startPeriodicTransfer()
    }

    /**
     * Add data to buffer
     */
    fun addData(data: HealthTrackerManager.TrackerData) {
        val json = convertToJSON(data)
        dataBuffer.offer(json)

        val currentSizeKB = getCurrentBufferSizeKB()
        val thresholdKB = getSizeThresholdKB()

        Log.d(TAG, "Buffer: ${dataBuffer.size} items, $currentSizeKB KB / $thresholdKB KB")

        // Check if we should transfer based on size
        if (currentSizeKB >= thresholdKB) {
            Log.d(TAG, "Size threshold reached, initiating transfer")
            scope.launch {
                transferBatch()
            }
        }

        // Safety check: prevent buffer from growing too large
        if (currentSizeKB >= MAX_BUFFER_SIZE_KB) {
            Log.w(TAG, "Buffer approaching max size, forcing transfer")
            scope.launch {
                transferBatch()
            }
        }
    }

    /**
     * Convert tracker data to JSON
     */
    private fun convertToJSON(data: HealthTrackerManager.TrackerData): JSONObject {
        return JSONObject().apply {
            when (data) {
                is HealthTrackerManager.TrackerData.PPGData -> {
                    put("type", "PPG")
                    put("green", data.green ?: 0)
                    put("ir", data.ir ?: 0)
                    put("red", data.red ?: 0)
                    put("timestamp", data.timestamp)
                }
                is HealthTrackerManager.TrackerData.SpO2Data -> {
                    put("type", "SpO2")
                    put("spo2", data.spO2)
                    put("heart_rate", data.heartRate)
                    put("status", data.status)
                    put("timestamp", data.timestamp)
                }
                is HealthTrackerManager.TrackerData.HeartRateData -> {
                    put("type", "HeartRate")
                    put("heart_rate", data.heartRate)
                    put("ibi_list", JSONArray(data.ibiList))
                    put("ibi_status_list", JSONArray(data.ibiStatusList))
                    put("status", data.status)
                    put("timestamp", data.timestamp)
                }
                is HealthTrackerManager.TrackerData.ECGData -> {
                    put("type", "ECG")
                    put("ppg_green", data.ppgGreen)
                    put("sequence", data.sequence)
                    put("ecg_mv", data.ecgMv)
                    put("lead_off", data.leadOff)
                    put("max_threshold_mv", data.maxThresholdMv)
                    put("min_threshold_mv", data.minThresholdMv)
                    put("timestamp", data.timestamp)
                }
                is HealthTrackerManager.TrackerData.SkinTemperatureData -> {
                    put("type", "SkinTemp")
                    put("status", data.status)
                    data.objectTemperature?.let { put("object_temp", it) }
                    data.ambientTemperature?.let { put("ambient_temp", it) }
                    put("timestamp", data.timestamp)
                }
                is HealthTrackerManager.TrackerData.BIAData -> {
                    put("type", "BIA")
                    put("bmr", data.basalMetabolicRate)
                    put("body_fat_mass", data.bodyFatMass)
                    put("body_fat_ratio", data.bodyFatRatio)
                    put("fat_free_mass", data.fatFreeMass)
                    put("muscle_mass", data.skeletalMuscleMass)
                    put("timestamp", data.timestamp)
                }
                is HealthTrackerManager.TrackerData.SweatLossData -> {
                    put("type", "Sweat")
                    put("sweat_loss", data.sweatLoss)
                    put("timestamp", data.timestamp)
                }
            }
        }
    }

    /**
     * Get current buffer size in KB
     */
    private fun getCurrentBufferSizeKB(): Int {
        var totalBytes = 0
        dataBuffer.forEach { json ->
            totalBytes += json.toString().toByteArray().size
        }
        return totalBytes / 1024
    }

    /**
     * Get current settings
     */
    fun getSettings(): Settings {
        return Settings(
            batchSizeKB = getSizeThresholdKB(),
            transferIntervalMinutes = getTimeIntervalMinutes()
        )
    }

    /**
     * Get buffer statistics as formatted string
     */
    fun getBufferStats(): String {
        val stats = BufferStats(
            itemCount = dataBuffer.size,
            sizeKB = getCurrentBufferSizeKB(),
            thresholdKB = getSizeThresholdKB(),
            intervalMinutes = getTimeIntervalMinutes()
        )
        return "📦 Buffer: ${stats.itemCount} items (${stats.sizeKB}/${stats.thresholdKB} KB)\n⏱️ Transfer every ${stats.intervalMinutes} min"
    }

    /**
     * Start periodic transfer based on time interval
     */
    private fun startPeriodicTransfer() {
        transferJob = scope.launch {
            while (isActive) {
                val intervalMs = getTimeIntervalMinutes() * 60 * 1000L
                delay(intervalMs)

                if (dataBuffer.isNotEmpty()) {
                    Log.d(TAG, "Time interval elapsed, initiating transfer")
                    transferBatch()
                }
            }
        }
    }

    /**
     * Transfer batch of data to phone
     */
    private suspend fun transferBatch() = withContext(Dispatchers.IO) {
        if (dataBuffer.isEmpty()) {
            Log.d(TAG, "Buffer empty, nothing to transfer")
            return@withContext
        }

        val batch = mutableListOf<JSONObject>()
        var batchSizeKB = 0
        val maxBatchKB = getSizeThresholdKB()

        // Collect items up to threshold
        while (dataBuffer.isNotEmpty() && batchSizeKB < maxBatchKB) {
            val item = dataBuffer.poll() ?: break
            batch.add(item)
            batchSizeKB += item.toString().toByteArray().size / 1024
        }

        if (batch.isEmpty()) {
            return@withContext
        }

        Log.d(TAG, "Transferring batch: ${batch.size} items, $batchSizeKB KB")

        try {
            // Send all items together (no grouping by type)
            sendBatchToPhone("mixed", batch)

            Log.d(TAG, "✓ Batch transfer complete: ${batch.size} items")

        } catch (e: Exception) {
            Log.e(TAG, "✗ Batch transfer failed: ${e.message}", e)

            // Re-add failed items to buffer
            batch.forEach { dataBuffer.offer(it) }
        }
    }

    /**
     * Send batch data to phone via Data Layer API
     */
    private fun sendBatchToPhone(type: String, items: List<JSONObject>) {
        try {
            // Calculate batch size
            val batchSizeKB = items.sumOf { it.toString().toByteArray().size } / 1024.0

            // Create batch wrapper matching phone's expected format
            val batchWrapper = JSONObject().apply {
                put("batch_id", UUID.randomUUID().toString())
                put("sent_at", System.currentTimeMillis())
                put("entry_count", items.size)
                put("buffer_size_kb", batchSizeKB)

                // Create entries array with proper structure
                val entriesArray = JSONArray()
                items.forEach { item ->
                    val entry = JSONObject().apply {
                        put("type", item.getString("type"))
                        put("data", item) // The full data object
                        put("timestamp", item.getLong("timestamp"))
                        put("received_at", System.currentTimeMillis())
                    }
                    entriesArray.put(entry)
                }
                put("entries", entriesArray)
            }

            val request = PutDataMapRequest.create("/health_tracker_batch").apply {
                dataMap.putString("batch_data", batchWrapper.toString())
                dataMap.putLong("timestamp", System.currentTimeMillis()) // Force update
            }.asPutDataRequest().setUrgent()

            Wearable.getDataClient(context).putDataItem(request)
                .addOnSuccessListener {
                    Log.d(TAG, "✓ Sent batch: $type (${items.size} items, ${String.format("%.1f", batchSizeKB)}KB)")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "✗ Failed to send batch: $type - ${e.message}")
                    throw e // Re-throw to trigger re-add to buffer
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating batch: ${e.message}", e)
            throw e
        }
    }

    /**
     * Manually trigger transfer
     */
    fun forceTransfer() {
        scope.launch {
            Log.d(TAG, "Manual transfer triggered")
            transferBatch()
        }
    }

    /**
     * Clear all buffered data
     */
    fun clearBuffer() {
        val count = dataBuffer.size
        dataBuffer.clear()
        Log.d(TAG, "Buffer cleared: $count items removed")
    }

    /**
     * Cleanup resources (alias for shutdown)
     */
    fun cleanup() {
        shutdown()
    }

    /**
     * Cleanup resources
     */
    fun shutdown() {
        transferJob?.cancel()
        scope.cancel()
        Log.d(TAG, "BatchDataManager shutdown")
    }

    data class Settings(
        val batchSizeKB: Int,
        val transferIntervalMinutes: Int
    )

    data class BufferStats(
        val itemCount: Int,
        val sizeKB: Int,
        val thresholdKB: Int,
        val intervalMinutes: Int
    )
}