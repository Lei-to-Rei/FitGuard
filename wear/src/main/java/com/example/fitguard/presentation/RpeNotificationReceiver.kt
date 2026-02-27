package com.example.fitguard.presentation

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class RpeNotificationReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "RpeNotifReceiver"
        const val ACTION_RPE_NOTIFICATION = "com.example.fitguard.wear.RPE_NOTIFICATION_ACTION"
        const val EXTRA_RPE_VALUE = "rpe_value"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RPE_NOTIFICATION) return

        val rpeValue = intent.getIntExtra(EXTRA_RPE_VALUE, -1)
        Log.d(TAG, "RPE notification action: $rpeValue")

        // Send the same broadcast that RpePromptActivity uses
        context.sendBroadcast(Intent(RpePromptActivity.ACTION_RPE_RESPONSE).apply {
            setPackage(context.packageName)
            putExtra(RpePromptActivity.EXTRA_RPE_VALUE, rpeValue)
        })

        // Cancel the notification
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(RpeNotificationHelper.NOTIFICATION_ID)
    }
}
