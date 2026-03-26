package com.example.fitguard.presentation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
           import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.samsung.android.service.health.tracking.data.HealthTrackerType

/**
 * Foreground service for passive health tracker measurements (HR, SpO2, SkinTemp).
 * Keeps sensors alive when the watch screen is off via wake lock + foreground notification.
 * Manages its own PassiveHealthTrackerManager independent of MainActivity.
 */
class PassiveTrackerService : Service() {

    companion object {
        private const val TAG = "PassiveTrackerSvc"
        private const val CHANNEL_ID = "passive_tracking"
        private const val NOTIFICATION_ID = 1003
        private const val EXTRA_ACTION = "action"
        private const val EXTRA_TRACKER_TYPE = "tracker_type"
        private const val IDLE_STOP_DELAY_MS = 60_000L

        fun startTracker(context: Context, trackerType: String) {
            context.startForegroundService(Intent(context, PassiveTrackerService::class.java).apply {
                putExtra(EXTRA_ACTION, "start")
                putExtra(EXTRA_TRACKER_TYPE, trackerType)
            })
        }

        fun stopTracker(context: Context, trackerType: String) {
            context.startForegroundService(Intent(context, PassiveTrackerService::class.java).apply {
                putExtra(EXTRA_ACTION, "stop")
                putExtra(EXTRA_TRACKER_TYPE, trackerType)
            })
        }
    }

    private var passiveTrackerManager: PassiveHealthTrackerManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isHealthSdkReady = false
    private val pendingCommands = mutableListOf<Pair<String, String>>()
    private val activeTrackerTypes = mutableSetOf<String>()

    // Delayed stop — keep SDK connection alive between measurements
    private val handler = Handler(Looper.getMainLooper())
    private var stopDelayRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        acquireWakeLock()
        initializeHealthSdk()
        Log.d(TAG, "Passive tracker service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra(EXTRA_ACTION) ?: return START_STICKY
        val trackerType = intent.getStringExtra(EXTRA_TRACKER_TYPE) ?: return START_STICKY

        Log.d(TAG, "Command: $action $trackerType (sdkReady=$isHealthSdkReady)")

        if (isHealthSdkReady) {
            executeCommand(action, trackerType)
        } else {
            pendingCommands.add(action to trackerType)
        }

        return START_STICKY
    }

    private fun initializeHealthSdk() {
        passiveTrackerManager = PassiveHealthTrackerManager(
            context = this,
            onDataCallback = { data -> sendDataToPhone(data) }
        )
        passiveTrackerManager?.initialize(
            onSuccess = {
                Log.d(TAG, "Health SDK connected")
                isHealthSdkReady = true
                processPendingCommands()
            },
            onError = { e ->
                Log.e(TAG, "Health SDK connection failed: ${e.errorCode}")
                // Process pending commands as failures — stop commands should still work
                pendingCommands.clear()
            }
        )
    }

    private fun processPendingCommands() {
        val commands = pendingCommands.toList()
        pendingCommands.clear()
        for ((action, trackerType) in commands) {
            executeCommand(action, trackerType)
        }
    }

    private fun executeCommand(action: String, trackerType: String) {
        when (action) {
            "start" -> {
                // Cancel any pending service stop — we have a new measurement
                cancelDelayedStop()

                val started = when (trackerType) {
                    "HeartRate" -> passiveTrackerManager?.startHeartRateContinuous() ?: false
                    "SpO2" -> passiveTrackerManager?.startSpO2OnDemand() ?: false
                    "SkinTemp" -> passiveTrackerManager?.startSkinTemperatureOnDemand() ?: false
                    else -> false
                }
                if (started) {
                    activeTrackerTypes.add(trackerType)
                }
                Log.d(TAG, "Tracker start $trackerType: ${if (started) "OK" else "FAILED"}")
            }
            "stop" -> {
                val htType = when (trackerType) {
                    "HeartRate" -> HealthTrackerType.HEART_RATE_CONTINUOUS
                    "SpO2" -> HealthTrackerType.SPO2_ON_DEMAND
                    "SkinTemp" -> HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND
                    else -> null
                }
                if (htType != null) {
                    passiveTrackerManager?.stopTracker(htType)
                }
                activeTrackerTypes.remove(trackerType)
                Log.d(TAG, "Tracker stopped: $trackerType (active: $activeTrackerTypes)")

                if (activeTrackerTypes.isEmpty()) {
                    scheduleDelayedStop()
                }
            }
        }
    }

