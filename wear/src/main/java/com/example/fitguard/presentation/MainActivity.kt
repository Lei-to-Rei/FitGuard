package com.example.fitguard.presentation

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var buttonContainer: LinearLayout
    private lateinit var healthTrackerManager: HealthTrackerManager
    private lateinit var sensorSequenceManager: SensorSequenceManager
    private val activeTrackerButtons = mutableMapOf<HealthTrackerType, Button>()
    private val individualButtons = mutableListOf<Button>()
    private var sequenceButton: Button? = null
    private var sequenceStatusText: TextView? = null
    private var activityCommandReceiver: BroadcastReceiver? = null

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val TAG = "WatchHealthTrackers"

        // New granular health permissions for API 36+ (Wear OS 6)
        // These show as "Fitness and Wellness" in Settings
        private val HEALTH_PERMISSIONS_API36 = arrayOf(
            "android.permission.health.READ_HEART_RATE",
            "android.permission.health.READ_OXYGEN_SATURATION",
            "android.permission.health.READ_SKIN_TEMPERATURE",
            "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
        )

        // Legacy permissions for API 30-35
        private val LEGACY_PERMISSIONS = arrayOf(
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.BODY_SENSORS_BACKGROUND
        )

        // Activity recognition permissions
        private val ACTIVITY_PERMISSIONS = arrayOf(
            Manifest.permission.ACTIVITY_RECOGNITION
        )

        // Other required permissions
        private val OTHER_PERMISSIONS = buildList {
            add(Manifest.permission.WAKE_LOCK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        statusText = TextView(this).apply {
            setPadding(16, 16, 16, 16)
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            text = "FitGuard Health Trackers\nInitializing..."
        }

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        buttonContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        scrollView.addView(buttonContainer)
        mainContainer.addView(statusText)
        mainContainer.addView(scrollView)
        setContentView(mainContainer)

        checkAndRequestPermissions()
        registerActivityCommandReceiver()
    }

    private fun checkAndRequestPermissions() {
        // Determine which permissions to use based on SDK version
        val healthPermissions = if (Build.VERSION.SDK_INT >= 36) {
            HEALTH_PERMISSIONS_API36
        } else {
            LEGACY_PERMISSIONS
        }

        val allPermissions = healthPermissions + ACTIVITY_PERMISSIONS + OTHER_PERMISSIONS

        val missingPermissions = allPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            onPermissionsGranted()
        } else {
            val permissionLabels = if (Build.VERSION.SDK_INT >= 36) {
                """
                Requesting permissions:

                ✓ Fitness & Wellness
                  • Heart Rate
                  • Blood Oxygen (SpO2)
                  • Skin Temperature
                  • Background Health Data

                ✓ Physical Activity
                  • Activity Recognition
                  • Step Counting
                """.trimIndent()
            } else {
                """
                Requesting permissions:

                ✓ Body Sensors
                ✓ Physical Activity
                ✓ Background Monitoring
                """.trimIndent()
            }

            statusText.text = permissionLabels
            requestPermissions(allPermissions)
        }
    }

    private fun requestPermissions(permissions: Array<String>) {
        ActivityCompat.requestPermissions(
            this,
            permissions,
            PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }

            if (allGranted) {
                onPermissionsGranted()
            } else {
                // Check which permissions were denied
                val deniedPermissions = permissions.filterIndexed { index, permission ->
                    grantResults[index] != PackageManager.PERMISSION_GRANTED
                }

                val deniedLabels = deniedPermissions.joinToString("\n") { getPermissionLabel(it) }

                statusText.text = """
                    ⚠️ Some permissions denied:

                    $deniedLabels

                    These are required for FitGuard to work.
                    Please enable in Settings.
                """.trimIndent()

                addSettingsButton()
            }
        }
    }

    private fun getPermissionLabel(permission: String): String {
        return when {
            permission.contains("health.READ_HEART_RATE") -> "• Heart Rate"
            permission.contains("health.READ_OXYGEN_SATURATION") -> "• Blood Oxygen"
            permission.contains("health.READ_SKIN_TEMPERATURE") -> "• Skin Temperature"
            permission.contains("health.READ_HEALTH_DATA_IN_BACKGROUND") -> "• Background Health Data"
            permission == Manifest.permission.BODY_SENSORS -> "• Body Sensors"
            permission == Manifest.permission.BODY_SENSORS_BACKGROUND -> "• Background Body Sensors"
            permission == Manifest.permission.ACTIVITY_RECOGNITION -> "• Physical Activity"
            permission == Manifest.permission.POST_NOTIFICATIONS -> "• Notifications"
            else -> "• ${permission.substringAfterLast('.')}"
        }
    }

    private fun addSettingsButton() {
        buttonContainer.removeAllViews()

        val apiVersion = if (Build.VERSION.SDK_INT >= 36) "API 36+ (Wear OS 6)" else "API 30-35"

        val infoText = TextView(this).apply {
            text = """
                FitGuard needs access to:

                📊 Fitness and Wellness
                  • Heart Rate monitoring
                  • SpO2 (Blood Oxygen)

                🏃 Physical Activity
                  • Activity Recognition
                  • Step Counting

                🔄 Background Monitoring
                  • Continuous tracking

                Running: $apiVersion

                Tap Settings below to enable permissions.
            """.trimIndent()
            textSize = 10f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(16, 16, 16, 32)
        }
        buttonContainer.addView(infoText)

        val settingsButton = Button(this).apply {
            text = "⚙️ Open Settings"
            textSize = 14f
            setPadding(24, 16, 24, 16)
            setOnClickListener {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.fromParts("package", packageName, null)
                startActivity(intent)
            }
        }
        buttonContainer.addView(settingsButton)

        val retryButton = Button(this).apply {
            text = "🔄 Retry"
            textSize = 14f
            setPadding(24, 16, 24, 16)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 16
            }
            setOnClickListener {
                checkAndRequestPermissions()
            }
        }
        buttonContainer.addView(retryButton)
    }

    private fun onPermissionsGranted() {
        val apiInfo = if (Build.VERSION.SDK_INT >= 36) {
            "Using granular health permissions (API 36+)"
        } else {
            "Using legacy body sensors (API 30-35)"
        }

        statusText.text = "✓ All permissions granted!\n$apiInfo\nConnecting to Health Service..."
        initializeHealthTrackers()
    }

    private fun initializeHealthTrackers() {
        healthTrackerManager = HealthTrackerManager(
            context = this,
            defaultDataCallback = { data ->
                sendDataToPhone(data)
            }
        )

        healthTrackerManager.initialize(
            onSuccess = {
                runOnUiThread {
                    initializeSequenceManager()
                    statusText.text = "✓ Connected to Health Service"
                    createTrackerButtons()
                }
            },
            onError = { error ->
                runOnUiThread {
                    statusText.text = """
                        ❌ Connection failed: ${error.errorCode}

                        Make sure:
                        • Samsung Health is installed
                        • All permissions are granted
                        • Background access is "Always"
                    """.trimIndent()
                }
            }
        )
    }

    private fun initializeSequenceManager() {
        sensorSequenceManager = SensorSequenceManager(
            context = this,
            healthTrackerManager = healthTrackerManager
        )

        sensorSequenceManager.onPhaseChanged = { phase ->
            runOnUiThread { updateSequenceUI(phase) }
        }

        sensorSequenceManager.onProgress = { elapsed, total ->
            runOnUiThread {
                val remaining = total - elapsed
                val minutes = remaining / 60
                val seconds = remaining % 60
                val phaseLabel = formatPhase(sensorSequenceManager)
                sequenceStatusText?.text = "$phaseLabel ${minutes}m ${String.format("%02d", seconds)}s remaining"
            }
        }

        sensorSequenceManager.onComplete = {
            runOnUiThread {
                sequenceStatusText?.text = "Sequence complete! Data sent."
                enableIndividualButtons(true)
                sequenceButton?.apply {
                    text = "▶ Start Sequence"
                    setBackgroundColor(Color.parseColor("#1565C0"))
                }
            }
        }

        sensorSequenceManager.onSequenceLoopComplete = { seqCount ->
            runOnUiThread {
                sequenceStatusText?.text = "Continuous: ${sensorSequenceManager.activityType} | Seq #$seqCount"
            }
            sendMessageToPhone("/fitguard/activity/heartbeat", JSONObject().apply {
                put("session_id", sensorSequenceManager.sessionId)
                put("sequence_count", seqCount)
                put("elapsed_s", seqCount * 77) // ~75s per sequence + 2s gap
            })
        }

    }

    private fun createTrackerButtons() {
        buttonContainer.removeAllViews()
        individualButtons.clear()

        val availableTrackers = healthTrackerManager.getAvailableTrackers()

        Log.d(TAG, "Available trackers: ${availableTrackers.map { it.name }}")

        val headerText = TextView(this).apply {
            text = "Samsung Health Trackers\n(${availableTrackers.size} available)"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(8, 8, 8, 16)
        }
        buttonContainer.addView(headerText)

        addSectionHeader("Continuous Trackers")

        if (availableTrackers.contains(HealthTrackerType.PPG_CONTINUOUS)) {
            addTrackerButton("PPG", HealthTrackerType.PPG_CONTINUOUS, "3 LEDs @ 100Hz",
                start = { healthTrackerManager.startPPGContinuous() }
            )
        }

        if (availableTrackers.contains(HealthTrackerType.ACCELEROMETER_CONTINUOUS)) {
            addTrackerButton("Accel", HealthTrackerType.ACCELEROMETER_CONTINUOUS, "X/Y/Z axes",
                start = { healthTrackerManager.startAccelerometerContinuous() }
            )
        }

        if (availableTrackers.contains(HealthTrackerType.HEART_RATE_CONTINUOUS)) {
            addTrackerButton("HR", HealthTrackerType.HEART_RATE_CONTINUOUS, "BPM + IBI",
                start = { PassiveTrackerService.startTracker(this, "HeartRate"); true },
                stop = { PassiveTrackerService.stopTracker(this, "HeartRate") }
            )
        }

        addSectionHeader("On-Demand Trackers")

        if (availableTrackers.contains(HealthTrackerType.SPO2_ON_DEMAND)) {
            addTrackerButton("SpO2", HealthTrackerType.SPO2_ON_DEMAND, "Blood oxygen %",
                start = { PassiveTrackerService.startTracker(this, "SpO2"); true },
                stop = { PassiveTrackerService.stopTracker(this, "SpO2") }
            )
        }

        if (availableTrackers.contains(HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND)) {
            addTrackerButton("Skin Temp", HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND, "Object + Ambient",
                start = { PassiveTrackerService.startTracker(this, "SkinTemp"); true },
                stop = { PassiveTrackerService.stopTracker(this, "SkinTemp") }
            )
        }

        // Automated Sequence section
        addSectionHeader("Automated Sequence")

        sequenceStatusText = TextView(this).apply {
            text = "60s PPG+Accel"
            textSize = 9f
            setTextColor(Color.LTGRAY)
            setPadding(12, 2, 12, 6)
        }

        sequenceButton = Button(this).apply {
            text = "▶ Start Sequence"
            setBackgroundColor(Color.parseColor("#1565C0"))
            setTextColor(Color.WHITE)
            textSize = 11f
            setPadding(12, 12, 12, 12)
            setOnClickListener {
                if (sensorSequenceManager.isRunning) {
                    if (sensorSequenceManager.isContinuousMode) {
                        val seqCount = sensorSequenceManager.sequenceCount
                        val sid = sensorSequenceManager.sessionId
                        sensorSequenceManager.cancelSequence()
                        SessionForegroundService.stop(this@MainActivity)
                        sendMessageToPhone("/fitguard/activity/stopped", JSONObject().apply {
                            put("session_id", sid)
                            put("reason", "watch_stop")
                            put("sequence_count", seqCount)
                        })
                        sequenceStatusText?.text = "Session stopped locally"
                    } else {
                        sensorSequenceManager.cancelSequence()
                        sequenceStatusText?.text = "Sequence cancelled"
                    }
                    enableIndividualButtons(true)
                    setBackgroundColor(Color.parseColor("#1565C0"))
                    text = "▶ Start Sequence"
                    statusText.text = "Sequence cancelled"
                } else {
                    // Stop any individually-running trackers first
                    healthTrackerManager.stopAllTrackers()
                    activeTrackerButtons.values.forEach { btn ->
                        btn.setBackgroundColor(Color.DKGRAY)
                        btn.text = btn.text.toString().replace("⏹", "▶")
                    }
                    activeTrackerButtons.clear()

                    enableIndividualButtons(false)
                    setBackgroundColor(Color.RED)
                    text = "⏹ Stop Sequence"
                    sensorSequenceManager.startSequence()
                }
            }
        }

        buttonContainer.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 4, 0, 4)
            addView(sequenceButton)
            addView(sequenceStatusText)
        })

        // Stop All button
        val stopAllButton = Button(this).apply {
            text = "⏹ STOP ALL"
            setBackgroundColor(Color.RED)
            setTextColor(Color.WHITE)
            setPadding(12, 12, 12, 12)
            setOnClickListener {
                if (sensorSequenceManager.isRunning) {
                    if (sensorSequenceManager.isContinuousMode) {
                        val seqCount = sensorSequenceManager.sequenceCount
                        val sid = sensorSequenceManager.sessionId
                        sensorSequenceManager.cancelSequence()
                        SessionForegroundService.stop(this@MainActivity)
                        sendMessageToPhone("/fitguard/activity/stopped", JSONObject().apply {
                            put("session_id", sid)
                            put("reason", "watch_stop")
                            put("sequence_count", seqCount)
                        })
                    } else {
                        sensorSequenceManager.cancelSequence()
                    }
                    sequenceButton?.apply {
                        setBackgroundColor(Color.parseColor("#1565C0"))
                        text = "▶ Start Sequence"
                    }
                    sequenceStatusText?.text = "Sequence cancelled"
                }
                healthTrackerManager.stopAllTrackers()
                activeTrackerButtons.values.forEach {
                    it.setBackgroundColor(Color.DKGRAY)
                    it.text = it.text.toString().replace("⏹", "▶")
                }
                activeTrackerButtons.clear()
                enableIndividualButtons(true)
                statusText.text = "All trackers stopped"
            }
        }
        buttonContainer.addView(LinearLayout(this).apply {
            setPadding(0, 16, 0, 0)
            addView(stopAllButton)
        })
    }

    private fun addSectionHeader(title: String) {
        buttonContainer.addView(TextView(this).apply {
            text = "\n$title"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(8, 8, 8, 8)
        })
    }

    private fun addTrackerButton(
        name: String,
        type: HealthTrackerType,
        desc: String,
        start: () -> Boolean,
        stop: () -> Unit = { healthTrackerManager.stopTracker(type) }
    ) {
        val button = Button(this).apply {
            text = "▶ $name"
            setBackgroundColor(Color.DKGRAY)
            setTextColor(Color.WHITE)
            textSize = 11f
            setPadding(12, 12, 12, 12)
            setOnClickListener {
                if (sensorSequenceManager.isRunning) return@setOnClickListener

                if (activeTrackerButtons.containsKey(type)) {
                    stop()
                    activeTrackerButtons.remove(type)
                    setBackgroundColor(Color.DKGRAY)
                    text = "▶ $name"
                    statusText.text = "Stopped: $name"
                } else {
                    if (start()) {
                        activeTrackerButtons[type] = this
                        setBackgroundColor(Color.GREEN)
                        text = "⏹ $name"
                        statusText.text = "✓ $name active"
                    } else {
                        statusText.text = "❌ Failed to start: $name"
                    }
                }
            }
        }

        individualButtons.add(button)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 4, 0, 4)
            addView(button)
            addView(TextView(this@MainActivity).apply {
                text = desc
                textSize = 9f
                setTextColor(Color.LTGRAY)
                setPadding(12, 2, 12, 6)
            })
        }
        buttonContainer.addView(container)
    }

    private fun enableIndividualButtons(enabled: Boolean) {
        individualButtons.forEach { button ->
            button.isEnabled = enabled
            button.alpha = if (enabled) 1.0f else 0.4f
        }
    }

    private fun formatPhase(manager: SensorSequenceManager): String {
        return when {
            !manager.isRunning -> ""
            else -> {
                // Read the phase from the last callback
                sequenceStatusText?.text?.toString()?.substringBefore("...")?.plus("...") ?: ""
            }
        }
    }

    private fun updateSequenceUI(phase: SensorSequenceManager.SequencePhase) {
        val phaseText = when (phase) {
            SensorSequenceManager.SequencePhase.IDLE -> "Ready"
            SensorSequenceManager.SequencePhase.CONTINUOUS -> "PPG + Accel..."
            SensorSequenceManager.SequencePhase.SENDING -> "Sending data..."
            SensorSequenceManager.SequencePhase.COMPLETE -> "Complete!"
            SensorSequenceManager.SequencePhase.CANCELLED -> "Cancelled"
        }
        statusText.text = phaseText
        sequenceStatusText?.text = phaseText
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
            .addOnFailureListener { Log.e(TAG, "✗ Failed to send: ${it.message}") }
    }

    private fun registerActivityCommandReceiver() {
        activityCommandReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent ?: return
                when (intent.action) {
                    WearableMessageListenerService.ACTION_START_ACTIVITY -> {
                        val activityType = intent.getStringExtra("activity_type") ?: ""
                        val sessionId = intent.getStringExtra("session_id") ?: ""
                        Log.d(TAG, "Received start command: activity=$activityType session=$sessionId")

                        if (::sensorSequenceManager.isInitialized && !sensorSequenceManager.isRunning) {
                            // Start foreground service to keep process alive
                            SessionForegroundService.start(this@MainActivity, activityType)

                            runOnUiThread {
                                enableIndividualButtons(false)
                                sequenceButton?.apply {
                                    setBackgroundColor(Color.RED)
                                    text = "⏹ Stop Session"
                                }
                                sequenceStatusText?.text = "Continuous: $activityType"
                                statusText.text = "Session started from phone"
                            }
                            // Stop any running passive trackers before starting sequence session
                            PassiveTrackerService.stopTracker(this@MainActivity, "HeartRate")
                            PassiveTrackerService.stopTracker(this@MainActivity, "SpO2")
                            PassiveTrackerService.stopTracker(this@MainActivity, "SkinTemp")
                            Log.d(TAG, "Stopped all passive trackers before starting sequence session")
                            sensorSequenceManager.startContinuousSession(sessionId, activityType)
                            sendMessageToPhone("/fitguard/activity/ack", JSONObject().apply {
                                put("session_id", sessionId)
                                put("status", "started")
                            })
                        } else {
                            sendMessageToPhone("/fitguard/activity/ack", JSONObject().apply {
                                put("session_id", sessionId)
                                put("status", "busy")
                            })
                        }
                    }
                    WearableMessageListenerService.ACTION_STOP_ACTIVITY -> {
                        val sessionId = intent.getStringExtra("session_id") ?: ""
                        Log.d(TAG, "Received stop command: session=$sessionId")

                        if (::sensorSequenceManager.isInitialized && sensorSequenceManager.isRunning && sensorSequenceManager.isContinuousMode) {
                            val seqCount = sensorSequenceManager.sequenceCount
                            sensorSequenceManager.cancelSequence()
                            SessionForegroundService.stop(this@MainActivity)
                            runOnUiThread {
                                enableIndividualButtons(true)
                                sequenceButton?.apply {
                                    setBackgroundColor(Color.parseColor("#1565C0"))
                                    text = "▶ Start Sequence"
                                }
                                sequenceStatusText?.text = "Session stopped by phone"
                                statusText.text = "Session stopped"
                            }
                            sendMessageToPhone("/fitguard/activity/stopped", JSONObject().apply {
                                put("session_id", sessionId)
                                put("reason", "phone_stop")
                                put("sequence_count", seqCount)
                            })
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(WearableMessageListenerService.ACTION_START_ACTIVITY)
            addAction(WearableMessageListenerService.ACTION_STOP_ACTIVITY)
        }
        registerReceiver(activityCommandReceiver, filter, RECEIVER_NOT_EXPORTED)
    }

    private fun sendMessageToPhone(path: String, payload: JSONObject) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val nodes = Wearable.getNodeClient(this@MainActivity).connectedNodes.await()
                val data = payload.toString().toByteArray(Charsets.UTF_8)
                for (node in nodes) {
                    Wearable.getMessageClient(this@MainActivity).sendMessage(node.id, path, data).await()
                    Log.d(TAG, "Sent $path to ${node.displayName}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message $path: ${e.message}", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activityCommandReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        if (::sensorSequenceManager.isInitialized && sensorSequenceManager.isRunning) {
            if (sensorSequenceManager.isContinuousMode) {
                val seqCount = sensorSequenceManager.sequenceCount
                val sid = sensorSequenceManager.sessionId
                SessionForegroundService.stop(this)
                sendMessageToPhone("/fitguard/activity/stopped", JSONObject().apply {
                    put("session_id", sid)
                    put("reason", "watch_destroyed")
                    put("sequence_count", seqCount)
                })
            }
            sensorSequenceManager.cancelSequence()
        }
        healthTrackerManager.disconnect()
    }
}
