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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.Wearable
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
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

        // Restart interval for on-demand trackers (SpO2, SkinTemp)
        private const val ON_DEMAND_RESTART_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes

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
    private val handler = Handler(Looper.getMainLooper())
    private var onDemandRestartRunnable: Runnable? = null

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

        // Start all 4 trackers on the watch
        startAllTrackers()

        // Schedule periodic restart of on-demand trackers
        scheduleOnDemandRestart()

        Log.d(TAG, "Sleep monitoring started")
        return START_STICKY
    }

    private fun startAllTrackers() {
        coroutineScope.launch {
            sendTrackerCommand("start", "HeartRate")
            sendTrackerCommand("start", "Accelerometer")
            sendTrackerCommand("start", "SkinTemp")
            sendTrackerCommand("start", "SpO2")
        }
    }

    private fun stopAllTrackers() {
        coroutineScope.launch {
            sendTrackerCommand("stop", "HeartRate")
            sendTrackerCommand("stop", "Accelerometer")
            sendTrackerCommand("stop", "SkinTemp")
            sendTrackerCommand("stop", "SpO2")
        }
    }

    private fun scheduleOnDemandRestart() {
        onDemandRestartRunnable = object : Runnable {
            override fun run() {
                coroutineScope.launch {
                    Log.d(TAG, "Restarting on-demand trackers (SpO2, SkinTemp)")
                    sendTrackerCommand("start", "SpO2")
                    sendTrackerCommand("start", "SkinTemp")
                }
                handler.postDelayed(this, ON_DEMAND_RESTART_INTERVAL_MS)
            }
        }
        handler.postDelayed(onDemandRestartRunnable!!, ON_DEMAND_RESTART_INTERVAL_MS)
    }

    private suspend fun sendTrackerCommand(command: String, trackerType: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val nodes = Wearable.getNodeClient(this@SleepMonitorService)
                    .connectedNodes.await()
                if (nodes.isEmpty()) return@withContext false

                val payload = JSONObject().apply {
                    put("tracker_type", trackerType)
                }.toString().toByteArray(Charsets.UTF_8)

                for (node in nodes) {
                    Wearable.getMessageClient(this@SleepMonitorService)
                        .sendMessage(node.id, "/fitguard/tracker/$command", payload).await()
                }
                Log.d(TAG, "Sent tracker $command for $trackerType")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send tracker command: ${e.message}", e)
                false
            }
        }
    }

    override fun onDestroy() {
        // Stop tracker restarts
        onDemandRestartRunnable?.let { handler.removeCallbacks(it) }

        // Stop watch trackers
        stopAllTrackers()

        // Unregister receiver
        try { unregisterReceiver(healthDataReceiver) } catch (_: Exception) {}

        // Release wake lock
        wakeLock?.let { if (it.isHeld) it.release() }

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

        // Clear persisted state
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(PREF_IS_ACTIVE, false)
            .remove(PREF_START_TIME)
            .apply()

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
