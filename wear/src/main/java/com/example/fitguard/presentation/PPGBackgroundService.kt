package com.example.fitguard.presentation

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import com.samsung.android.service.health.tracking.data.PpgType
import kotlinx.coroutines.*
import kotlin.math.sqrt

class PPGBackgroundService : Service() {
    private var healthTrackingService: HealthTrackingService? = null
    private var ppgTracker: HealthTracker? = null  // Single tracker for all PPG types

    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())

    // Data batching (30 seconds)
    private val batchWindow = 30000L // 30 seconds
    private val ppgGreenData = mutableListOf<PPGDataPoint>()
    private val ppgIrData = mutableListOf<PPGDataPoint>()
    private val ppgRedData = mutableListOf<PPGDataPoint>()

    private var batchJob: Job? = null

    companion object {
        private const val TAG = "PPGBackgroundService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "ppg_service_channel"

        // PPG Wavelengths (in nanometers)
        const val GREEN_WAVELENGTH = 525 // Green LED ~525nm
        const val IR_WAVELENGTH = 940    // Infrared LED ~940nm
        const val RED_WAVELENGTH = 660   // Red LED ~660nm
    }

    data class PPGDataPoint(
        val value: Int,
        val timestamp: Long,
        val wavelength: Int
    )

    data class PPGBatchData(
        val wavelength: Int,
        val values: List<Int>,
        val timestamps: List<Long>,
        val startTime: Long,
        val endTime: Long,
        val count: Int,
        val mean: Double,
        val stdDev: Double,
        val min: Int,
        val max: Int
    )

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        Log.d(TAG, "PPG Background Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification("Initializing PPG sensors..."))

        connectHealthService()
        startBatchTimer()

        return START_STICKY
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "WatchSensors::PPGWakeLock"
        )
        wakeLock?.acquire(60 * 60 * 1000L) // 1 hour max
        Log.d(TAG, "Wake lock acquired")
    }

    private fun connectHealthService() {
        val connectionListener = object : ConnectionListener {
            override fun onConnectionSuccess() {
                Log.d(TAG, "Health Service connected")
                updateNotification("Connected - Starting PPG collection...")
                checkCapabilitiesAndInitialize()
            }

            override fun onConnectionEnded() {
                Log.d(TAG, "Health Service connection ended")
            }

            override fun onConnectionFailed(error: HealthTrackerException) {
                Log.e(TAG, "Connection failed: ${error.errorCode}")
                updateNotification("Connection failed: ${error.errorCode}")
            }
        }

        healthTrackingService = HealthTrackingService(connectionListener, applicationContext)
        healthTrackingService?.connectService()
    }

    private fun checkCapabilitiesAndInitialize() {
        val healthTrackingService = this.healthTrackingService ?: return

        try {
            val availableTrackers = healthTrackingService.trackingCapability.supportHealthTrackerTypes
            Log.d(TAG, "Available trackers: $availableTrackers")

            // Check which PPG tracker type is available
            when {
                availableTrackers.contains(HealthTrackerType.PPG_CONTINUOUS) -> {
                    Log.d(TAG, "Using PPG_CONTINUOUS (new API)")
                    initializePPGTrackerNew()
                }
                availableTrackers.contains(HealthTrackerType.PPG_GREEN) -> {
                    Log.d(TAG, "Using deprecated PPG trackers (old API)")
                    initializePPGTrackersDeprecated()
                }
                else -> {
                    Log.e(TAG, "No PPG trackers available!")
                    updateNotification("Error: No PPG sensors available")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking capabilities: ${e.message}")
            updateNotification("Error: ${e.message}")
        }
    }

    // NEW API: Use PPG_CONTINUOUS with all three PPG types
    private fun initializePPGTrackerNew() {
        try {
            // Request all three PPG types in a single tracker
            val ppgTypes = setOf(PpgType.GREEN, PpgType.IR, PpgType.RED)

            ppgTracker = healthTrackingService?.getHealthTracker(
                HealthTrackerType.PPG_CONTINUOUS,
                ppgTypes
            )

            ppgTracker?.setEventListener(object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(dataPoints: MutableList<DataPoint>) {
                    dataPoints.forEach { dataPoint ->
                        // Extract all three wavelengths from the same DataPoint
                        val greenValue = dataPoint.getValue(ValueKey.PpgSet.PPG_GREEN)
                        val irValue = dataPoint.getValue(ValueKey.PpgSet.PPG_IR)
                        val redValue = dataPoint.getValue(ValueKey.PpgSet.PPG_RED)
                        val timestamp = dataPoint.timestamp

                        synchronized(ppgGreenData) {
                            if (greenValue != null && greenValue > 0) {
                                ppgGreenData.add(PPGDataPoint(greenValue, timestamp, GREEN_WAVELENGTH))
                            }
                        }

                        synchronized(ppgIrData) {
                            if (irValue != null && irValue > 0) {
                                ppgIrData.add(PPGDataPoint(irValue, timestamp, IR_WAVELENGTH))
                            }
                        }

                        synchronized(ppgRedData) {
                            if (redValue != null && redValue > 0) {
                                ppgRedData.add(PPGDataPoint(redValue, timestamp, RED_WAVELENGTH))
                            }
                        }
                    }
                }

                override fun onFlushCompleted() {
                    Log.d(TAG, "Flush completed for PPG tracker")
                }

                override fun onError(error: HealthTracker.TrackerError) {
                    Log.e(TAG, "PPG Tracker error: ${error.name}")
                    when (error) {
                        HealthTracker.TrackerError.PERMISSION_ERROR -> {
                            updateNotification("Permission error - check BODY_SENSORS")
                        }
                        HealthTracker.TrackerError.SDK_POLICY_ERROR -> {
                            updateNotification("SDK Policy error - enable Developer Mode")
                        }
                        else -> {
                            updateNotification("Tracker error: ${error.name}")
                        }
                    }
                }
            })

            updateNotification("PPG sensors active - Collecting data...")
            Log.d(TAG, "PPG tracker initialized (new API)")

        } catch (e: Exception) {
            Log.e(TAG, "Error initializing PPG tracker: ${e.message}")
            updateNotification("Error: ${e.message}")
        }
    }

    // DEPRECATED API: Fall back for older watches
    private fun initializePPGTrackersDeprecated() {
        try {
            // For older watches, use the deprecated individual trackers
            // Note: This only uses GREEN as continuous. IR and RED are on-demand
            // and cannot run simultaneously

            val ppgGreenTracker = healthTrackingService?.getHealthTracker(
                HealthTrackerType.PPG_GREEN
            )
            ppgGreenTracker?.setEventListener(createDeprecatedPPGListener(GREEN_WAVELENGTH, ppgGreenData))

            updateNotification("PPG Green sensor active (deprecated API)")
            Log.d(TAG, "PPG Green tracker initialized (deprecated API)")
            Log.w(TAG, "IR and RED trackers skipped - cannot run on-demand trackers simultaneously")

        } catch (e: Exception) {
            Log.e(TAG, "Error initializing trackers: ${e.message}")
            updateNotification("Error: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun createDeprecatedPPGListener(wavelength: Int, dataList: MutableList<PPGDataPoint>): HealthTracker.TrackerEventListener {
        return object : HealthTracker.TrackerEventListener {
            override fun onDataReceived(dataPoints: MutableList<DataPoint>) {
                synchronized(dataList) {
                    dataPoints.forEach { dataPoint ->
                        val value = when (wavelength) {
                            GREEN_WAVELENGTH -> dataPoint.getValue(ValueKey.PpgGreenSet.PPG_GREEN)
                            IR_WAVELENGTH -> dataPoint.getValue(ValueKey.PpgIrSet.PPG_IR)
                            RED_WAVELENGTH -> dataPoint.getValue(ValueKey.PpgRedSet.PPG_RED)
                            else -> null
                        }

                        if (value != null && value > 0) {
                            val timestamp = dataPoint.timestamp
                            dataList.add(PPGDataPoint(value, timestamp, wavelength))
                        }
                    }
                }
            }

            override fun onFlushCompleted() {
                Log.d(TAG, "Flush completed for wavelength: $wavelength nm")
            }

            override fun onError(error: HealthTracker.TrackerError) {
                Log.e(TAG, "Tracker error for $wavelength nm: ${error.name}")
            }
        }
    }

    private fun startBatchTimer() {
        batchJob = serviceScope.launch {
            while (isActive) {
                delay(batchWindow)
                processBatch()
            }
        }
    }

    private fun processBatch() {
        synchronized(ppgGreenData) {
            if (ppgGreenData.isNotEmpty()) {
                val batchData = createBatchData(ppgGreenData, GREEN_WAVELENGTH)
                sendBatchToPhone(batchData, "Green")
                ppgGreenData.clear()
            }
        }

        synchronized(ppgIrData) {
            if (ppgIrData.isNotEmpty()) {
                val batchData = createBatchData(ppgIrData, IR_WAVELENGTH)
                sendBatchToPhone(batchData, "IR")
                ppgIrData.clear()
            }
        }

        synchronized(ppgRedData) {
            if (ppgRedData.isNotEmpty()) {
                val batchData = createBatchData(ppgRedData, RED_WAVELENGTH)
                sendBatchToPhone(batchData, "Red")
                ppgRedData.clear()
            }
        }

        updateNotification("Batch sent - Collecting next batch...")
    }

    private fun createBatchData(dataPoints: List<PPGDataPoint>, wavelength: Int): PPGBatchData {
        val values = dataPoints.map { it.value }
        val timestamps = dataPoints.map { it.timestamp }

        val mean = values.average()
        val variance = values.map { (it - mean) * (it - mean) }.average()
        val stdDev = sqrt(variance)

        return PPGBatchData(
            wavelength = wavelength,
            values = values,
            timestamps = timestamps,
            startTime = timestamps.first(),
            endTime = timestamps.last(),
            count = values.size,
            mean = mean,
            stdDev = stdDev,
            min = values.minOrNull() ?: 0,
            max = values.maxOrNull() ?: 0
        )
    }

    private fun sendBatchToPhone(batchData: PPGBatchData, wavelengthName: String) {
        val dataClient = Wearable.getDataClient(this)

        val path = "/ppg_batch_data"

        val request = PutDataMapRequest.create(path).apply {
            dataMap.putString("wavelength_name", wavelengthName)
            dataMap.putInt("wavelength_nm", batchData.wavelength)
            dataMap.putIntegerArrayList("values", ArrayList(batchData.values))
            dataMap.putLongArray("timestamps", batchData.timestamps.toLongArray())
            dataMap.putLong("start_time", batchData.startTime)
            dataMap.putLong("end_time", batchData.endTime)
            dataMap.putInt("count", batchData.count)
            dataMap.putDouble("mean", batchData.mean)
            dataMap.putDouble("std_dev", batchData.stdDev)
            dataMap.putInt("min", batchData.min)
            dataMap.putInt("max", batchData.max)
            dataMap.putLong("batch_timestamp", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()

        dataClient.putDataItem(request)
            .addOnSuccessListener {
                Log.d(TAG, "✓ Batch sent: $wavelengthName (${batchData.count} samples)")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "✗ Failed to send batch: ${e.message}")
            }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "PPG Data Collection",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Background PPG sensor data collection"
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(contentText: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("PPG Data Collection")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(contentText))
    }

    override fun onDestroy() {
        super.onDestroy()

        batchJob?.cancel()
        ppgTracker?.unsetEventListener()
        healthTrackingService?.disconnectService()
        wakeLock?.release()
        serviceScope.cancel()

        Log.d(TAG, "PPG Background Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}