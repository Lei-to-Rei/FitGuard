package com.example.fitguard.services

import android.util.Log
import com.example.fitguard.data.processing.AccelSample
import com.example.fitguard.data.processing.PpgSample
import com.example.fitguard.data.processing.SpO2Sample
import com.example.fitguard.data.processing.SkinTempSample
import com.example.fitguard.data.processing.SequenceProcessor
import com.example.fitguard.features.sleep.SleepStressActivity
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

    private val sequenceProcessor by lazy { SequenceProcessor(this) }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path == "/sleep_stress/status") {
            try {
                val json = JSONObject(String(messageEvent.data, Charsets.UTF_8))
                val status = json.getString("status")
                val reason = json.optString("reason", "")
                Log.d(TAG, "Watch status message: $status (reason: $reason)")

                sendBroadcast(android.content.Intent(SleepStressActivity.ACTION_SLEEP_STRESS_STATUS).apply {
                    setPackage(packageName)
                    putExtra("status", status)
                    putExtra("reason", reason)
                })
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse watch status: ${e.message}", e)
            }
        }
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
                "SkinTemp" -> {
                    put("status", bundle.getInt("status"))
                    if (bundle.containsKey("object_temp")) put("obj", bundle.getFloat("object_temp"))
                    if (bundle.containsKey("ambient_temp")) put("amb", bundle.getFloat("ambient_temp"))
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

            val isSleepStress = sequenceId.startsWith("ss_")
            Log.d(TAG, "Received batch $batchNumber/$totalBatches (${dataArray.length()} points) for $sequenceId (sleepStress=$isSleepStress)")

            for (i in 0 until dataArray.length()) {
                val entry = dataArray.getJSONObject(i)
                val type = entry.getString("type")

                entry.put("sequence_id", sequenceId)
                entry.put("batch_number", batchNumber)
                entry.put("received_at", receivedAt)

                saveToFile(type, entry.toString(), isSleepStress)
            }

            // Feed PPG, SpO2, SkinTemp, and Accelerometer entries to the sequence processor accumulator
            val ppgSamples = mutableListOf<PpgSample>()
            val spo2Samples = mutableListOf<SpO2Sample>()
            val skinTempSamples = mutableListOf<SkinTempSample>()
            val accelSamples = mutableListOf<AccelSample>()
            for (i in 0 until dataArray.length()) {
                val entry = dataArray.getJSONObject(i)
                when (entry.getString("type")) {
                    "PPG" -> ppgSamples.add(PpgSample(
                        timestamp = entry.getLong("timestamp"),
                        green = entry.optInt("green", 0)
                    ))
                    "SpO2" -> spo2Samples.add(SpO2Sample(
                        spo2 = entry.optInt("spo2", 0),
                        heartRate = entry.optInt("heart_rate", 0),
                        status = entry.optInt("status", 0),
                        timestamp = entry.getLong("timestamp")
                    ))
                    "SkinTemp" -> skinTempSamples.add(SkinTempSample(
                        objectTemp = entry.optDouble("object_temp", Double.NaN).toFloat(),
                        ambientTemp = entry.optDouble("ambient_temp", Double.NaN).toFloat(),
                        status = entry.optInt("status", 0),
                        timestamp = entry.getLong("timestamp")
                    ))
                    "Accelerometer" -> accelSamples.add(AccelSample(
                        x = entry.optInt("x", 0),
                        y = entry.optInt("y", 0),
                        z = entry.optInt("z", 0),
                        timestamp = entry.getLong("timestamp")
                    ))
                }
            }
            if (ppgSamples.isNotEmpty()) {
                sequenceProcessor.accumulator.addPpgSamples(sequenceId, totalBatches, ppgSamples)
            }
            if (spo2Samples.isNotEmpty()) {
                sequenceProcessor.accumulator.addSpO2Samples(sequenceId, totalBatches, spo2Samples)
            }
            if (skinTempSamples.isNotEmpty()) {
                sequenceProcessor.accumulator.addSkinTempSamples(sequenceId, totalBatches, skinTempSamples)
            }
            if (accelSamples.isNotEmpty()) {
                sequenceProcessor.accumulator.addAccelSamples(sequenceId, totalBatches, accelSamples)
            }
            sequenceProcessor.accumulator.markBatchReceived(sequenceId, batchNumber, totalBatches)

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

    private fun saveToFile(type: String, data: String, isSleepStress: Boolean = false) {
        try {
            val subDir = if (isSleepStress) "FitGuard_Data/Sleep_and_Stress_Data" else "FitGuard_Data"
            val dir = File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), subDir)
            dir.mkdirs()
            File(dir, "${type}_${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.jsonl")
                .appendText(data + "\n")
        } catch (e: Exception) {
            Log.e(TAG, "Save failed: ${e.message}")
        }
    }
}