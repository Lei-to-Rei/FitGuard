package com.example.fitguard.presentation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

object FatigueAlertHelper {
    private const val TAG = "FatigueAlertHelper"
    private const val CHANNEL_ID = "fatigue_alert"
    private const val NOTIFICATION_ID = 1003

    private var channelCreated = false

    private fun ensureChannel(context: Context) {
        if (channelCreated) return
        channelCreated = true
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Fatigue Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Fatigue level escalation alerts during workout"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 300, 150, 300)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    fun showAlert(context: Context, level: String, levelIndex: Int, percentDisplay: Int) {
        ensureChannel(context)
        wakeScreen(context)

        val nm = context.getSystemService(NotificationManager::class.java)

        val (title, body, vibration) = when (levelIndex) {
            1 -> Triple(
                "Fatigue Rising",
                "$percentDisplay% — Consider pacing yourself",
                longArrayOf(0, 200, 100, 200)
            )
            2 -> Triple(
                "High Fatigue Warning",
                "$percentDisplay% — Consider taking a break",
                longArrayOf(0, 300, 100, 300, 100, 300)
            )
            3 -> Triple(
                "CRITICAL Fatigue",
                "$percentDisplay% — Stop and rest immediately!",
                longArrayOf(0, 500, 200, 500, 200, 500)
            )
            else -> return
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setVibrate(vibration)
            .setAutoCancel(true)
            .setTimeoutAfter(8_000)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
        Log.d(TAG, "Fatigue alert shown: $level ($percentDisplay%)")
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
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(NOTIFICATION_ID)
    }
}
