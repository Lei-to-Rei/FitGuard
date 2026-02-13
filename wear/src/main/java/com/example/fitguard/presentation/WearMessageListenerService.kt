package com.example.fitguard.presentation

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject

class WearMessageListenerService : WearableListenerService() {

    companion object {
        private const val TAG = "WearMsgListener"
        private const val PATH_START = "/sleep_stress/start"
        private const val PATH_STOP = "/sleep_stress/stop"
        private const val PATH_STATUS = "/sleep_stress/status"
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.d(TAG, "Message received: ${messageEvent.path}")

        when (messageEvent.path) {
            PATH_START -> handleStart(messageEvent.sourceNodeId)
            PATH_STOP -> handleStop(messageEvent.sourceNodeId)
            else -> super.onMessageReceived(messageEvent)
        }
    }

    private fun handleStart(sourceNodeId: String) {
        // If sleep/stress is already running, just confirm — phone may have lost state
        if (SessionManager.currentSession == SessionManager.SessionType.SLEEP_STRESS) {
            Log.d(TAG, "Sleep/stress already running, re-confirming started")
            sendStatus(sourceNodeId, "started", "")
            return
        }

        when {
            !SessionManager.isHealthServiceReady -> {
                Log.w(TAG, "Health service not ready — watch app not open")
                sendStatus(sourceNodeId, "rejected", "open_watch_app")
            }
            !SessionManager.tryStartSleepStress() -> {
                Log.w(TAG, "Cannot start sleep/stress — session active: ${SessionManager.currentSession}")
                sendStatus(sourceNodeId, "rejected", "activity_running")
            }
            else -> {
                val manager = SessionManager.sleepStressSequenceManager
                if (manager == null) {
                    Log.e(TAG, "SleepStressSequenceManager not initialized")
                    SessionManager.endSession()
                    sendStatus(sourceNodeId, "rejected", "open_watch_app")
                    return
                }
                manager.startRepeatingSequence()
                Log.d(TAG, "Sleep/stress sequence started")
                sendStatus(sourceNodeId, "started", "")
            }
        }
    }

    private fun handleStop(sourceNodeId: String) {
        SessionManager.sleepStressSequenceManager?.stopRepeatingSequence()
        SessionManager.endSession()
        Log.d(TAG, "Sleep/stress sequence stopped")
        sendStatus(sourceNodeId, "stopped", "")
    }

    private fun sendStatus(nodeId: String, status: String, reason: String) {
        val json = JSONObject().apply {
            put("status", status)
            if (reason.isNotEmpty()) put("reason", reason)
        }
        val data = json.toString().toByteArray(Charsets.UTF_8)

        Wearable.getMessageClient(this).sendMessage(nodeId, PATH_STATUS, data)
            .addOnSuccessListener { Log.d(TAG, "Status sent: $status") }
            .addOnFailureListener { Log.e(TAG, "Failed to send status: ${it.message}") }
    }
}
