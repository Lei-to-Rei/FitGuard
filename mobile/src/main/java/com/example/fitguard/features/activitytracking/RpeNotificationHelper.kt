package com.example.fitguard.features.activitytracking

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat

class RpeNotificationHelper(private val context: Context) {

    companion object {
        private const val TAG = "PhoneRpeNotifHelper"
        const val CHANNEL_ID = "rpe_prompt_phone"
        const val NOTIFICATION_ID = 2002
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
        val nm = context.getSystemService(NotificationManager::class.java)

        val activityIntent = Intent(context, RpePromptActivity::class.java).apply {
            putExtra(RpePromptActivity.EXTRA_LAST_RPE, lastRpe)
            putExtra(RpePromptActivity.EXTRA_IS_END_OF_SESSION, isEndOfSession)
            putExtra(RpePromptActivity.EXTRA_SESSION_ID, sessionId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val fullScreenPi = PendingIntent.getActivity(
            context, 0, activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isEndOfSession) "Session RPE?" else "How hard?"
        val subtitle = if (lastRpe >= 0) "Last RPE: $lastRpe" else "Rate your exertion"

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

        if (!isEndOfSession) {
            builder.setTimeoutAfter(15_000)
        }

        nm.notify(NOTIFICATION_ID, builder.build())
        Log.d(TAG, "RPE notification shown: isEndOfSession=$isEndOfSession")
    }

    fun cancel() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(NOTIFICATION_ID)
    }
}
