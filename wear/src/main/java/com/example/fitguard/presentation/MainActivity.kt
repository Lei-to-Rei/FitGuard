package com.example.fitguard.presentation

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.fitguard.R
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

class MainActivity : Activity() {

    // ===== Screen views =====
    private lateinit var screenLoading: LinearLayout
    private lateinit var screenHome: LinearLayout
    private lateinit var screenActivityList: ScrollView
    private lateinit var screenSession: RelativeLayout
    private lateinit var screenFatigue: LinearLayout

    // Loading screen
    private lateinit var statusText: TextView
    private lateinit var buttonContainer: LinearLayout

    // Activity list
    private lateinit var activityListContainer: LinearLayout

    // Session screen
    private lateinit var tvActivityChip: TextView
    private lateinit var tvTimer: TextView
    private lateinit var btnStartStop: TextView

    // Fatigue screen
    private lateinit var tvFatigueLevel: TextView
    private lateinit var tvFatigueDetail: TextView

    // ===== Sensor managers =====
    private lateinit var healthTrackerManager: HealthTrackerManager
    private lateinit var sensorSequenceManager: SensorSequenceManager
    private var activityCommandReceiver: BroadcastReceiver? = null

    // ===== Session state =====
    private var selectedActivity = "Running"
    private var elapsedSeconds = 0
    private var timerJob: Job? = null
    private var isSessionRunning = false
    private var lastFatigueLabel = "No data"
    private var lastFatigueDetail = "Start a session to measure fatigue"
    private var lastFatigueColor = "#4CAF50"

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val TAG = "WatchHealthTrackers"
        const val ACTION_FATIGUE_UPDATE = "com.example.fitguard.wear.FATIGUE_UPDATE"

        private val HEALTH_PERMISSIONS_API36 = arrayOf(
            "android.permission.health.READ_HEART_RATE",
            "android.permission.health.READ_OXYGEN_SATURATION",
            "android.permission.health.READ_SKIN_TEMPERATURE",
            "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
        )

        private val LEGACY_PERMISSIONS = arrayOf(
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.BODY_SENSORS_BACKGROUND
        )

        private val ACTIVITY_PERMISSIONS = arrayOf(
            Manifest.permission.ACTIVITY_RECOGNITION
        )

        private val OTHER_PERMISSIONS = buildList {
            add(Manifest.permission.WAKE_LOCK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bind views
        screenLoading      = findViewById(R.id.screenLoading)
        screenHome         = findViewById(R.id.screenHome)
        screenActivityList = findViewById(R.id.screenActivityList)
        screenSession      = findViewById(R.id.screenSession)
        screenFatigue      = findViewById(R.id.screenFatigue)
        statusText         = findViewById(R.id.statusText)
        buttonContainer    = findViewById(R.id.buttonContainer)
        activityListContainer = findViewById(R.id.activityListContainer)
        tvActivityChip     = findViewById(R.id.tvActivityChip)
        tvTimer            = findViewById(R.id.tvTimer)
        btnStartStop       = findViewById(R.id.btnStartStop)
        tvFatigueLevel     = findViewById(R.id.tvFatigueLevel)
        tvFatigueDetail    = findViewById(R.id.tvFatigueDetail)

        styleHomeButtons()
        styleSessionButtons()

        // Navigation listeners
        findViewById<Button>(R.id.btnStartActivity).setOnClickListener { showActivityListScreen() }
        findViewById<Button>(R.id.btnFatigueLevel).setOnClickListener { showFatigueScreen() }
        findViewById<TextView>(R.id.btnBackToHome).setOnClickListener { showHomeScreen() }
        findViewById<TextView>(R.id.btnBackFromFatigue).setOnClickListener { showHomeScreen() }
        btnStartStop.setOnClickListener {
            if (isSessionRunning) stopSession() else startSession()
        }

        checkAndRequestPermissions()
        registerActivityCommandReceiver()
    }

    override fun onDestroy() {
        super.onDestroy()
        timerJob?.cancel()
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
        if (::healthTrackerManager.isInitialized) {
            healthTrackerManager.disconnect()
        }
    }

    // ─── Permissions ─────────────────────────────────────────────────────────

    private fun checkAndRequestPermissions() {
        val healthPermissions = if (Build.VERSION.SDK_INT >= 36) HEALTH_PERMISSIONS_API36 else LEGACY_PERMISSIONS
        val allPermissions = healthPermissions + ACTIVITY_PERMISSIONS + OTHER_PERMISSIONS
        val missing = allPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            onPermissionsGranted()
        } else {
            val labels = if (Build.VERSION.SDK_INT >= 36) {
                "Requesting permissions:\n\n✓ Fitness & Wellness\n  • Heart Rate\n  • Blood Oxygen (SpO2)\n  • Skin Temperature\n  • Background Health Data\n\n✓ Physical Activity\n  • Activity Recognition"
            } else {
                "Requesting permissions:\n\n✓ Body Sensors\n✓ Physical Activity\n✓ Background Monitoring"
            }
            statusText.text = labels
            ActivityCompat.requestPermissions(this, allPermissions, PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST_CODE) return
        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            onPermissionsGranted()
        } else {
            val denied = permissions.filterIndexed { i, _ -> grantResults[i] != PackageManager.PERMISSION_GRANTED }
            val deniedLabels = denied.joinToString("\n") { getPermissionLabel(it) }
            statusText.text = "⚠️ Some permissions denied:\n\n$deniedLabels\n\nThese are required for FitGuard to work.\nPlease enable in Settings."
            addSettingsButton()
        }
    }

