package com.example.fitguard.services

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.fitguard.data.processing.AccelSample
import com.example.fitguard.data.processing.PpgSample
import com.example.fitguard.data.processing.RpeState
import com.example.fitguard.data.processing.SequenceProcessor
import com.example.fitguard.features.activitytracking.ActivityTrackingViewModel
import com.example.fitguard.features.activitytracking.RpeNotificationHelper
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
        const val ACTION_WATCH_BATTERY_RESPONSE = "com.example.fitguard.WATCH_BATTERY_RESPONSE"
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
            // Battery response may have data; other messages always have JSON
            if (path == "/fitguard/device/battery_response") {
                val json = JSONObject(String(messageEvent.data, Charsets.UTF_8))
                val percent = json.optInt("battery_percent", -1)
                if (percent >= 0) {
                    sendBroadcast(Intent(ACTION_WATCH_BATTERY_RESPONSE).apply {
                        setPackage(packageName)
                        putExtra("node_id", messageEvent.sourceNodeId)
                        putExtra("battery_percent", percent)
                    })
                }
                return
            }
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

                    // Cancel phone RPE notification/activity (watch answered first)
                    val nm = getSystemService(NotificationManager::class.java)
                    nm.cancel(RpeNotificationHelper.NOTIFICATION_ID)

                    sendBroadcast(Intent(ACTION_RPE_RECEIVED).apply {
                        setPackage(packageName)
                        putExtra("session_id", sessionId)
                        putExtra("rpe_value", rpeValue)
                    })
                }
                "/fitguard/activity/rpe_prompt" -> {
                    val sessionId = json.optString("session_id", "")
                    val lastRpe = json.optInt("last_rpe", -1)
                    val isEndOfSession = json.optBoolean("is_end_of_session", false)
                    Log.d(TAG, "RPE prompt from watch: session=$sessionId isEnd=$isEndOfSession")

                    // Validate session matches active session
                    val activeSession = ActivityTrackingViewModel.activeSessionId
                    if (activeSession != null && sessionId.isNotEmpty() && sessionId != activeSession) {
                        Log.w(TAG, "Ignoring RPE prompt for stale session $sessionId (active=$activeSession)")
                        return
                    }

                    RpeNotificationHelper(this).showRpePrompt(lastRpe, isEndOfSession, sessionId)
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
            var activeSession = ActivityTrackingViewModel.activeSessionId
            if (activeSession == null) {
                val prefs = getSharedPreferences("workout_session", Context.MODE_PRIVATE)
                if (prefs.getBoolean("is_active", false)) {
                    val savedId = prefs.getString("session_id", null)
                    if (savedId != null) {
                        ActivityTrackingViewModel.activeSessionId = savedId
                        activeSession = savedId
                        Log.d(TAG, "Restored activeSessionId from prefs: $savedId")
                        val savedDir = prefs.getString("session_dir", null)
                        if (!savedDir.isNullOrEmpty()) {
                            ActivityTrackingViewModel.activeSessionDir = savedDir
                            Log.d(TAG, "Restored activeSessionDir from prefs: $savedDir")
                        }
                    }
                }
            }
            // Independent recovery for activeSessionDir
            if (ActivityTrackingViewModel.activeSessionDir == null) {
                val dirPrefs = getSharedPreferences("workout_session", Context.MODE_PRIVATE)
                val savedDir = dirPrefs.getString("session_dir", null)
                if (!savedDir.isNullOrEmpty()) {
                    ActivityTrackingViewModel.activeSessionDir = savedDir
                    Log.d(TAG, "Recovered activeSessionDir from prefs: $savedDir")
                } else {
                    // Reconstruct from activity_type + start_time
                    val at = dirPrefs.getString("activity_type", null)
                    val st = dirPrefs.getLong("start_time", 0L)
                    if (at != null && st > 0) {
                        val dir = "${SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date(st))}_$at"
                        ActivityTrackingViewModel.activeSessionDir = dir
                        Log.d(TAG, "Reconstructed activeSessionDir: $dir")
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

            getSharedPreferences("device_sync", Context.MODE_PRIVATE)
                .edit().putLong("last_sync_time", System.currentTimeMillis()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Batch processing failed: ${e.message}", e)
        }
    }

    private val currentUserId: String
        get() = FirebaseAuth.getInstance().currentUser?.uid ?: ""

    private fun saveToFile(type: String, data: String) {
        try {
            val sessionDir = ActivityTrackingViewModel.activeSessionDir ?: ""
            val dir = com.example.fitguard.data.processing.CsvWriter.getOutputDir(currentUserId, sessionDir)
            File(dir, "${type}_${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.jsonl")
                .appendText(data + "\n")
        } catch (e: Exception) {
            Log.e(TAG, "Save failed: ${e.message}")
        }
    }
}
