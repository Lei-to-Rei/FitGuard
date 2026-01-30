package com.example.fitguard.services

import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.*
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Service to receive batch health data from watch
 */
class WearableDataListenerService : WearableListenerService() {
    companion object {
        private const val TAG = "WearableDataListener"
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            when (event.dataItem.uri.path) {
                "/health_tracker_batch" -> {
                    if (event.type == DataEvent.TYPE_CHANGED) {
                        processBatchData(DataMapItem.fromDataItem(event.dataItem).dataMap)
                    }
                }
                "/health_tracker_data" -> {
                    // Legacy single-item path (fallback)
                    if (event.type == DataEvent.TYPE_CHANGED) {
                        processHealthData(DataMapItem.fromDataItem(event.dataItem).dataMap.toBundle())
                    }
                }
            }
        }
    }

    /**
     * Process batch data from watch
     */
    private fun processBatchData(dataMap: DataMap) {
        try {
            val batchDataString = dataMap.getString("batch_data") ?: return
            val batchJson = JSONObject(batchDataString)

            val batchId = batchJson.getString("batch_id")
            val sentAt = batchJson.getLong("sent_at")
            val entryCount = batchJson.getInt("entry_count")
            val bufferSizeKB = batchJson.getDouble("buffer_size_kb")
            val receivedAt = System.currentTimeMillis()

            Log.d(TAG, "Received batch: ID=$batchId, Entries=$entryCount, Size=${bufferSizeKB}KB")

            val entries = batchJson.getJSONArray("entries")

            // Save batch metadata
            saveBatchMetadata(batchId, entryCount, bufferSizeKB, sentAt, receivedAt)

            // Process each entry
            for (i in 0 until entries.length()) {
                val entry = entries.getJSONObject(i)
                val type = entry.getString("type")
                val data = entry.getJSONObject("data")
                val timestamp = entry.getLong("timestamp")
                val entryReceivedAt = entry.getLong("received_at")

                // Save to individual type files
                saveToFile(type, data.toString(), timestamp)

                // Broadcast to UI if it's open
                sendBroadcast(Intent("com.example.fitguard.HEALTH_DATA").apply {
                    putExtra("type", type)
                    putExtra("data", data.toString())
                    putExtra("timestamp", timestamp)
                    putExtra("batch_id", batchId)
                })
            }

            // Notify UI of batch completion
            sendBroadcast(Intent("com.example.fitguard.BATCH_RECEIVED").apply {
                putExtra("batch_id", batchId)
                putExtra("entry_count", entryCount)
                putExtra("buffer_size_kb", bufferSizeKB)
            })

            Log.d(TAG, "✓ Batch processed successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error processing batch: ${e.message}", e)
        }
    }

    /**
     * Process single health data (legacy/fallback)
     */
    private fun processHealthData(bundle: android.os.Bundle) {
        val type = bundle.getString("type") ?: return
        val json = JSONObject().apply {
            put("type", type)
            put("timestamp", bundle.getLong("timestamp"))
            put("sent_at", bundle.getLong("sent_at"))
            put("received_at", System.currentTimeMillis())

            when (type) {
                "PPG" -> {
                    put("green", bundle.getInt("green"))
                    put("ir", bundle.getInt("ir"))
                    put("red", bundle.getInt("red"))
                }
                "SpO2" -> {
                    put("spo2", bundle.getInt("spo2"))
                    put("hr", bundle.getInt("heart_rate"))
                    put("status", bundle.getInt("status"))
                }
                "HeartRate" -> {
                    put("hr", bundle.getInt("heart_rate"))
                    put("ibi", bundle.getIntegerArrayList("ibi_list")?.toString())
                    put("status", bundle.getInt("status"))
                }
                "ECG" -> {
                    put("ppg_green", bundle.getInt("ppg_green"))
                    put("sequence", bundle.getInt("sequence"))
                    put("ecg_mv", bundle.getFloat("ecg_mv"))
                    put("lead_off", bundle.getInt("lead_off"))
                    put("max_mv", bundle.getFloat("max_threshold_mv"))
                    put("min_mv", bundle.getFloat("min_threshold_mv"))
                }
                "SkinTemp" -> {
                    put("status", bundle.getInt("status"))
                    if (bundle.containsKey("object_temp")) put("obj", bundle.getFloat("object_temp"))
                    if (bundle.containsKey("ambient_temp")) put("amb", bundle.getFloat("ambient_temp"))
                }
                "BIA" -> {
                    put("bmr", bundle.getFloat("bmr"))
                    put("fat_mass", bundle.getFloat("body_fat_mass"))
                    put("fat_ratio", bundle.getFloat("body_fat_ratio"))
                    put("ffm", bundle.getFloat("fat_free_mass"))
                    put("muscle", bundle.getFloat("muscle_mass"))
                }
                "Sweat" -> {
                    put("loss", bundle.getFloat("sweat_loss"))
                }
            }
        }

        saveToFile(type, json.toString(), json.getLong("timestamp"))
        sendBroadcast(Intent("com.example.fitguard.HEALTH_DATA").apply {
            putExtra("type", type)
            putExtra("data", json.toString())
        })
    }

    /**
     * Save batch metadata
     */
    private fun saveBatchMetadata(
        batchId: String,
        entryCount: Int,
        bufferSizeKB: Double,
        sentAt: Long,
        receivedAt: Long
    ) {
        try {
            val dir = File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                ), "FitGuard_Data"
            )
            dir.mkdirs()

            val metadataFile = File(dir, "batch_metadata.jsonl")
            val metadata = JSONObject().apply {
                put("batch_id", batchId)
                put("entry_count", entryCount)
                put("buffer_size_kb", bufferSizeKB)
                put("sent_at", sentAt)
                put("received_at", receivedAt)
                put("transfer_time_ms", receivedAt - sentAt)
            }

            metadataFile.appendText(metadata.toString() + "\n")
            Log.d(TAG, "Saved batch metadata: $batchId")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to save batch metadata: ${e.message}")
        }
    }

    /**
     * Save data to file
     */
    private fun saveToFile(type: String, data: String, timestamp: Long) {
        try {
            val dir = File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                ), "FitGuard_Data"
            )
            dir.mkdirs()

            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val date = dateFormat.format(Date(timestamp))

            File(dir, "${type}_${date}.jsonl").appendText(data + "\n")
        } catch (e: Exception) {
            Log.e(TAG, "Save failed: ${e.message}")
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

        val nodeClient = Wearable.getNodeClient(this)
        nodeClient.connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { node ->
                val messageClient = Wearable.getMessageClient(this)
                val message = "$batchSizeKB,$transferIntervalMinutes"
                messageClient.sendMessage(node.id, "/batch_settings", message.toByteArray())
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