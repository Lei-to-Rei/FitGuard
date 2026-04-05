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
import com.example.fitguard.features.recommendations.RecoveryRecommendationManager
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
    private const val RECOVERY_CHANNEL_ID = "recovery_recommendation"
    private const val NOTIFICATION_ID = 2003
    private const val RECOVERY_NOTIFICATION_ID = 2004
    private const val COOLDOWN_MS = 60_000L
    private const val MESSAGE_PATH = "/fitguard/fatigue/alert"
    private const val RECOVERY_MESSAGE_PATH = "/fitguard/recovery/active"
    private const val ACTION_DISMISS = "com.example.fitguard.FATIGUE_ALERT_DISMISS_PHONE"
    private const val EMA_ALPHA = 0.4f
    private const val CONSECUTIVE_HIGH_THRESHOLD = 3

    private var detector: FatigueDetector? = null
    private var initialized = false
    private var previousLevelIndex = -1
    private var lastAlertTimeMs = 0L
    private var vibrator: Vibrator? = null
    private var recoveryChannelCreated = false

    // EMA smoothing state
    var smoothedPHigh: Float = -1f
        private set
    var lastRawPHigh: Float = 0f
        private set
    private var consecutiveHighCount = 0

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

    private fun ensureRecoveryChannel(context: Context) {
        if (recoveryChannelCreated) return
        recoveryChannelCreated = true
        val channel = NotificationChannel(
            RECOVERY_CHANNEL_ID,
            "Recovery Recommendations",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Recovery recommendations during and after workouts"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 300, 150, 300)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    /**
     * Buffer features in the detector WITHOUT running inference.
     * Called by SequenceProcessor on non-prediction stride windows.
     */
    fun bufferWindowOnly(context: Context, features: FloatArray) {
        ensureInitialized(context)
        val det = detector ?: return
        det.bufferWindowOnly(features)
        Log.d(TAG, "Window buffered (no inference)")
    }

    fun onFeatureWindow(context: Context, features: FloatArray) {
        ensureInitialized(context)
        val det = detector ?: return
        if (!det.isReady) return

        val result = det.addWindowAndPredict(features) ?: return

        // Update raw pHigh
        lastRawPHigh = result.pHigh

        // Apply EMA smoothing
        smoothedPHigh = if (smoothedPHigh < 0f) {
            result.pHigh
        } else {
            EMA_ALPHA * result.pHigh + (1f - EMA_ALPHA) * smoothedPHigh
        }

        // Classify using smoothed pHigh
        val smoothedLevelIndex = when {
            smoothedPHigh < 0.25f -> 0
            smoothedPHigh < 0.50f -> 1
            smoothedPHigh < 0.75f -> 2
            else -> 3
        }
        val smoothedPercentDisplay = (smoothedPHigh * 100).toInt().coerceIn(0, 100)

        // Track consecutive predictions above High threshold (0.50)
        if (smoothedPHigh >= 0.50f) {
            consecutiveHighCount++
        } else {
            consecutiveHighCount = 0
        }

        Log.d(TAG, "Prediction: raw=${String.format("%.3f", lastRawPHigh)}, " +
                "smoothed=${String.format("%.3f", smoothedPHigh)}, " +
                "level=$smoothedLevelIndex, consecutiveHigh=$consecutiveHighCount")

        // Feed recovery recommendation manager (features[0]=HR, features[8]=RMSSD)
        val currentHR = features[0].toDouble()
        val currentRMSSD = features[8].toDouble()
        RecoveryRecommendationManager.onPrediction(smoothedPHigh.toDouble(), currentHR, currentRMSSD)

        // Check for active recovery recommendation
        val activeRec = RecoveryRecommendationManager.checkActiveRecovery(
            smoothedPHigh.toDouble(), currentHR, currentRMSSD
        )
        if (activeRec != null) {
            showRecoveryNotification(context, activeRec)
            sendRecoveryToWatch(context, activeRec)
        }

        if (previousLevelIndex == -1) {
            previousLevelIndex = smoothedLevelIndex
            Log.d(TAG, "Baseline set: level=$smoothedLevelIndex ($smoothedPercentDisplay%)")
            return
        }

        // Only fire alert when: level escalated AND sustained high fatigue (3+ consecutive)
        if (smoothedLevelIndex > previousLevelIndex &&
            consecutiveHighCount >= CONSECUTIVE_HIGH_THRESHOLD) {
            val now = System.currentTimeMillis()
            if (now - lastAlertTimeMs >= COOLDOWN_MS) {
                // Build a result using smoothed values for the notification
                val smoothedResult = FatigueResult(
                    pLow = 1f - smoothedPHigh,
                    pHigh = smoothedPHigh,
                    level = result.level,
                    levelIndex = smoothedLevelIndex,
                    percentDisplay = smoothedPercentDisplay
                )
                showPhoneNotification(context, smoothedResult)
                sendToWatch(context, smoothedResult)
                lastAlertTimeMs = now
                Log.d(TAG, "Alert fired: level=$smoothedLevelIndex ($smoothedPercentDisplay%)")
            } else {
                Log.d(TAG, "Alert suppressed (cooldown): level=$smoothedLevelIndex")
            }
        }

        previousLevelIndex = smoothedLevelIndex
    }

    fun reset() {
        previousLevelIndex = -1
        lastAlertTimeMs = 0L
        smoothedPHigh = -1f
        lastRawPHigh = 0f
        consecutiveHighCount = 0
        detector?.clearBuffer()
        Log.d(TAG, "Reset")
    }

    // --- Fatigue alert notifications ---

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

    // --- Recovery recommendation notifications ---

    private fun showRecoveryNotification(
        context: Context,
        recovery: RecoveryRecommendationManager.ActiveRecovery
    ) {
        ensureRecoveryChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java)

        val vibrationPattern = when (recovery.fatigueLevel) {
            1 -> longArrayOf(0, 300)                          // single short
            2 -> longArrayOf(0, 300, 200, 300)                // double
            3 -> longArrayOf(0, 400, 200, 400, 200, 400)     // triple long
            else -> longArrayOf(0, 300)
        }

        val notification = NotificationCompat.Builder(context, RECOVERY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(recovery.phoneTitle)
            .setContentText(recovery.phoneBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(recovery.phoneBody))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setVibrate(vibrationPattern)
            .setAutoCancel(true)
            .build()

        nm.notify(RECOVERY_NOTIFICATION_ID, notification)
        Log.d(TAG, "Recovery notification shown: level=${recovery.fatigueLevel}")
    }

    fun showPassiveRecoveryNotification(context: Context, recovery: RecoveryRecommendationManager.PassiveRecovery) {
        ensureRecoveryChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java)

        val notification = NotificationCompat.Builder(context, RECOVERY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(recovery.phoneTitle)
            .setContentText(recovery.phoneBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(recovery.phoneBody))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setVibrate(longArrayOf(0, 200, 100, 200))
            .setAutoCancel(true)
            .build()

        nm.notify(RECOVERY_NOTIFICATION_ID, notification)
        Log.d(TAG, "Passive recovery notification shown: ${recovery.phoneTitle}")
    }

    // --- Watch messaging ---

    private fun sendRecoveryToWatch(
        context: Context,
        recovery: RecoveryRecommendationManager.ActiveRecovery
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val nodes = Wearable.getNodeClient(context).connectedNodes.await()
                val payload = JSONObject().apply {
                    put("watchText", recovery.watchText)
                    put("fatigueLevel", recovery.fatigueLevel)
                }
                val data = payload.toString().toByteArray(Charsets.UTF_8)
                for (node in nodes) {
                    Wearable.getMessageClient(context)
                        .sendMessage(node.id, RECOVERY_MESSAGE_PATH, data).await()
                    Log.d(TAG, "Recovery alert sent to ${node.displayName}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send recovery to watch: ${e.message}", e)
            }
        }
    }

    fun sendPassiveRecoveryToWatch(context: Context, recovery: RecoveryRecommendationManager.PassiveRecovery) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val nodes = Wearable.getNodeClient(context).connectedNodes.await()
                val payload = JSONObject().apply {
                    put("watchText", recovery.watchText)
                    put("restHours", recovery.estimatedRestHours)
                    put("sessionEndTime", System.currentTimeMillis())
                }
                val data = payload.toString().toByteArray(Charsets.UTF_8)
                for (node in nodes) {
                    Wearable.getMessageClient(context)
                        .sendMessage(node.id, "/fitguard/recovery/passive", data).await()
                    Log.d(TAG, "Passive recovery sent to ${node.displayName}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send passive recovery to watch: ${e.message}", e)
            }
        }
    }

    // --- Vibration ---

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

    fun cancel(context: Context) {
        cancelVibration()
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(NOTIFICATION_ID)
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
