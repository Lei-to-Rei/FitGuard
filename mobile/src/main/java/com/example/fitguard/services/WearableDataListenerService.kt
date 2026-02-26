package com.example.fitguard.services

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.fitguard.data.processing.AccelSample
import com.example.fitguard.data.processing.PpgSample
import com.example.fitguard.data.processing.RpeState
import com.example.fitguard.data.processing.SequenceProcessor
import com.example.fitguard.features.workout.WorkoutControlViewModel
import android.net.Uri
import com.google.android.gms.wearable.*
import com.google.firebase.auth.FirebaseAuth
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class WearableDataListenerService : WearableListenerService() {
    companion object {
        private const val TAG = "WearableDataListener"
        const val ACTION_ACTIVITY_ACK = "com.example.fitguard.ACTIVITY_ACK"
        const val ACTION_ACTIVITY_STOPPED = "com.example.fitguard.ACTIVITY_STOPPED"
        const val ACTION_ACTIVITY_HEARTBEAT = "com.example.fitguard.ACTIVITY_HEARTBEAT"
        const val ACTION_RPE_RECEIVED = "com.example.fitguard.RPE_RECEIVED"
    }

    private val sequenceProcessor by lazy { SequenceProcessor(this) }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        Log.d(TAG, "onDataChanged: ${dataEvents.count} events")
        val processedUris = mutableListOf<Uri>()
        dataEvents.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED) {
                val uri = event.dataItem.uri
                val path = uri.path
                Log.d(TAG, "DataEvent: path=$path")
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                when {
                    path == "/health_tracker_data" -> processHealthData(dataMap.toBundle())
                    path?.startsWith("/health_tracker_batch/") == true -> {
                        processBatchData(dataMap)
                        processedUris.add(uri)
                    }
                }
            }
        }
        // Delete processed items AFTER fully consuming the buffer
        for (uri in processedUris) {
            Wearable.getDataClient(this).deleteDataItems(uri, DataClient.FILTER_LITERAL)
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val path = messageEvent.path
        Log.d(TAG, "Message received: $path")
        try {
            val json = JSONObject(String(messageEvent.data, Charsets.UTF_8))
            when (path) {
                "/fitguard/activity/ack" -> {
                    sendBroadcast(Intent(ACTION_ACTIVITY_ACK).apply {
                        putExtra("session_id", json.optString("session_id"))
                        putExtra("status", json.optString("status"))
                    })
                }
                "/fitguard/activity/stopped" -> {
                    sendBroadcast(Intent(ACTION_ACTIVITY_STOPPED).apply {
                        putExtra("session_id", json.optString("session_id"))
                        putExtra("reason", json.optString("reason"))
                        putExtra("sequence_count", json.optInt("sequence_count", 0))
                    })
                }
                "/fitguard/activity/heartbeat" -> {
                    sendBroadcast(Intent(ACTION_ACTIVITY_HEARTBEAT).apply {
                        putExtra("session_id", json.optString("session_id"))
                        putExtra("sequence_count", json.optInt("sequence_count", 0))
                        putExtra("elapsed_s", json.optInt("elapsed_s", 0))
                    })
                }
                "/fitguard/activity/rpe" -> {
                    val rpeValue = json.optInt("rpe_value", -1)
                    val sessionId = json.optString("session_id", "")
                    Log.d(TAG, "RPE received: $rpeValue for session $sessionId")
                    if (rpeValue >= 0) {
                        RpeState.update(rpeValue)
                    }
                    sequenceProcessor.onRpeReceived(rpeValue)
                    sendBroadcast(Intent(ACTION_RPE_RECEIVED).apply {
                        putExtra("session_id", sessionId)
                        putExtra("rpe_value", rpeValue)
                    })
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process message on $path: ${e.message}", e)
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

            val activityType = metadata.optString("activity_type", "")
            val batchSessionId = metadata.optString("session_id", "")

            // Filter stale batches: check static first, fall back to SharedPreferences after process death
            var activeSession = WorkoutControlViewModel.activeSessionId
            if (activeSession == null) {
                val prefs = getSharedPreferences("workout_session", Context.MODE_PRIVATE)
                if (prefs.getBoolean("is_active", false)) {
                    val savedId = prefs.getString("session_id", null)
                    if (savedId != null) {
                        WorkoutControlViewModel.activeSessionId = savedId
                        activeSession = savedId
                        Log.d(TAG, "Restored activeSessionId from prefs: $savedId")
                    }
                }
            }
            if (activeSession == null || (batchSessionId.isNotEmpty() && batchSessionId != activeSession)) {
                Log.w(TAG, "Dropping stale batch $batchNumber/$totalBatches for $sequenceId " +
                        "(batch session=$batchSessionId, active=$activeSession)")
                return
            }

            Log.d(TAG, "Received batch $batchNumber/$totalBatches (${dataArray.length()} points) for $sequenceId" +
                    if (activityType.isNotEmpty()) " activity=$activityType" else "")

            val ppgSamples = mutableListOf<PpgSample>()
            val accelSamples = mutableListOf<AccelSample>()

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
                }
            }

            if (ppgSamples.isNotEmpty()) {
                sequenceProcessor.accumulator.addPpgSamples(sequenceId, totalBatches, ppgSamples)
            }
            if (accelSamples.isNotEmpty()) {
                sequenceProcessor.accumulator.addAccelSamples(sequenceId, totalBatches, accelSamples)
            }
            if (activityType.isNotEmpty()) {
                sequenceProcessor.accumulator.setActivityType(sequenceId, activityType)
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

    private val currentUserId: String
        get() = FirebaseAuth.getInstance().currentUser?.uid ?: ""

    private fun saveToFile(type: String, data: String) {
        try {
            val dir = com.example.fitguard.data.processing.CsvWriter.getOutputDir(currentUserId)
            File(dir, "${type}_${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.jsonl")
                .appendText(data + "\n")
        } catch (e: Exception) {
            Log.e(TAG, "Save failed: ${e.message}")
        }
    }
}
