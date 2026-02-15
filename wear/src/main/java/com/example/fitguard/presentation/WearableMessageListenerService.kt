package com.example.fitguard.presentation

import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
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
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process message: ${e.message}", e)
        }
    }
}
