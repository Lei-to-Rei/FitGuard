package com.example.fitguard.presentation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat

object FatigueAlertHelper {
    private const val TAG = "FatigueAlertHelper"
    private const val CHANNEL_ID = "fatigue_alert"
    private const val NOTIFICATION_ID = 1003
    private const val ACTION_DISMISS = "com.example.fitguard.FATIGUE_ALERT_DISMISS_WEAR"

    private var channelCreated = false
    private var vibrator: Vibrator? = null

    private fun ensureChannel(context: Context) {
        if (channelCreated) return
        channelCreated = true
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Fatigue Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Fatigue level escalation alerts during workout"
            enableVibration(false) // We handle vibration manually for repeating
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    fun showAlert(context: Context, level: String, levelIndex: Int, percentDisplay: Int) {
        ensureChannel(context)
        wakeScreen(context)

        val nm = context.getSystemService(NotificationManager::class.java)

        // Repeating vibration patterns: vibrate then pause, loop from index 0
        // Higher levels vibrate more aggressively with shorter pauses
        val (title, body, vibrationPattern) = when (levelIndex) {
            1 -> Triple(
                "Fatigue Rising",
                "$percentDisplay% — Consider pacing yourself",
                longArrayOf(0, 200, 100, 200, 4500)        // ~5s cycle
            )
            2 -> Triple(
                "High Fatigue Warning",
                "$percentDisplay% — Consider taking a break",
                longArrayOf(0, 300, 100, 300, 100, 300, 1900) // ~3s cycle
            )
            3 -> Triple(
                "CRITICAL Fatigue",
                "$percentDisplay% — Stop and rest immediately!",
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

        Log.d(TAG, "Fatigue alert shown: $level ($percentDisplay%) — vibrating until dismissed")
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

    @Suppress("DEPRECATION")
    private fun wakeScreen(context: Context) {
        try {
            val pm = context.getSystemService(PowerManager::class.java)
            val wakeLock = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK
                        or PowerManager.ACQUIRE_CAUSES_WAKEUP
                        or PowerManager.ON_AFTER_RELEASE,
                "fitguard:fatigue_alert_wake"
            )
            wakeLock.acquire(5000L)
            Log.d(TAG, "Screen wake lock acquired")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire wake lock: ${e.message}")
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
