package com.example.fitguard.presentation

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
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import org.json.JSONObject

/**
 * Foreground service for passive health tracker measurements (HR, SpO2, SkinTemp, Accel).
 * Supports both manual start/stop commands and schedule-based automatic measurements.
 * Queues on-demand sensors (SpO2, SkinTemp) to prevent overlap.
 */
class PassiveTrackerService : Service() {

    companion object {
        private const val TAG = "PassiveTrackerSvc"
        private const val CHANNEL_ID = "passive_tracking"
        private const val NOTIFICATION_ID = 1003
        private const val EXTRA_ACTION = "action"
        private const val EXTRA_TRACKER_TYPE = "tracker_type"
        private const val IDLE_STOP_DELAY_MS = 60_000L

        // Measurement timeouts
        private const val HR_MEASUREMENT_DURATION_MS = 15_000L
        private const val STRESS_COLLECTION_DURATION_MS = 180_000L  // 3 min for IBI → stress
        private const val SPO2_TIMEOUT_MS = 90_000L
        private const val SKIN_TEMP_TIMEOUT_MS = 30_000L
        private const val AUTO_INTERVAL_10_MIN_MS = 10 * 60 * 1000L
        private const val AUTO_INTERVAL_15_MIN_MS = 15 * 60 * 1000L
        private const val MAX_HR_RETRIES = 2
        private const val MIN_IBI_FOR_STRESS = 30

        private const val SCHEDULE_PREFS = "passive_tracker_schedule"

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

        fun setSchedule(context: Context, scheduleJson: String) {
            context.startForegroundService(Intent(context, PassiveTrackerService::class.java).apply {
                putExtra(EXTRA_ACTION, "set_schedule")
                putExtra("schedule_json", scheduleJson)
            })
        }

        fun clearSchedule(context: Context) {
            context.startForegroundService(Intent(context, PassiveTrackerService::class.java).apply {
                putExtra(EXTRA_ACTION, "clear_schedule")
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

    // Schedule state
    private var scheduleConfig: JSONObject? = null
    private var intervalRunnable: Runnable? = null
    private val measurementQueue = ArrayDeque<String>()
    private var isQueueProcessing = false
    private val scheduledActiveTrackers = mutableSetOf<String>()
    private val trackerTimeoutRunnables = mutableMapOf<String, Runnable>()
    private var hrRetryCount = 0
    private var hrReceivedValid = false
    private var stressIbiCount = 0

    // Activity conflict — pause scheduled measurements during workouts
    private val activityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WearableMessageListenerService.ACTION_START_ACTIVITY -> {
                    Log.d(TAG, "Workout started — pausing scheduled measurements")
                    stopAllScheduledMeasurements()
                }
                WearableMessageListenerService.ACTION_STOP_ACTIVITY -> {
                    Log.d(TAG, "Workout stopped — resuming schedule")
                    scheduleConfig?.let { reconcileSchedule(it) }
                }
            }
        }
    }

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

        // Register for activity start/stop
        val filter = IntentFilter().apply {
            addAction(WearableMessageListenerService.ACTION_START_ACTIVITY)
            addAction(WearableMessageListenerService.ACTION_STOP_ACTIVITY)
        }
        registerReceiver(activityReceiver, filter, RECEIVER_NOT_EXPORTED)

        // Restore persisted schedule
        val savedSchedule = getSharedPreferences(SCHEDULE_PREFS, MODE_PRIVATE)
            .getString("config", null)
        if (savedSchedule != null) {
            try {
                scheduleConfig = JSONObject(savedSchedule)
                Log.d(TAG, "Restored persisted schedule")
            } catch (_: Exception) {}
        }

        Log.d(TAG, "Passive tracker service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra(EXTRA_ACTION) ?: return START_STICKY

        when (action) {
            "set_schedule" -> {
                val json = intent.getStringExtra("schedule_json") ?: return START_STICKY
                try {
                    val config = JSONObject(json)
                    scheduleConfig = config
                    // Persist
                    getSharedPreferences(SCHEDULE_PREFS, MODE_PRIVATE).edit()
                        .putString("config", json).apply()
                    Log.d(TAG, "Schedule received: $json")
                    if (isHealthSdkReady) {
                        reconcileSchedule(config)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Invalid schedule JSON: ${e.message}")
                }
            }
            "clear_schedule" -> {
                Log.d(TAG, "Schedule cleared")
                scheduleConfig = null
                getSharedPreferences(SCHEDULE_PREFS, MODE_PRIVATE).edit().remove("config").apply()
                stopAllScheduledMeasurements()
                if (activeTrackerTypes.isEmpty()) {
                    scheduleDelayedStop()
                }
            }
            "start", "stop" -> {
                val trackerType = intent.getStringExtra(EXTRA_TRACKER_TYPE) ?: return START_STICKY
                Log.d(TAG, "Command: $action $trackerType (sdkReady=$isHealthSdkReady)")
                if (isHealthSdkReady) {
                    executeCommand(action, trackerType)
                } else {
                    pendingCommands.add(action to trackerType)
                }
            }
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
                // Apply persisted schedule
                scheduleConfig?.let { reconcileSchedule(it) }
            },
            onError = { e ->
                Log.e(TAG, "Health SDK connection failed: ${e.errorCode}")
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
                cancelDelayedStop()

                val started = when (trackerType) {
                    "HeartRate" -> passiveTrackerManager?.startHeartRateContinuous() ?: false
                    "SpO2" -> passiveTrackerManager?.startSpO2OnDemand() ?: false
                    "SkinTemp" -> passiveTrackerManager?.startSkinTemperatureOnDemand() ?: false
                    "Accelerometer" -> passiveTrackerManager?.startAccelerometerContinuous() ?: false
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
                    "Accelerometer" -> HealthTrackerType.ACCELEROMETER_CONTINUOUS
                    else -> null
                }
                if (htType != null) {
                    passiveTrackerManager?.stopTracker(htType)
                }
                activeTrackerTypes.remove(trackerType)
                scheduledActiveTrackers.remove(trackerType)
                trackerTimeoutRunnables.remove(trackerType)?.let { handler.removeCallbacks(it) }
                Log.d(TAG, "Tracker stopped: $trackerType (active: $activeTrackerTypes)")

                if (activeTrackerTypes.isEmpty()) {
                    scheduleDelayedStop()
                }
            }
        }
    }

    // ===== Schedule-based measurement =====

    private fun reconcileSchedule(config: JSONObject) {
        // Stop all current scheduled measurements first
        stopAllScheduledMeasurements()

        val hrEnabled = config.optBoolean("hr_enabled", false)
        val hrMode = config.optString("hr_mode", "Manual")
        val spo2Enabled = config.optBoolean("spo2_enabled", false)
        val spo2Mode = config.optString("spo2_mode", "Manual")
        val skinTempEnabled = config.optBoolean("skin_temp_enabled", false)
        val skinTempMode = config.optString("skin_temp_mode", "Manual")
        val accelEnabled = config.optBoolean("accel_enabled", false)
        val accelMode = config.optString("accel_mode", "Manual")

        // Start continuous trackers immediately
        if (hrEnabled && hrMode == "Continuous") {
            executeCommand("start", "HeartRate")
            scheduledActiveTrackers.add("HeartRate")
        }
        if (accelEnabled && accelMode == "Continuous") {
            executeCommand("start", "Accelerometer")
            scheduledActiveTrackers.add("Accelerometer")
        }

        // Determine interval for periodic measurements
        val periodicTrackers = mutableListOf<Pair<String, String>>() // trackerType, mode
        if (hrEnabled && hrMode.startsWith("Every")) {
            periodicTrackers.add("HeartRate" to hrMode)
        }
        if (spo2Enabled && spo2Mode.startsWith("Every")) {
            periodicTrackers.add("SpO2" to spo2Mode)
        }
        if (skinTempEnabled && skinTempMode.startsWith("Every")) {
            periodicTrackers.add("SkinTemp" to skinTempMode)
        }

        if (periodicTrackers.isNotEmpty()) {
            // Use shortest interval among all periodic trackers
            val intervalMs = periodicTrackers.minOf { (_, mode) -> parseIntervalMs(mode) }
            startPeriodicMeasurements(periodicTrackers.map { it.first }, intervalMs)
        }

        Log.d(TAG, "Schedule reconciled: HR=$hrMode, SpO2=$spo2Mode, SkinTemp=$skinTempMode, Accel=$accelMode")
    }

    private fun parseIntervalMs(mode: String): Long {
        return when {
            mode.contains("10") -> AUTO_INTERVAL_10_MIN_MS
            mode.contains("15") -> AUTO_INTERVAL_15_MIN_MS
            else -> AUTO_INTERVAL_10_MIN_MS
        }
    }

    private fun startPeriodicMeasurements(trackerTypes: List<String>, intervalMs: Long) {
        // Run first cycle immediately
        startQueuedMeasurementCycle(trackerTypes)

        // Schedule repeating cycles
        intervalRunnable = object : Runnable {
            override fun run() {
                startQueuedMeasurementCycle(trackerTypes)
                handler.postDelayed(this, intervalMs)
            }
        }
        handler.postDelayed(intervalRunnable!!, intervalMs)
        Log.d(TAG, "Periodic measurements started: $trackerTypes every ${intervalMs / 60000}min")
    }

    private fun startQueuedMeasurementCycle(trackerTypes: List<String>) {
        measurementQueue.clear()
        measurementQueue.addAll(trackerTypes)
        isQueueProcessing = false
        processNextInQueue()
    }

    private fun processNextInQueue() {
        if (measurementQueue.isEmpty()) {
            isQueueProcessing = false
            Log.d(TAG, "Measurement queue complete")
            return
        }

        isQueueProcessing = true
        val trackerType = measurementQueue.removeFirst()
        startScheduledMeasurement(trackerType)
    }

    private fun startScheduledMeasurement(trackerType: String) {
        Log.d(TAG, "Starting scheduled measurement: $trackerType")
        executeCommand("start", trackerType)
        scheduledActiveTrackers.add(trackerType)

        // Set timeout for auto-stop
        val timeoutMs = when (trackerType) {
            "HeartRate" -> STRESS_COLLECTION_DURATION_MS  // run long enough to collect IBI for stress
            "SpO2" -> SPO2_TIMEOUT_MS
            "SkinTemp" -> SKIN_TEMP_TIMEOUT_MS
            else -> 30_000L
        }

        if (trackerType == "HeartRate") {
            hrReceivedValid = false
            hrRetryCount = 0
            stressIbiCount = 0
        }

        val timeoutRunnable = Runnable {
            if (trackerType == "HeartRate" && !hrReceivedValid && hrRetryCount < MAX_HR_RETRIES) {
                hrRetryCount++
                Log.d(TAG, "HR retry $hrRetryCount — no valid data yet")
                val retryRunnable = Runnable { stopScheduledMeasurement(trackerType) }
                trackerTimeoutRunnables[trackerType] = retryRunnable
                handler.postDelayed(retryRunnable, HR_MEASUREMENT_DURATION_MS)
            } else {
                stopScheduledMeasurement(trackerType)
            }
        }
        trackerTimeoutRunnables[trackerType] = timeoutRunnable
        handler.postDelayed(timeoutRunnable, timeoutMs)
    }

    private fun stopScheduledMeasurement(trackerType: String) {
        // Don't stop continuous trackers that are also in the schedule
        val config = scheduleConfig
        if (config != null) {
            val mode = when (trackerType) {
                "HeartRate" -> config.optString("hr_mode", "Manual")
                "Accelerometer" -> config.optString("accel_mode", "Manual")
                else -> "Manual"
            }
            if (mode == "Continuous") {
                Log.d(TAG, "Skipping stop for continuous tracker: $trackerType")
                scheduledActiveTrackers.remove(trackerType)
                trackerTimeoutRunnables.remove(trackerType)?.let { handler.removeCallbacks(it) }
                processNextInQueue()
                return
            }
        }

        executeCommand("stop", trackerType)
        processNextInQueue()
    }

    /**
     * Called from sendDataToPhone when valid data is received.
     * Auto-stops scheduled measurements that have received valid data.
     * HeartRate keeps running until enough IBI data for stress calculation.
     */
    private fun checkScheduledAutoStop(trackerType: String, isValid: Boolean, ibiCount: Int = 0) {
        if (trackerType !in scheduledActiveTrackers) return
        if (!isValid) return

        // Don't auto-stop continuous trackers
        val config = scheduleConfig ?: return
        val mode = when (trackerType) {
            "HeartRate" -> config.optString("hr_mode", "Manual")
            "Accelerometer" -> config.optString("accel_mode", "Manual")
            else -> "non-continuous"
        }
        if (mode == "Continuous") return

        // HeartRate: keep collecting IBI for stress calculation
        if (trackerType == "HeartRate") {
            hrReceivedValid = true
            stressIbiCount += ibiCount
            if (stressIbiCount < MIN_IBI_FOR_STRESS) {
                return  // keep running — not enough IBI yet
            }
            Log.d(TAG, "Auto-stopping HeartRate — collected $stressIbiCount IBIs for stress")
        } else {
            Log.d(TAG, "Auto-stopping $trackerType — valid data received")
        }
        stopScheduledMeasurement(trackerType)
    }

    private fun stopAllScheduledMeasurements() {
        intervalRunnable?.let { handler.removeCallbacks(it) }
        intervalRunnable = null
        measurementQueue.clear()
        isQueueProcessing = false

        for (trackerType in scheduledActiveTrackers.toList()) {
            trackerTimeoutRunnables.remove(trackerType)?.let { handler.removeCallbacks(it) }
            executeCommand("stop", trackerType)
        }
        scheduledActiveTrackers.clear()
    }

    // ===== Idle stop =====

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

    // ===== Data send + auto-stop =====

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
            .addOnSuccessListener { Log.d(TAG, "Sent $type to phone") }
            .addOnFailureListener { Log.e(TAG, "Failed to send $type: ${it.message}") }

        // Check for auto-stop on valid data
        when (data) {
            is HealthTrackerManager.TrackerData.HeartRateData -> {
                val validIbiCount = data.ibiList.zip(data.ibiStatusList)
                    .count { (ibi, status) -> status == 0 && ibi in 300..2000 }
                checkScheduledAutoStop("HeartRate", data.heartRate > 0 && data.status == 1, validIbiCount)
            }
            is HealthTrackerManager.TrackerData.SpO2Data -> {
                val valid = (data.spO2 > 0 && data.status == 2) || data.status < 0
                checkScheduledAutoStop("SpO2", valid)
            }
            is HealthTrackerManager.TrackerData.SkinTemperatureData -> {
                checkScheduledAutoStop("SkinTemp", data.status == 0)
            }
            else -> {}
        }
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
            acquire(24 * 60 * 60 * 1000L) // 24 hours max
        }
        Log.d(TAG, "Wake lock acquired")
    }

    // ===== Lifecycle =====

    override fun onDestroy() {
        cancelDelayedStop()
        stopAllScheduledMeasurements()
        try { unregisterReceiver(activityReceiver) } catch (_: Exception) {}
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
