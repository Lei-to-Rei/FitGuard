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
                    val rpeIntervalMinutes = json.optInt("rpe_interval_minutes", 10)
                    Log.d(TAG, "Start command: activity=$activityType session=$sessionId rpeInterval=${rpeIntervalMinutes}min")
                    sendBroadcast(Intent(ACTION_START_ACTIVITY).apply {
                        setPackage(packageName)
                        putExtra("activity_type", activityType)
                        putExtra("session_id", sessionId)
                        putExtra("rpe_interval_minutes", rpeIntervalMinutes)
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
