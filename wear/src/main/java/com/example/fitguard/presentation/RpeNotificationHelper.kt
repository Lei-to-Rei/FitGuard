package com.example.fitguard.presentation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

class RpeNotificationHelper(private val context: Context) {

    companion object {
        private const val TAG = "RpeNotifHelper"
        const val CHANNEL_ID = "rpe_prompt"
        const val NOTIFICATION_ID = 1001
    }

    init {
        createChannel()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "RPE Prompts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Rate of Perceived Exertion prompts during workout"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 200, 100, 200)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    fun showRpePrompt(lastRpe: Int, isEndOfSession: Boolean, sessionId: String) {
        // 1. Wake the screen via PowerManager
        wakeScreen()

        // 2. Show notification with action buttons
        showNotification(lastRpe, isEndOfSession, sessionId)

        // 3. Directly launch the activity (guaranteed to turn screen on via manifest attrs)
        launchActivity(lastRpe, isEndOfSession, sessionId)

        Log.d(TAG, "RPE prompt triggered: isEndOfSession=$isEndOfSession")
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        try {
            val pm = context.getSystemService(PowerManager::class.java)
            val wakeLock = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK
                        or PowerManager.ACQUIRE_CAUSES_WAKEUP
                        or PowerManager.ON_AFTER_RELEASE,
                "fitguard:rpe_wake"
            )
            wakeLock.acquire(5000L)
            Log.d(TAG, "Screen wake lock acquired")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire screen wake lock: ${e.message}")
        }
    }

    private fun launchActivity(lastRpe: Int, isEndOfSession: Boolean, sessionId: String) {
        try {
            val intent = Intent(context, RpePromptActivity::class.java).apply {
                putExtra(RpePromptActivity.EXTRA_LAST_RPE, lastRpe)
                putExtra(RpePromptActivity.EXTRA_IS_END_OF_SESSION, isEndOfSession)
                putExtra(RpePromptActivity.EXTRA_SESSION_ID, sessionId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch RPE activity: ${e.message}")
        }
    }

    private fun showNotification(lastRpe: Int, isEndOfSession: Boolean, sessionId: String) {
        val nm = context.getSystemService(NotificationManager::class.java)

        // Full-screen intent as another wake mechanism
        val fullScreenIntent = Intent(context, RpePromptActivity::class.java).apply {
            putExtra(RpePromptActivity.EXTRA_LAST_RPE, lastRpe)
            putExtra(RpePromptActivity.EXTRA_IS_END_OF_SESSION, isEndOfSession)
            putExtra(RpePromptActivity.EXTRA_SESSION_ID, sessionId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val fullScreenPi = PendingIntent.getActivity(
            context, 0, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isEndOfSession) "Session RPE?" else "How hard?"
        val subtitle = if (lastRpe >= 0) "Last: $lastRpe" else "Rate your exertion"

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setVibrate(longArrayOf(0, 200, 100, 200))
            .setAutoCancel(true)
            .setFullScreenIntent(fullScreenPi, true)
            .setContentIntent(fullScreenPi)
            .setOngoing(isEndOfSession)

        // RPE action buttons via WearableExtender (scrollable list on Wear OS)
        val wearableExtender = NotificationCompat.WearableExtender()

        val rpeLabels = arrayOf(
            "0 Rest", "1", "2", "3 Mod", "4", "5 Hard",
            "6", "7 V.Hard", "8", "9", "10 Max"
        )

        for (i in 0..10) {
            val actionIntent = Intent(context, RpeNotificationReceiver::class.java).apply {
                action = RpeNotificationReceiver.ACTION_RPE_NOTIFICATION
                putExtra(RpeNotificationReceiver.EXTRA_RPE_VALUE, i)
            }
            val pi = PendingIntent.getBroadcast(
                context, i + 100, actionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            wearableExtender.addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_input_add, rpeLabels[i], pi
                ).build()
            )
        }

        // Skip action for periodic prompts only
        if (!isEndOfSession) {
            val skipIntent = Intent(context, RpeNotificationReceiver::class.java).apply {
                action = RpeNotificationReceiver.ACTION_RPE_NOTIFICATION
                putExtra(RpeNotificationReceiver.EXTRA_RPE_VALUE, -1)
            }
            val skipPi = PendingIntent.getBroadcast(
                context, 200, skipIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            wearableExtender.addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_delete, "Skip", skipPi
                ).build()
            )
        }

        builder.extend(wearableExtender)

        // Auto-dismiss after 15s for periodic prompts
        if (!isEndOfSession) {
            builder.setTimeoutAfter(15_000)
        }

        nm.notify(NOTIFICATION_ID, builder.build())
    }

    fun cancel() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(NOTIFICATION_ID)
    }
}
