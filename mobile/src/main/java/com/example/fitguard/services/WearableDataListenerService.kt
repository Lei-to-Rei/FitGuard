package com.example.fitguard.services

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import com.google.android.gms.wearable.*
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Service to receive batch health data from watch
 *
 * FIXES:
 * - Removed wake lock (causing SecurityException)
 * - Added proper error handling
 * - Uses app-specific storage
 */
class WearableDataListenerService : WearableListenerService() {

    companion object {
        private const val TAG = "WearableDataListener"

        // Broadcast actions
        const val ACTION_HEALTH_DATA = "com.example.fitguard.HEALTH_DATA"
        const val ACTION_BATCH_RECEIVED = "com.example.fitguard.BATCH_RECEIVED"

        // Data paths
        private const val PATH_HEALTH_BATCH = "/health_tracker_batch"
        private const val PATH_HEALTH_DATA = "/health_tracker_data"
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        try {
            dataEvents.forEach { event ->
                if (event.type != DataEvent.TYPE_CHANGED) return@forEach

                when (event.dataItem.uri.path) {
                    PATH_HEALTH_BATCH -> {
                        processBatchData(DataMapItem.fromDataItem(event.dataItem).dataMap)
                    }
                    PATH_HEALTH_DATA -> {
                        // Legacy single-item path (fallback)
                        processHealthData(DataMapItem.fromDataItem(event.dataItem).dataMap.toBundle())
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing data events: ${e.message}", e)
        }
    }

    /**
     * Process batch data from watch with robust error handling
     */
    private fun processBatchData(dataMap: DataMap) {
        try {
            val batchDataString = dataMap.getString("batch_data")
            if (batchDataString.isNullOrEmpty()) {
                Log.w(TAG, "Empty batch data received")
                return
            }

            val batchJson = try {
                JSONObject(batchDataString)
            } catch (e: JSONException) {
                Log.e(TAG, "Invalid JSON in batch data: ${e.message}")
                return
            }

            val batchId = batchJson.optString("batch_id", UUID.randomUUID().toString())
            val sentAt = batchJson.optLong("sent_at", System.currentTimeMillis())
            val entryCount = batchJson.optInt("entry_count", 0)
            val bufferSizeKB = batchJson.optDouble("buffer_size_kb", 0.0)
            val receivedAt = System.currentTimeMillis()

            Log.d(TAG, "Received batch: ID=$batchId, Entries=$entryCount, Size=${bufferSizeKB}KB")

            val entries = batchJson.optJSONArray("entries")
            if (entries == null || entries.length() == 0) {
                Log.w(TAG, "No entries in batch")
                return
            }

            // Save batch metadata
            saveBatchMetadata(batchId, entryCount, bufferSizeKB, sentAt, receivedAt)

            // Process each entry with individual error handling
            var successCount = 0
            var errorCount = 0

            for (i in 0 until entries.length()) {
                try {
                    val entry = entries.optJSONObject(i) ?: continue
                    val type = entry.optString("type", "Unknown")
                    val data = entry.optJSONObject("data") ?: entry
                    val timestamp = entry.optLong("timestamp", System.currentTimeMillis())

                    // Validate data before saving
                    if (isValidHealthData(type, data)) {
                        // Save to individual type files
                        saveToFile(type, data.toString(), timestamp)

                        // Broadcast to UI if it's open
                        sendHealthDataBroadcast(type, data.toString(), timestamp, batchId)
                        successCount++
                    } else {
                        Log.w(TAG, "Invalid health data for type: $type")
                        errorCount++
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing entry $i: ${e.message}")
                    errorCount++
                }
            }

            // Notify UI of batch completion
            sendBatchCompleteBroadcast(batchId, entryCount, bufferSizeKB, successCount, errorCount)

            Log.d(TAG, "✓ Batch processed: $successCount success, $errorCount errors")

        } catch (e: Exception) {
            Log.e(TAG, "Error processing batch: ${e.message}", e)
        }
    }

    /**
     * Validate health data before processing
     */
    private fun isValidHealthData(type: String, data: JSONObject): Boolean {
        return when (type) {
            "PPG" -> data.has("green") || data.has("ir") || data.has("red")
            "SpO2" -> data.has("spo2") || data.has("hr")
            "HeartRate" -> data.has("hr") || data.has("heart_rate")
            "ECG" -> data.has("ecg_mv") || data.has("sequence")
            "SkinTemp" -> data.has("obj") || data.has("amb") || data.has("object_temp")
            "BIA" -> data.has("bmr") || data.has("fat_ratio")
            "Sweat" -> data.has("loss") || data.has("sweat_loss")
            else -> true // Accept unknown types
        }
    }

    /**
     * Send broadcast with explicit package targeting (Android 14+ requirement)
     */
    private fun sendHealthDataBroadcast(type: String, data: String, timestamp: Long, batchId: String) {
        val intent = Intent(ACTION_HEALTH_DATA).apply {
            putExtra("type", type)
            putExtra("data", data)
            putExtra("timestamp", timestamp)
            putExtra("batch_id", batchId)
            // Set package for Android 14+ security
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun sendBatchCompleteBroadcast(
        batchId: String,
        entryCount: Int,
        bufferSizeKB: Double,
        successCount: Int,
        errorCount: Int
    ) {
        val intent = Intent(ACTION_BATCH_RECEIVED).apply {
            putExtra("batch_id", batchId)
            putExtra("entry_count", entryCount)
            putExtra("buffer_size_kb", bufferSizeKB)
            putExtra("success_count", successCount)
            putExtra("error_count", errorCount)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    /**
     * Process single health data (legacy/fallback)
     */
    private fun processHealthData(bundle: android.os.Bundle) {
        val type = bundle.getString("type") ?: return

        val json = try {
            JSONObject().apply {
                put("type", type)
                put("timestamp", bundle.getLong("timestamp", System.currentTimeMillis()))
                put("sent_at", bundle.getLong("sent_at", System.currentTimeMillis()))
                put("received_at", System.currentTimeMillis())

                when (type) {
                    "PPG" -> {
                        put("green", bundle.getInt("green", 0))
                        put("ir", bundle.getInt("ir", 0))
                        put("red", bundle.getInt("red", 0))
                    }
                    "SpO2" -> {
                        put("spo2", bundle.getInt("spo2", 0))
                        put("hr", bundle.getInt("heart_rate", 0))
                        put("status", bundle.getInt("status", 0))
                    }
                    "HeartRate" -> {
                        put("hr", bundle.getInt("heart_rate", 0))
                        put("ibi", bundle.getIntegerArrayList("ibi_list")?.toString() ?: "[]")
                        put("status", bundle.getInt("status", 0))
                    }
                    "ECG" -> {
                        put("ppg_green", bundle.getInt("ppg_green", 0))
                        put("sequence", bundle.getInt("sequence", 0))
                        put("ecg_mv", bundle.getFloat("ecg_mv", 0f))
                        put("lead_off", bundle.getInt("lead_off", 0))
                        put("max_mv", bundle.getFloat("max_threshold_mv", 0f))
                        put("min_mv", bundle.getFloat("min_threshold_mv", 0f))
                    }
                    "SkinTemp" -> {
                        put("status", bundle.getInt("status", 0))
                        if (bundle.containsKey("object_temp")) put("obj", bundle.getFloat("object_temp"))
                        if (bundle.containsKey("ambient_temp")) put("amb", bundle.getFloat("ambient_temp"))
                    }
                    "BIA" -> {
                        put("bmr", bundle.getFloat("bmr", 0f))
                        put("fat_mass", bundle.getFloat("body_fat_mass", 0f))
                        put("fat_ratio", bundle.getFloat("body_fat_ratio", 0f))
                        put("ffm", bundle.getFloat("fat_free_mass", 0f))
                        put("muscle", bundle.getFloat("muscle_mass", 0f))
                    }
                    "Sweat" -> {
                        put("loss", bundle.getFloat("sweat_loss", 0f))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating JSON for legacy data: ${e.message}")
            return
        }

        saveToFile(type, json.toString(), json.optLong("timestamp", System.currentTimeMillis()))
        sendHealthDataBroadcast(type, json.toString(), json.optLong("timestamp"), "legacy")
    }

    /**
     * Save batch metadata with error handling
     */
    private fun saveBatchMetadata(
        batchId: String,
        entryCount: Int,
        bufferSizeKB: Double,
        sentAt: Long,
        receivedAt: Long
    ) {
        try {
            val metadata = JSONObject().apply {
                put("batch_id", batchId)
                put("entry_count", entryCount)
                put("buffer_size_kb", bufferSizeKB)
                put("sent_at", sentAt)
                put("received_at", receivedAt)
                put("transfer_time_ms", receivedAt - sentAt)
            }

            saveToFile("batch_metadata", metadata.toString(), receivedAt)
            Log.d(TAG, "Saved batch metadata: $batchId")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to save batch metadata: ${e.message}")
        }
    }

    /**
     * Save data to file using app-specific storage (more reliable, no permissions needed)
     */
    private fun saveToFile(type: String, data: String, timestamp: Long) {
        try {
            // Use app-specific external storage (no permissions needed on Android 10+)
            val dir = getExternalFilesDir("FitGuard_Data")
                ?: File(filesDir, "FitGuard_Data")

            if (!dir.exists()) {
                dir.mkdirs()
            }

            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val date = dateFormat.format(Date(timestamp))
            val fileName = "${type}_${date}.jsonl"

            val file = File(dir, fileName)
            file.appendText(data + "\n")

            Log.d(TAG, "Saved to: ${file.absolutePath}")

            // Also try to save to Downloads for user access (best effort)
            saveToDownloadsFolder(type, data, timestamp)

        } catch (e: Exception) {
            Log.e(TAG, "Save failed: ${e.message}", e)
        }
    }

    /**
     * Save to Downloads folder for easy user access (best effort)
     */
    private fun saveToDownloadsFolder(type: String, data: String, timestamp: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Use MediaStore for Android 10+
                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val date = dateFormat.format(Date(timestamp))
                val fileName = "FitGuard_${type}_${date}.jsonl"

                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/jsonl")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FitGuard_Data")
                }

                // Check if file exists, if so append to it
                val existingUri = contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Downloads._ID),
                    "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                    arrayOf(fileName),
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                        android.content.ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, id
                        )
                    } else null
                }

                val uri = existingUri ?: contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    contentValues
                )

                uri?.let {
                    contentResolver.openOutputStream(it, "wa")?.use { os ->
                        os.write((data + "\n").toByteArray())
                    }
                }
            } else {
                // Legacy approach for Android 9 and below
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "FitGuard_Data"
                )
                dir.mkdirs()

                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val date = dateFormat.format(Date(timestamp))

                File(dir, "${type}_${date}.jsonl").appendText(data + "\n")
            }
        } catch (e: Exception) {
            // Best effort - don't fail if Downloads save fails
            Log.w(TAG, "Could not save to Downloads: ${e.message}")
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            "/request_settings" -> {
                // Watch is requesting current batch settings
                sendSettingsToWatch()
            }
        }
    }

    /**
     * Send current batch settings to watch
     */
    private fun sendSettingsToWatch() {
        val prefs = getSharedPreferences("batch_settings", MODE_PRIVATE)
        val batchSizeKB = prefs.getInt("batch_size_kb", 50)
        val transferIntervalMinutes = prefs.getInt("transfer_interval_minutes", 5)

        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { node ->
                val message = "$batchSizeKB,$transferIntervalMinutes"
                Wearable.getMessageClient(this)
                    .sendMessage(node.id, "/batch_settings", message.toByteArray())
                    .addOnSuccessListener {
                        Log.d(TAG, "Sent settings to watch: ${batchSizeKB}KB, ${transferIntervalMinutes}min")
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Failed to send settings: ${e.message}")
                    }
            }
        }
    }
}