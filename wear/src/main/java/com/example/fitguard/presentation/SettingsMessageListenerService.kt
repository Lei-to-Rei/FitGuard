package com.example.fitguard.presentation

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Service to receive batch settings updates from phone
 */
class SettingsMessageListenerService : WearableListenerService() {
    companion object {
        private const val TAG = "SettingsMessageListener"
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            "/batch_settings" -> {
                val message = String(messageEvent.data)
                Log.d(TAG, "Received settings: $message")

                try {
                    val parts = message.split(",")
                    if (parts.size == 2) {
                        val batchSizeKB = parts[0].toInt()
                        val transferIntervalMinutes = parts[1].toInt()

                        // Save settings locally
                        val prefs = getSharedPreferences("batch_settings", Context.MODE_PRIVATE)
                        prefs.edit().apply {
                            putInt("batch_size_kb", batchSizeKB)
                            putInt("transfer_interval_minutes", transferIntervalMinutes)
                        }.apply()

                        Log.d(TAG, "Settings updated: ${batchSizeKB}KB, ${transferIntervalMinutes}min")

                        // Notify app if it's running
                        sendBroadcast(android.content.Intent("com.example.fitguard.SETTINGS_UPDATED").apply {
                            putExtra("batch_size_kb", batchSizeKB)
                            putExtra("transfer_interval_minutes", transferIntervalMinutes)
                        })
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse settings: ${e.message}")
                }
            }
        }
    }
}