    private fun getPermissionLabel(permission: String) = when {
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

    private fun addSettingsButton() {
        buttonContainer.removeAllViews()
        val apiVersion = if (Build.VERSION.SDK_INT >= 36) "API 36+ (Wear OS 6)" else "API 30-35"
        buttonContainer.addView(TextView(this).apply {
            text = "FitGuard needs access to Fitness and Wellness, Physical Activity, and Background Monitoring.\n\nRunning: $apiVersion\n\nTap Settings below to enable permissions."
            textSize = 10f
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
            setPadding(16, 16, 16, 32)
        })
        buttonContainer.addView(Button(this).apply {
            text = "⚙️ Open Settings"
            textSize = 13f
            setPadding(24, 14, 24, 14)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                })
            }
        })
        buttonContainer.addView(Button(this).apply {
            text = "🔄 Retry"
            textSize = 13f
            setPadding(24, 14, 24, 14)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12 }
            setOnClickListener { checkAndRequestPermissions() }
        })
    }

    // ─── Health tracker init ──────────────────────────────────────────────────

    private fun onPermissionsGranted() {
        val apiInfo = if (Build.VERSION.SDK_INT >= 36) "API 36+ (Wear OS 6)" else "API 30-35"
        statusText.text = "✓ All permissions granted!\n$apiInfo\nConnecting to Health Service..."
        initializeHealthTrackers()
    }

    private fun initializeHealthTrackers() {
        healthTrackerManager = HealthTrackerManager(
            context = this,
            defaultDataCallback = { data -> sendDataToPhone(data) }
        )
        healthTrackerManager.initialize(
            onSuccess = {
                runOnUiThread {
                    initializeSequenceManager()
                    buildActivityList()
                    showHomeScreen()
                }
            },
            onError = { error ->
                runOnUiThread {
                    statusText.text = "❌ Connection failed: ${error.errorCode}\n\nMake sure:\n• Samsung Health is installed\n• All permissions are granted\n• Background access is \"Always\""
                }
            }
        )
    }

    private fun initializeSequenceManager() {
        sensorSequenceManager = SensorSequenceManager(
            context = this,
            healthTrackerManager = healthTrackerManager
        )
        sensorSequenceManager.onPhaseChanged = { /* handled by timer UI */ }
        sensorSequenceManager.onProgress = { _, _ -> /* timer coroutine drives display */ }
        sensorSequenceManager.onComplete = { /* data sent to phone automatically */ }
        sensorSequenceManager.onSequenceLoopComplete = { seqCount ->
            sendMessageToPhone("/fitguard/activity/heartbeat", JSONObject().apply {
                put("session_id", sensorSequenceManager.sessionId)
                put("sequence_count", seqCount)
                put("elapsed_s", seqCount * 77)
            })
        }
    }

    // ─── Activity list ────────────────────────────────────────────────────────

    private fun buildActivityList() {
        activityListContainer.removeAllViews()
        val activities = listOf(
            "Running", "Walking", "Cycling",
            "Treadmill", "Stationary Bike", "Trail Running"
        )
        for (label in activities) {
            val btn = Button(this).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 14f
                stateListAnimator = null
                setPadding(32, 20, 32, 20)
                background = pillDrawable("#2C2C2C")
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 10 }
                setOnClickListener {
                    selectedActivity = label
                    showSessionScreen(label)
                }
            }
            activityListContainer.addView(btn)
        }
    }

    // ─── Navigation ──────────────────────────────────────────────────────────

    private fun showHomeScreen() {
        screenLoading.visibility      = View.GONE
        screenHome.visibility          = View.VISIBLE
        screenActivityList.visibility  = View.GONE
        screenSession.visibility       = View.GONE
        screenFatigue.visibility       = View.GONE
    }

    private fun showActivityListScreen() {
        screenHome.visibility          = View.GONE
        screenActivityList.visibility  = View.VISIBLE
        screenSession.visibility       = View.GONE
        screenFatigue.visibility       = View.GONE
    }

    private fun showSessionScreen(activityType: String) {
        screenActivityList.visibility  = View.GONE
        screenSession.visibility       = View.VISIBLE
        tvActivityChip.text            = activityType
        tvTimer.text                   = "00:00"
        elapsedSeconds                 = 0
        isSessionRunning               = false
        setStartStopButton(running = false)
    }

    private fun showFatigueScreen() {
        screenHome.visibility      = View.GONE
        screenFatigue.visibility   = View.VISIBLE
        tvFatigueLevel.text        = lastFatigueLabel
        tvFatigueLevel.setTextColor(Color.parseColor(lastFatigueColor))
        tvFatigueDetail.text       = lastFatigueDetail
    }

    // ─── Session ─────────────────────────────────────────────────────────────

    private fun startSession(phoneSessionId: String? = null) {
        if (!::sensorSequenceManager.isInitialized) return
        val sessionId = phoneSessionId ?: "watch_${System.currentTimeMillis()}"
        PassiveTrackerService.stopTracker(this, "HeartRate")
        PassiveTrackerService.stopTracker(this, "SpO2")
        PassiveTrackerService.stopTracker(this, "SkinTemp")
        SessionForegroundService.start(this, selectedActivity)
        sensorSequenceManager.startContinuousSession(sessionId, selectedActivity)
        isSessionRunning = true
        setStartStopButton(running = true)
        startTimer()
        sendMessageToPhone("/fitguard/activity/ack", JSONObject().apply {
            put("session_id", sessionId)
            put("status", "started")
            put("activity_type", selectedActivity)
        })
        Log.d(TAG, "Session started: $selectedActivity id=$sessionId")
    }

    private fun stopSession() {
        timerJob?.cancel()
        if (::sensorSequenceManager.isInitialized && sensorSequenceManager.isRunning) {
            val seqCount = sensorSequenceManager.sequenceCount
            val sid = sensorSequenceManager.sessionId
            sensorSequenceManager.cancelSequence()
            SessionForegroundService.stop(this)
            sendMessageToPhone("/fitguard/activity/stopped", JSONObject().apply {
                put("session_id", sid)
                put("reason", "watch_stop")
                put("sequence_count", seqCount)
            })
        }
        isSessionRunning = false
        showHomeScreen()
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                delay(1000)
                elapsedSeconds++
                val m = elapsedSeconds / 60
                val s = elapsedSeconds % 60
                tvTimer.text = String.format("%02d:%02d", m, s)
            }
        }
    }

    // ─── Styling helpers ──────────────────────────────────────────────────────

    private fun styleHomeButtons() {
        findViewById<Button>(R.id.btnStartActivity).apply {
            background = pillDrawable("#4CAF50")
            setTextColor(Color.WHITE)
        }
        findViewById<Button>(R.id.btnFatigueLevel).apply {
            background = pillDrawable("#2C2C2C")
            setTextColor(Color.WHITE)
        }
    }

    private fun styleSessionButtons() {
        tvActivityChip.background = pillDrawable("#4CAF50")
        setStartStopButton(running = false)
    }

    private fun setStartStopButton(running: Boolean) {
        btnStartStop.text = if (running) "⏹" else "▶"
        btnStartStop.setTextColor(Color.BLACK)
        btnStartStop.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(if (running) "#E53935" else "#4CAF50"))
        }
    }

    private fun pillDrawable(hex: String) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 64f
        setColor(Color.parseColor(hex))
    }

    // ─── Broadcast receiver ───────────────────────────────────────────────────

    private fun registerActivityCommandReceiver() {
        activityCommandReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent ?: return
                when (intent.action) {
                    WearableMessageListenerService.ACTION_START_ACTIVITY -> {
                        val activityType = intent.getStringExtra("activity_type") ?: ""
                        val sessionId = intent.getStringExtra("session_id") ?: ""
                        Log.d(TAG, "Phone start: activity=$activityType session=$sessionId")
                        if (::sensorSequenceManager.isInitialized && !sensorSequenceManager.isRunning) {
                            runOnUiThread {
                                selectedActivity = activityType
                                showSessionScreen(activityType)
                                startSession(sessionId)
                            }
                        } else {
                            sendMessageToPhone("/fitguard/activity/ack", JSONObject().apply {
                                put("session_id", sessionId)
                                put("status", "busy")
                            })
                        }
                    }
                    WearableMessageListenerService.ACTION_STOP_ACTIVITY -> {
                        Log.d(TAG, "Phone stop command")
                        runOnUiThread { stopSession() }
                    }
                    ACTION_FATIGUE_UPDATE -> {
                        val label = intent.getStringExtra("level") ?: "Unknown"
                        val percent = intent.getIntExtra("percentDisplay", 0)
                        lastFatigueLabel = label
                        lastFatigueDetail = "Fatigue: $percent%"
                        lastFatigueColor = when (label.lowercase()) {
                            "low"      -> "#4CAF50"
                            "moderate" -> "#FFC107"
                            "high"     -> "#F44336"
                            else       -> "#FFFFFF"
                        }
                        runOnUiThread {
                            if (screenFatigue.visibility == View.VISIBLE) {
                                tvFatigueLevel.text = lastFatigueLabel
                                tvFatigueLevel.setTextColor(Color.parseColor(lastFatigueColor))
                                tvFatigueDetail.text = lastFatigueDetail
                            }
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(WearableMessageListenerService.ACTION_START_ACTIVITY)
            addAction(WearableMessageListenerService.ACTION_STOP_ACTIVITY)
            addAction(ACTION_FATIGUE_UPDATE)
        }
        registerReceiver(activityCommandReceiver, filter, RECEIVER_NOT_EXPORTED)
    }

    // ─── Data transmission ────────────────────────────────────────────────────

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
        Wearable.getDataClient(this).putDataItem(request.asPutDataRequest().setUrgent())
            .addOnSuccessListener { Log.d(TAG, "✓ $type sent to phone") }
            .addOnFailureListener { Log.e(TAG, "✗ Failed to send $type: ${it.message}") }
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
                Log.e(TAG, "Failed to send $path: ${e.message}", e)
            }
        }
    }
}
