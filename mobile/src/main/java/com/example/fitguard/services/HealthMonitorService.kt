package com.example.fitguard.services

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
import com.example.fitguard.features.activitytracking.ActivityTrackingViewModel
import com.example.fitguard.features.metrics.MetricsMonitoringActivity
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/**
 * Persistent foreground service for health tracker scheduling.
 * Runs even when the app is closed or screen is off.
 * Handles: Manual (no-op), Every 10 minutes (sequential queue), Continuous (HR only).
 */
class HealthMonitorService : Service() {

    companion object {
        private const val TAG = "HealthMonitorSvc"
        private const val CHANNEL_ID = "health_monitoring"
        private const val NOTIFICATION_ID = 2004
        private const val PREFS_NAME = "health_tracker_prefs"

        // Measurement durations
        private const val HR_MEASUREMENT_DURATION_MS = 15_000L
        private const val SPO2_MEASUREMENT_TIMEOUT_MS = 90_000L
        private const val SKIN_TEMP_MEASUREMENT_TIMEOUT_MS = 30_000L
        private const val AUTO_INTERVAL_MS = 60_000L // 10 minutes
        private const val MAX_HR_RETRIES = 2

        fun start(context: Context) {
            context.startForegroundService(Intent(context, HealthMonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HealthMonitorService::class.java))
        }

        fun isRunning(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean("monitor_active", false)
        }
    }

    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    // Interval scheduling
    private var intervalRunnable: Runnable? = null

    // Active measurement tracking
    private var isHrMeasuring = false
    private var isHrContinuousMode = false
    private var isSpo2Measuring = false
    private var isSkinTempMeasuring = false
    private var hrReceivedValid = false
    private var hrRetryCount = 0
    private var hrStopRunnable: Runnable? = null
    private var spo2StopRunnable: Runnable? = null
    private var skinTempStopRunnable: Runnable? = null

    // Sequential measurement queue
    private val measurementQueue = ArrayDeque<String>()
    private var isQueueRunning = false

    // Listens for health data from WearableDataListenerService to handle auto-stop logic
    private val healthDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val type = intent?.getStringExtra("type") ?: return
            val json = JSONObject(intent.getStringExtra("data") ?: return)

