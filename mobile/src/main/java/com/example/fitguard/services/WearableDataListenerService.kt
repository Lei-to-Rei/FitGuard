package com.example.fitguard.services

import android.util.Log
import com.example.fitguard.data.processing.AccelSample
import com.example.fitguard.data.processing.PpgSample
import com.example.fitguard.data.processing.SequenceProcessor
import com.example.fitguard.data.processing.SkinTempSample
import com.google.android.gms.wearable.*
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class WearableDataListenerService : WearableListenerService() {
    companion object {
        private const val TAG = "WearableDataListener"
    }

    private val sequenceProcessor by lazy { SequenceProcessor(this) }

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

            Log.d(TAG, "Received batch $batchNumber/$totalBatches (${dataArray.length()} points) for $sequenceId")

            val ppgSamples = mutableListOf<PpgSample>()
            val accelSamples = mutableListOf<AccelSample>()
            val skinTempSamples = mutableListOf<SkinTempSample>()

            for (i in 0 until dataArray.length()) {
                val entry = dataArray.getJSONObject(i)
                val type = entry.getString("type")

                entry.put("sequence_id", sequenceId)
                entry.put("batch_number", batchNumber)
                entry.put("received_at", receivedAt)

                saveToFile(type, entry.toString())

                when (type) {
                    "PPG" -> ppgSamples.add(PpgSample(
                        timestamp = entry.getLong("timestamp"),
                        green = entry.optInt("green", 0),
                        ir = entry.optInt("ir", 0),
                        red = entry.optInt("red", 0)
                    ))
                    "Accelerometer" -> accelSamples.add(AccelSample(
                        timestamp = entry.getLong("timestamp"),
                        x = entry.optDouble("x", 0.0).toFloat(),
                        y = entry.optDouble("y", 0.0).toFloat(),
                        z = entry.optDouble("z", 0.0).toFloat()
                    ))
                    "SkinTemp" -> {
                        val objTemp = entry.optDouble("object_temp", Double.NaN)
                        val ambTemp = entry.optDouble("ambient_temp", Double.NaN)
                        if (!objTemp.isNaN() && !ambTemp.isNaN()) {
                            skinTempSamples.add(SkinTempSample(
                                timestamp = entry.getLong("timestamp"),
                                objectTemp = objTemp.toFloat(),
                                ambientTemp = ambTemp.toFloat()
                            ))
                        }
                    }
                }
            }

            if (ppgSamples.isNotEmpty()) {
                sequenceProcessor.accumulator.addPpgSamples(sequenceId, totalBatches, ppgSamples)
            }
            if (accelSamples.isNotEmpty()) {
                sequenceProcessor.accumulator.addAccelSamples(sequenceId, totalBatches, accelSamples)
            }
            if (skinTempSamples.isNotEmpty()) {
                sequenceProcessor.accumulator.addSkinTempSamples(sequenceId, totalBatches, skinTempSamples)
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
