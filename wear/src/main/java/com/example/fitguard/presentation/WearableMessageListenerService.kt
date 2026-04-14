package com.example.fitguard.presentation

import android.content.Intent
import android.os.BatteryManager
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

class WearableMessageListenerService : WearableListenerService() {
    companion object {
        private const val TAG = "WearMsgListener"
        const val ACTION_START_ACTIVITY = "com.example.fitguard.wear.START_ACTIVITY"
        const val ACTION_STOP_ACTIVITY = "com.example.fitguard.wear.STOP_ACTIVITY"
        const val ACTION_START_TRACKER = "com.example.fitguard.wear.START_TRACKER"
        const val ACTION_STOP_TRACKER = "com.example.fitguard.wear.STOP_TRACKER"
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val path = messageEvent.path
        Log.d(TAG, "Message received: $path")

        if (path == "/fitguard/device/battery_request") {
            handleBatteryRequest(messageEvent.sourceNodeId)
            return
        }

        try {
            val json = JSONObject(String(messageEvent.data, Charsets.UTF_8))
            when (path) {
                "/fitguard/activity/start" -> {
                    val activityType = json.optString("activity_type", "")
                    val sessionId = json.optString("session_id", "")
                    Log.d(TAG, "Start command: activity=$activityType session=$sessionId")
                    sendBroadcast(Intent(ACTION_START_ACTIVITY).apply {
                        setPackage(packageName)
                        putExtra("activity_type", activityType)
                        putExtra("session_id", sessionId)
                    })
                }
                "/fitguard/activity/stop" -> {
                    val sessionId = json.optString("session_id", "")
                    Log.d(TAG, "Stop command: session=$sessionId")
                    sendBroadcast(Intent(ACTION_STOP_ACTIVITY).apply {
                        setPackage(packageName)
                        putExtra("session_id", sessionId)
                    })
                }
                "/fitguard/tracker/start" -> {
                    val trackerType = json.optString("tracker_type", "")
                    Log.d(TAG, "Tracker start command: $trackerType")
                    PassiveTrackerService.startTracker(this, trackerType)
                }
                "/fitguard/tracker/stop" -> {
                    val trackerType = json.optString("tracker_type", "")
                    Log.d(TAG, "Tracker stop command: $trackerType")
                    PassiveTrackerService.stopTracker(this, trackerType)
                }
                "/fitguard/tracker/set_schedule" -> {
                    val scheduleJson = String(messageEvent.data, Charsets.UTF_8)
                    Log.d(TAG, "Schedule set command")
                    PassiveTrackerService.setSchedule(this, scheduleJson)
                }
                "/fitguard/tracker/clear_schedule" -> {
                    Log.d(TAG, "Schedule clear command")
                    PassiveTrackerService.clearSchedule(this)
                }
                "/fitguard/fatigue/alert" -> {
                    val level = json.optString("level", "Unknown")
                    val levelIndex = json.optInt("levelIndex", 0)
                    val percentDisplay = json.optInt("percentDisplay", 0)
                    Log.d(TAG, "Fatigue alert: $level ($percentDisplay%)")
                    FatigueAlertHelper.showAlert(this, level, levelIndex, percentDisplay)
                    sendBroadcast(Intent(MainActivity.ACTION_FATIGUE_UPDATE).apply {
                        setPackage(packageName)
                        putExtra("level", level)
                        putExtra("levelIndex", levelIndex)
                        putExtra("percentDisplay", percentDisplay)
                    })
                }
                "/fitguard/recovery/active" -> {
                    val watchText = json.optString("watchText", "")
                    val fatigueLevel = json.optInt("fatigueLevel", 1)
                    Log.d(TAG, "Active recovery: $watchText (level=$fatigueLevel)")
                    FatigueAlertHelper.showRecoveryAlert(this, watchText, fatigueLevel)
                }
                "/fitguard/recovery/passive" -> {
                    val watchText = json.optString("watchText", "")
                    Log.d(TAG, "Passive recovery: $watchText")
                    FatigueAlertHelper.showRecoveryComplete(this, watchText)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process message: ${e.message}", e)
        }
    }

    private fun handleBatteryRequest(sourceNodeId: String) {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val response = JSONObject().apply {
            put("battery_percent", level)
        }
        val data = response.toString().toByteArray(Charsets.UTF_8)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Wearable.getMessageClient(this@WearableMessageListenerService)
                    .sendMessage(sourceNodeId, "/fitguard/device/battery_response", data)
                    .await()
                Log.d(TAG, "Battery response sent: $level%")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send battery response: ${e.message}", e)
            }
        }
    }
}
