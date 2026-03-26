package com.example.fitguard.features.fatigue

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.fitguard.data.processing.FatigueDetector
import com.example.fitguard.data.processing.FatigueResult
import com.google.android.gms.wearable.Wearable
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

object FatigueAlertManager {
    private const val TAG = "FatigueAlertManager"
    private const val CHANNEL_ID = "fatigue_alert_phone"
    private const val NOTIFICATION_ID = 2003
    private const val COOLDOWN_MS = 60_000L
    private const val MESSAGE_PATH = "/fitguard/fatigue/alert"

    private var detector: FatigueDetector? = null
    private var initialized = false
    private var previousLevelIndex = -1
    private var lastAlertTimeMs = 0L

    private fun ensureInitialized(context: Context) {
        if (initialized) return
        initialized = true
        createNotificationChannel(context)
        val det = FatigueDetector(context.applicationContext)
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: ""
        val personalized = if (userId.isNotEmpty()) det.initializePersonalized(userId) else false
        if (!personalized) det.initialize()
        detector = det
        Log.d(TAG, "Initialized (model ready=${det.isReady})")
    }

    private fun createNotificationChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Fatigue Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Fatigue level escalation alerts during workout"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 300, 150, 300)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    fun onFeatureWindow(context: Context, features: FloatArray) {
        ensureInitialized(context)
        val det = detector ?: return
        if (!det.isReady) return

        val result = det.addWindowAndPredict(features) ?: return

        if (previousLevelIndex == -1) {
            previousLevelIndex = result.levelIndex
            Log.d(TAG, "Baseline set: ${result.level} (${result.percentDisplay}%)")
            return
        }

        if (result.levelIndex > previousLevelIndex) {
            val now = System.currentTimeMillis()
            if (now - lastAlertTimeMs >= COOLDOWN_MS) {
                showPhoneNotification(context, result)
                sendToWatch(context, result)
                lastAlertTimeMs = now
                Log.d(TAG, "Alert fired: ${result.level} (${result.percentDisplay}%)")
            } else {
                Log.d(TAG, "Alert suppressed (cooldown): ${result.level}")
            }
        }

        previousLevelIndex = result.levelIndex
    }

    fun reset() {
        previousLevelIndex = -1
        lastAlertTimeMs = 0L
        detector?.clearBuffer()
        Log.d(TAG, "Reset")
    }

    private fun showPhoneNotification(context: Context, result: FatigueResult) {
        val nm = context.getSystemService(NotificationManager::class.java)

        val (title, body, vibration) = when (result.levelIndex) {
            1 -> Triple(
                "Fatigue Rising",
                "Fatigue level: Moderate (${result.percentDisplay}%). Consider pacing yourself.",
                longArrayOf(0, 200, 100, 200)
            )
            2 -> Triple(
                "High Fatigue Warning",
                "Fatigue level: High (${result.percentDisplay}%). Consider taking a break.",
                longArrayOf(0, 300, 100, 300, 100, 300)
            )
            3 -> Triple(
                "CRITICAL Fatigue",
                "Fatigue level: Critical (${result.percentDisplay}%). Stop and rest immediately!",
                longArrayOf(0, 500, 200, 500, 200, 500)
            )
            else -> return
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setVibrate(vibration)
            .setAutoCancel(true)
            .setTimeoutAfter(10_000)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun sendToWatch(context: Context, result: FatigueResult) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val nodes = Wearable.getNodeClient(context).connectedNodes.await()
                val payload = JSONObject().apply {
                    put("level", result.level)
                    put("levelIndex", result.levelIndex)
                    put("pHigh", result.pHigh.toDouble())
                    put("percentDisplay", result.percentDisplay)
                }
                val data = payload.toString().toByteArray(Charsets.UTF_8)
                for (node in nodes) {
                    Wearable.getMessageClient(context)
                        .sendMessage(node.id, MESSAGE_PATH, data).await()
                    Log.d(TAG, "Fatigue alert sent to ${node.displayName}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send fatigue alert to watch: ${e.message}", e)
            }
        }
    }
}
