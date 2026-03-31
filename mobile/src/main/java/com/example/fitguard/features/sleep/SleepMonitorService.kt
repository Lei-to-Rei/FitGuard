package com.example.fitguard.features.sleep

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.fitguard.services.WearableDataListenerService
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import org.json.JSONObject

class SleepMonitorService : Service() {

    companion object {
        private const val TAG = "SleepMonitorSvc"
        private const val CHANNEL_ID = "sleep_monitoring"
        private const val NOTIFICATION_ID = 2002
        const val ACTION_SLEEP_SESSION_COMPLETE = "com.example.fitguard.SLEEP_SESSION_COMPLETE"

        private const val PREFS_NAME = "sleep_monitor"
        private const val PREF_IS_ACTIVE = "is_active"
        private const val PREF_START_TIME = "start_time"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SleepMonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SleepMonitorService::class.java))
        }

        fun isRunning(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(PREF_IS_ACTIVE, false)
        }
    }

    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val epochAccumulator = SleepEpochAccumulator()
    private var wakeLock: PowerManager.WakeLock? = null
    private var sessionStartMs: Long = 0L

    private val healthDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val type = intent?.getStringExtra("type") ?: return
            val json = JSONObject(intent.getStringExtra("data") ?: return)
            val timestamp = json.optLong("timestamp", System.currentTimeMillis())

            when (type) {
                "HeartRate" -> {
                    val hr = json.optInt("heart_rate", 0)
                    val ibiString = json.optString("ibi_list", "[]")
                    val ibis = ibiString.removeSurrounding("[", "]")
                        .split(",")
                        .mapNotNull { it.trim().toIntOrNull() }
                    epochAccumulator.addHeartRate(timestamp, hr, ibis)
                }
                "Accelerometer" -> {
                    val x = json.optInt("x", 0)
                    val y = json.optInt("y", 0)
                    val z = json.optInt("z", 0)
                    epochAccumulator.addAccelerometer(timestamp, x, y, z)
                }
                "SkinTemp" -> {
                    val objTemp = json.optDouble("object_temp", 0.0).toFloat()
                    epochAccumulator.addSkinTemperature(timestamp, objTemp)
                }
                "SpO2" -> {
                    val spo2 = json.optInt("spo2", 0)
                    epochAccumulator.addSpO2(timestamp, spo2)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        sessionStartMs = System.currentTimeMillis()
        epochAccumulator.setSessionStart(sessionStartMs)

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        acquireWakeLock()

        registerReceiver(
            healthDataReceiver,
            IntentFilter("com.example.fitguard.HEALTH_DATA"),
            RECEIVER_NOT_EXPORTED
        )

        // Persist active state
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(PREF_IS_ACTIVE, true)
            .putLong(PREF_START_TIME, sessionStartMs)
            .apply()

        // Send combined schedule to watch (metrics prefs + sleep overrides)
        val schedule = WearableDataListenerService.buildScheduleJson(this)
        WearableDataListenerService.sendScheduleToWatch(this, schedule)

        Log.d(TAG, "Sleep monitoring started")
        return START_STICKY
    }

    override fun onDestroy() {
        // Unregister receiver
        try { unregisterReceiver(healthDataReceiver) } catch (_: Exception) {}

        // Release wake lock
        wakeLock?.let { if (it.isHeld) it.release() }

        // Clear persisted state BEFORE rebuilding schedule so buildScheduleJson sees sleep=false
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(PREF_IS_ACTIVE, false)
            .remove(PREF_START_TIME)
            .apply()

        // Restore metrics-only schedule (without sleep overrides)
        val schedule = WearableDataListenerService.buildScheduleJson(this)
        val hasActiveSchedule = try {
            val json = JSONObject(schedule)
            (json.optBoolean("hr_enabled") && json.optString("hr_mode") != "Manual") ||
            (json.optBoolean("spo2_enabled") && json.optString("spo2_mode") != "Manual") ||
            (json.optBoolean("skin_temp_enabled") && json.optString("skin_temp_mode") != "Manual")
        } catch (_: Exception) { false }

        if (hasActiveSchedule) {
            WearableDataListenerService.sendScheduleToWatch(this, schedule)
        } else {
            WearableDataListenerService.clearWatchSchedule(this)
        }

        // Process accumulated data
        val sessionEndMs = System.currentTimeMillis()
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val epochs = epochAccumulator.finalizeEpochs()
                val session = SleepProcessor.process(epochs, sessionStartMs, sessionEndMs)
                val userId = FirebaseAuth.getInstance().currentUser?.uid ?: ""
                SleepDataLoader.saveSleepSession(session, userId)
                Log.d(TAG, "Sleep session processed: ${session.qualityLabel}, " +
                        "duration=${SleepDataLoader.formatDuration(session.totalSleepDurationMs)}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process sleep session: ${e.message}", e)
            }

            // Broadcast completion
            sendBroadcast(Intent(ACTION_SLEEP_SESSION_COMPLETE).apply {
                setPackage(packageName)
            })
        }

        coroutineScope.cancel()
        Log.d(TAG, "Sleep monitoring stopped")
        super.onDestroy()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sleep Monitoring",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Tracks sleep using watch sensors"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, SleepStressActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("FitGuard")
            .setContentText("Sleep monitoring active")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fitguard:sleep_monitor").apply {
            acquire(10 * 60 * 60 * 1000L) // 10 hours max
        }
        Log.d(TAG, "Wake lock acquired (10h)")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
