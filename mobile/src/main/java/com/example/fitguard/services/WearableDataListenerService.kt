package com.example.fitguard.services

import android.util.Log
import com.google.android.gms.wearable.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class WearableDataListenerService : WearableListenerService() {
    companion object {
        private const val TAG = "WearableDataListener"
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED) {
                val path = event.dataItem.uri.path
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                when (path) {
                    "/health_tracker_data" -> processHealthData(dataMap.toBundle())
                    "/health_tracker_batch" -> processBatchData(dataMap)
                }
            }
        }
    }

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

        saveToFile(type, json.toString())
        sendBroadcast(android.content.Intent("com.example.fitguard.HEALTH_DATA").apply {
            putExtra("type", type)
            putExtra("data", json.toString())
        })
    }

    private fun processBatchData(dataMap: DataMap) {
        try {
            val batchJson = dataMap.getString("batch_json") ?: return
            val payload = JSONObject(batchJson)
            val metadata = payload.getJSONObject("metadata")
            val dataArray = payload.getJSONArray("data")

            val sequenceId = metadata.getString("sequence_id")
            val batchNumber = metadata.getInt("batch_number")
            val totalBatches = metadata.getInt("total_batches")
            val receivedAt = System.currentTimeMillis()

            Log.d(TAG, "Received batch $batchNumber/$totalBatches (${dataArray.length()} points) for $sequenceId")

            for (i in 0 until dataArray.length()) {
                val entry = dataArray.getJSONObject(i)
                val type = entry.getString("type")

                entry.put("sequence_id", sequenceId)
                entry.put("batch_number", batchNumber)
                entry.put("received_at", receivedAt)

                saveToFile(type, entry.toString())
            }

            sendBroadcast(android.content.Intent("com.example.fitguard.BATCH_DATA").apply {
                putExtra("sequence_id", sequenceId)
                putExtra("batch_number", batchNumber)
                putExtra("total_batches", totalBatches)
                putExtra("points", dataArray.length())
            })
        } catch (e: Exception) {
            Log.e(TAG, "Batch processing failed: ${e.message}", e)
        }
    }

    private fun saveToFile(type: String, data: String) {
        try {
            val dir = File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), "FitGuard_Data")
            dir.mkdirs()
            File(dir, "${type}_${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.jsonl")
                .appendText(data + "\n")
        } catch (e: Exception) {
            Log.e(TAG, "Save failed: ${e.message}")
        }
    }
}