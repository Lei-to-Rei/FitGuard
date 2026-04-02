package com.example.fitguard.features.fatigue

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.VibrationEffect
import android.os.Vibrator
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
    private const val ACTION_DISMISS = "com.example.fitguard.FATIGUE_ALERT_DISMISS_PHONE"

    private var detector: FatigueDetector? = null
    private var initialized = false
    private var previousLevelIndex = -1
    private var lastAlertTimeMs = 0L
    private var vibrator: Vibrator? = null

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
            enableVibration(false) // We handle vibration manually for repeating
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

        // Repeating vibration patterns: vibrate then pause, loop from index 0
        val (title, body, vibrationPattern) = when (result.levelIndex) {
            1 -> Triple(
                "Fatigue Rising",
                "Fatigue level: Moderate (${result.percentDisplay}%). Consider pacing yourself.",
                longArrayOf(0, 200, 100, 200, 4500)        // ~5s cycle
            )
            2 -> Triple(
                "High Fatigue Warning",
                "Fatigue level: High (${result.percentDisplay}%). Consider taking a break.",
                longArrayOf(0, 300, 100, 300, 100, 300, 1900) // ~3s cycle
            )
            3 -> Triple(
                "CRITICAL Fatigue",
                "Fatigue level: Critical (${result.percentDisplay}%). Stop and rest immediately!",
                longArrayOf(0, 500, 200, 500, 200, 500, 100)  // ~2s cycle
            )
            else -> return
        }

        val dismissIntent = Intent(ACTION_DISMISS).setPackage(context.packageName)
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context, NOTIFICATION_ID, dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(dismissPendingIntent)
            .setDeleteIntent(dismissPendingIntent)
            .build()

        nm.notify(NOTIFICATION_ID, notification)

        // Start repeating vibration until user acknowledges
        startRepeatingVibration(context, vibrationPattern)

        Log.d(TAG, "Phone notification shown: ${result.level} — vibrating until dismissed")
    }

    private fun startRepeatingVibration(context: Context, pattern: LongArray) {
        cancelVibration()
        val v = context.getSystemService(Vibrator::class.java)
        vibrator = v
        // repeat=0 means loop the entire pattern from index 0
        v.vibrate(VibrationEffect.createWaveform(pattern, 0))
    }

    fun cancelVibration() {
        vibrator?.cancel()
        vibrator = null
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

    /**
     * Receiver that stops vibration and cancels notification when user taps or swipes.
     */
    class DismissReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            cancelVibration()
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.cancel(NOTIFICATION_ID)
            Log.d(TAG, "Fatigue alert dismissed by user")
        }
    }
}