            when (type) {
                "HeartRate" -> {
                    val hr = json.optInt("heart_rate", 0)
                    val status = json.optInt("status", -1)
                    if (hr > 0 && status == 1) {
                        hrReceivedValid = true
                        Log.d(TAG, "HR received: $hr bpm")
                        // Auto-stop for queue mode (not continuous)
                        if (isHrMeasuring && !isHrContinuousMode) {
                            stopMeasurement("HeartRate")
                        }
                    }
                }
                "SpO2" -> {
                    val spo2 = json.optInt("spo2", 0)
                    val status = json.optInt("status", -1)
                    if (spo2 > 0 && status == 2) {
                        Log.d(TAG, "SpO2 received: $spo2%")
                        if (isSpo2Measuring) stopMeasurement("SpO2")
                    } else if (status < 0 && isSpo2Measuring) {
                        Log.w(TAG, "SpO2 measurement failed (status=$status)")
                        stopMeasurement("SpO2")
                    }
                }
                "SkinTemp" -> {
                    val status = json.optInt("status", -1)
                    if (status == 0 && isSkinTempMeasuring) {
                        Log.d(TAG, "SkinTemp received")
                        stopMeasurement("SkinTemp")
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        acquireWakeLock()
        registerReceiver(
            healthDataReceiver,
            IntentFilter("com.example.fitguard.HEALTH_DATA"),
            RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Persist active state
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean("monitor_active", true).apply()

        reconcileTracking()

        Log.d(TAG, "Health monitoring service started")
        return START_STICKY
    }

    // ===== Tracking Logic =====

    /**
     * Idempotent reconciliation — compares desired state (prefs) with current state
     * and only starts/stops what changed. Safe to call on every onStartCommand.
     */
    private fun reconcileTracking() {
        if (ActivityTrackingViewModel.activeSessionId != null) {
            Log.d(TAG, "Tracking skipped — workout session active")
            return
        }

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val hrEnabled = prefs.getBoolean("switch_hr", false)
        val spo2Enabled = prefs.getBoolean("switch_spo2", false)
        val skinTempEnabled = prefs.getBoolean("switch_skin_temp", false)
        val hrMode = prefs.getString("hr_mode", "Manual") ?: "Manual"
        val spo2Mode = prefs.getString("spo2_mode", "Manual") ?: "Manual"
        val skinTempMode = prefs.getString("skin_temp_mode", "Manual") ?: "Manual"

        Log.d(TAG, "Reconcile: HR=$hrEnabled($hrMode) SpO2=$spo2Enabled($spo2Mode) SkinTemp=$skinTempEnabled($skinTempMode)")

        // --- Continuous HR: start/stop as needed ---
        val wantHrContinuous = hrEnabled && hrMode == "Continuous"
        if (wantHrContinuous && !isHrContinuousMode) {
            isHrContinuousMode = true
            coroutineScope.launch {
                val sent = sendTrackerCommand("start", "HeartRate")
                if (sent) {
                    isHrMeasuring = true
                    Log.d(TAG, "HR Continuous started")
                } else {
                    isHrContinuousMode = false // Allow retry on next reconcile
                }
            }
        } else if (!wantHrContinuous && isHrContinuousMode) {
            coroutineScope.launch { sendTrackerCommand("stop", "HeartRate") }
            isHrMeasuring = false
            isHrContinuousMode = false
            Log.d(TAG, "HR Continuous stopped")
        }

        // --- Stop active measurements for toggled-off sensors ---
        if (!hrEnabled && isHrMeasuring && !isHrContinuousMode) stopMeasurement("HeartRate")
        if (!spo2Enabled && isSpo2Measuring) stopMeasurement("SpO2")
        if (!skinTempEnabled && isSkinTempMeasuring) stopMeasurement("SkinTemp")

        // --- Remove toggled-off sensors from pending queue ---
        if (!hrEnabled || hrMode != "Every 10 minutes") measurementQueue.removeAll { it == "HeartRate" }
        if (!spo2Enabled || spo2Mode != "Every 10 minutes") measurementQueue.removeAll { it == "SpO2" }
        if (!skinTempEnabled || skinTempMode != "Every 10 minutes") measurementQueue.removeAll { it == "SkinTemp" }

        // --- Interval schedule: start/stop as needed (don't reset if already running) ---
        val wantInterval = (hrEnabled && hrMode == "Every 10 minutes")
                || (spo2Enabled && spo2Mode == "Every 10 minutes")
                || (skinTempEnabled && skinTempMode == "Every 10 minutes")

        if (wantInterval && intervalRunnable == null) {
            startQueuedMeasurementCycle()
            intervalRunnable = object : Runnable {
                override fun run() {
                    startQueuedMeasurementCycle()
                    handler.postDelayed(this, AUTO_INTERVAL_MS)
                }
            }
            handler.postDelayed(intervalRunnable!!, AUTO_INTERVAL_MS)
            Log.d(TAG, "Interval schedule started (${AUTO_INTERVAL_MS / 1000}s)")
        } else if (!wantInterval && intervalRunnable != null) {
            handler.removeCallbacks(intervalRunnable!!)
            intervalRunnable = null
            measurementQueue.clear()
            isQueueRunning = false
            Log.d(TAG, "Interval schedule stopped")
        }

        // --- Nothing needed? Stop service ---
        if (!wantHrContinuous && !wantInterval) {
            Log.d(TAG, "No non-Manual trackers — stopping service")
            stopSelf()
        }
    }

    // ===== Sequential Measurement Queue =====

    private fun startQueuedMeasurementCycle() {
        if (isQueueRunning) return
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        measurementQueue.clear()

        if (prefs.getBoolean("switch_hr", false) && prefs.getString("hr_mode", "Manual") == "Every 10 minutes")
            measurementQueue.addLast("HeartRate")
        if (prefs.getBoolean("switch_spo2", false) && prefs.getString("spo2_mode", "Manual") == "Every 10 minutes")
            measurementQueue.addLast("SpO2")
        if (prefs.getBoolean("switch_skin_temp", false) && prefs.getString("skin_temp_mode", "Manual") == "Every 10 minutes")
            measurementQueue.addLast("SkinTemp")

        if (measurementQueue.isEmpty()) return
        Log.d(TAG, "Starting queued measurement cycle: $measurementQueue")
        processNextInQueue()
    }

    private fun processNextInQueue() {
        if (measurementQueue.isEmpty()) {
            isQueueRunning = false
            Log.d(TAG, "Measurement queue complete")
            return
        }
        isQueueRunning = true
        val next = measurementQueue.removeFirst()
        Log.d(TAG, "Queue: starting $next (${measurementQueue.size} remaining)")
        startMeasurement(next)
    }

    // ===== Measurement Control =====

    private fun startMeasurement(trackerType: String) {
        coroutineScope.launch {
            val sent = sendTrackerCommand("start", trackerType)
            if (!sent) {
                // Skip to next in queue if command failed
                if (isQueueRunning) {
                    handler.postDelayed({ processNextInQueue() }, 1000)
                }
                return@launch
            }

            when (trackerType) {
                "HeartRate" -> {
                    isHrMeasuring = true
                    hrReceivedValid = false
                    hrRetryCount = 0
                    hrStopRunnable = Runnable {
                        if (!hrReceivedValid && hrRetryCount < MAX_HR_RETRIES) {
                            hrRetryCount++
                            Log.d(TAG, "HR retry $hrRetryCount — no valid data yet")
                            handler.postDelayed(hrStopRunnable!!, HR_MEASUREMENT_DURATION_MS)
                        } else {
                            stopMeasurement("HeartRate")
                            hrRetryCount = 0
                        }
                    }
                    handler.postDelayed(hrStopRunnable!!, HR_MEASUREMENT_DURATION_MS)
                }
                "SpO2" -> {
                    isSpo2Measuring = true
                    spo2StopRunnable = Runnable { stopMeasurement("SpO2") }
                    handler.postDelayed(spo2StopRunnable!!, SPO2_MEASUREMENT_TIMEOUT_MS)
                }
                "SkinTemp" -> {
                    isSkinTempMeasuring = true
                    skinTempStopRunnable = Runnable { stopMeasurement("SkinTemp") }
                    handler.postDelayed(skinTempStopRunnable!!, SKIN_TEMP_MEASUREMENT_TIMEOUT_MS)
                }
            }
        }
    }

    private fun stopMeasurement(trackerType: String) {
        coroutineScope.launch { sendTrackerCommand("stop", trackerType) }
        when (trackerType) {
            "HeartRate" -> {
                isHrMeasuring = false
                hrStopRunnable?.let { handler.removeCallbacks(it) }
                hrStopRunnable = null
            }
            "SpO2" -> {
                isSpo2Measuring = false
                spo2StopRunnable?.let { handler.removeCallbacks(it) }
                spo2StopRunnable = null
            }
            "SkinTemp" -> {
                isSkinTempMeasuring = false
                skinTempStopRunnable?.let { handler.removeCallbacks(it) }
                skinTempStopRunnable = null
            }
        }
        // Trigger next in queue
        if (isQueueRunning) {
            handler.postDelayed({ processNextInQueue() }, 1000)
        }
    }

    private fun stopAllTracking() {
        // Cancel interval runnable
        intervalRunnable?.let { handler.removeCallbacks(it) }
        intervalRunnable = null

        // Cancel stop/retry runnables
        hrStopRunnable?.let { handler.removeCallbacks(it) }
        hrStopRunnable = null
        spo2StopRunnable?.let { handler.removeCallbacks(it) }
        spo2StopRunnable = null
        skinTempStopRunnable?.let { handler.removeCallbacks(it) }
        skinTempStopRunnable = null

        // Clear queue
        measurementQueue.clear()
        isQueueRunning = false

        // Stop active measurements on watch
        if (isHrMeasuring) {
            coroutineScope.launch { sendTrackerCommand("stop", "HeartRate") }
            isHrMeasuring = false
            isHrContinuousMode = false
        }
        if (isSpo2Measuring) {
            coroutineScope.launch { sendTrackerCommand("stop", "SpO2") }
            isSpo2Measuring = false
        }
        if (isSkinTempMeasuring) {
            coroutineScope.launch { sendTrackerCommand("stop", "SkinTemp") }
            isSkinTempMeasuring = false
        }
    }

    // ===== Watch Communication =====

    private suspend fun sendTrackerCommand(command: String, trackerType: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val nodes = Wearable.getNodeClient(this@HealthMonitorService)
                    .connectedNodes.await()
                if (nodes.isEmpty()) {
                    Log.w(TAG, "No watch connected for $command $trackerType")
                    return@withContext false
                }
                val payload = JSONObject().apply {
                    put("tracker_type", trackerType)
                }.toString().toByteArray(Charsets.UTF_8)

                for (node in nodes) {
                    Wearable.getMessageClient(this@HealthMonitorService)
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

    // ===== Service Lifecycle =====

    override fun onDestroy() {
        stopAllTracking()

        try { unregisterReceiver(healthDataReceiver) } catch (_: Exception) {}

        wakeLock?.let { if (it.isHeld) it.release() }

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean("monitor_active", false).apply()

        coroutineScope.cancel()
        Log.d(TAG, "Health monitoring service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ===== Notification =====

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Health Monitoring",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent health tracker monitoring"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MetricsMonitoringActivity::class.java).apply {
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
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fitguard:health_monitor").apply {
            acquire(24 * 60 * 60 * 1000L) // 24 hours max
        }
        Log.d(TAG, "Wake lock acquired (24h)")
    }
}