    private fun scheduleDelayedStop() {
        cancelDelayedStop()
        stopDelayRunnable = Runnable {
            Log.d(TAG, "Idle timeout — stopping service")
            stopSelf()
        }
        handler.postDelayed(stopDelayRunnable!!, IDLE_STOP_DELAY_MS)
        Log.d(TAG, "No active trackers — will stop in ${IDLE_STOP_DELAY_MS / 1000}s if idle")
    }

    private fun cancelDelayedStop() {
        stopDelayRunnable?.let { handler.removeCallbacks(it) }
        stopDelayRunnable = null
    }

    private fun sendDataToPhone(data: HealthTrackerManager.TrackerData) {
        val request = PutDataMapRequest.create("/health_tracker_data").apply {
            when (data) {
                is HealthTrackerManager.TrackerData.PPGData -> {
                    dataMap.putString("type", "PPG")
                    dataMap.putInt("green", data.green ?: 0)
                    dataMap.putInt("ir", data.ir ?: 0)
                    dataMap.putInt("red", data.red ?: 0)
                    dataMap.putLong("timestamp", data.timestamp)
                }
                is HealthTrackerManager.TrackerData.SpO2Data -> {
                    dataMap.putString("type", "SpO2")
                    dataMap.putInt("spo2", data.spO2)
                    dataMap.putInt("heart_rate", data.heartRate)
                    dataMap.putInt("status", data.status)
                    dataMap.putLong("timestamp", data.timestamp)
                }
                is HealthTrackerManager.TrackerData.HeartRateData -> {
                    dataMap.putString("type", "HeartRate")
                    dataMap.putInt("heart_rate", data.heartRate)
                    dataMap.putIntegerArrayList("ibi_list", ArrayList(data.ibiList))
                    dataMap.putIntegerArrayList("ibi_status_list", ArrayList(data.ibiStatusList))
                    dataMap.putInt("status", data.status)
                    dataMap.putLong("timestamp", data.timestamp)
                }
                is HealthTrackerManager.TrackerData.SkinTemperatureData -> {
                    dataMap.putString("type", "SkinTemp")
                    dataMap.putInt("status", data.status)
                    dataMap.putFloat("object_temp", data.objectTemperature ?: 0f)
                    dataMap.putFloat("ambient_temp", data.ambientTemperature ?: 0f)
                    dataMap.putLong("timestamp", data.timestamp)
                }
                is HealthTrackerManager.TrackerData.AccelerometerData -> {
                    dataMap.putString("type", "Accelerometer")
                    dataMap.putInt("x", data.x ?: 0)
                    dataMap.putInt("y", data.y ?: 0)
                    dataMap.putInt("z", data.z ?: 0)
                    dataMap.putLong("timestamp", data.timestamp)
                }
            }
            dataMap.putLong("sent_at", System.currentTimeMillis())
        }
        val type = request.dataMap.getString("type") ?: "Unknown"
        val putRequest = request.asPutDataRequest().setUrgent()

        Wearable.getDataClient(this).putDataItem(putRequest)
            .addOnSuccessListener { Log.d(TAG, "✓ $type sent to phone") }
            .addOnFailureListener { Log.e(TAG, "✗ Failed to send $type: ${it.message}") }
    }

    // ===== Notification =====

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Health Tracking",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Passive health tracker monitoring"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("FitGuard")
            .setContentText("Health monitoring active")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fitguard:passive_tracker").apply {
            acquire(4 * 60 * 60 * 1000L) // 4 hours max
        }
        Log.d(TAG, "Wake lock acquired")
    }

    // ===== Lifecycle =====

    override fun onDestroy() {
        cancelDelayedStop()
        passiveTrackerManager?.disconnect()
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Wake lock released")
            }
        }
        activeTrackerTypes.clear()
        isHealthSdkReady = false
        Log.d(TAG, "Passive tracker service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
