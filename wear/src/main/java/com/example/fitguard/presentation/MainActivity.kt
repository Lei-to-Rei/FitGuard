package com.example.fitguard.presentation

import android.Manifest
import android.app.Activity
import android.content.Intent
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
import com.samsung.android.service.health.tracking.data.HealthTrackerType

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var buttonContainer: LinearLayout
    private lateinit var healthTrackerManager: HealthTrackerManager
    private lateinit var batchDataManager: BatchDataManager
    private val activeTrackerButtons = mutableMapOf<HealthTrackerType, Button>()

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val TAG = "WatchHealthTrackers"

        // New granular health permissions for API 36+ (Wear OS 6)
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

        // Initialize batch data manager
        batchDataManager = BatchDataManager(this)

        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
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
                  • Skin Temperature
                
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
            onDataCallback = { data ->
                // Add to batch instead of sending immediately
                batchDataManager.addData(data)
            }
        )

        healthTrackerManager.initialize(
            onSuccess = {
                runOnUiThread {
                    val settings = batchDataManager.getSettings()
                    statusText.text = """
                        ✓ Connected to Health Service
                        📦 Batch Mode: ${settings.batchSizeKB}KB / ${settings.transferIntervalMinutes}min
                    """.trimIndent()
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

    private fun createTrackerButtons() {
        buttonContainer.removeAllViews()

        val availableTrackers = healthTrackerManager.getAvailableTrackers()

        Log.d(TAG, "Available trackers: ${availableTrackers.map { it.name }}")

        // Batch info header
        val batchInfoText = TextView(this).apply {
            text = batchDataManager.getBufferStats()
            textSize = 10f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(8, 8, 8, 8)
        }
        buttonContainer.addView(batchInfoText)

        // Batch control buttons
        val batchControlContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 16)
            gravity = Gravity.CENTER
        }

        val transferNowButton = Button(this).apply {
            text = "📤 Transfer Now"
            textSize = 10f
            setPadding(12, 8, 12, 8)
            setOnClickListener {
                batchDataManager.forceTransfer()
                statusText.text = "Transferring batch..."
            }
        }

        val clearBufferButton = Button(this).apply {
            text = "🗑️ Clear Buffer"
            textSize = 10f
            setPadding(12, 8, 12, 8)
            setBackgroundColor(Color.rgb(139, 0, 0))
            setOnClickListener {
                batchDataManager.clearBuffer()
                statusText.text = "Buffer cleared"
                batchInfoText.text = batchDataManager.getBufferStats()
            }
        }

        batchControlContainer.addView(transferNowButton)
        batchControlContainer.addView(clearBufferButton)
        buttonContainer.addView(batchControlContainer)

        // Divider
        buttonContainer.addView(TextView(this).apply {
            text = "━".repeat(20)
            textSize = 10f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 8)
        })

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
            addTrackerButton("PPG", HealthTrackerType.PPG_CONTINUOUS, "3 LEDs @ 100Hz") {
                healthTrackerManager.startPPGContinuous()
            }
        }

        if (availableTrackers.contains(HealthTrackerType.HEART_RATE_CONTINUOUS)) {
            addTrackerButton("Heart Rate", HealthTrackerType.HEART_RATE_CONTINUOUS, "BPM + IBI") {
                healthTrackerManager.startHeartRateContinuous()
            }
        }

        addSectionHeader("On-Demand Trackers")

        if (availableTrackers.contains(HealthTrackerType.SPO2_ON_DEMAND)) {
            addTrackerButton("SpO2", HealthTrackerType.SPO2_ON_DEMAND, "30 sec") {
                healthTrackerManager.startSpO2OnDemand()
            }
        }

        if (availableTrackers.contains(HealthTrackerType.ECG_ON_DEMAND)) {
            addTrackerButton("ECG", HealthTrackerType.ECG_ON_DEMAND, "30 sec, touch bezel") {
                healthTrackerManager.startECGOnDemand()
            }
        }

        if (availableTrackers.contains(HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND)) {
            addTrackerButton("Skin Temp", HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND, "Body temp") {
                healthTrackerManager.startSkinTemperatureOnDemand()
            }
        }

        if (availableTrackers.contains(HealthTrackerType.BIA)) {
            addTrackerButton("BIA", HealthTrackerType.BIA, "Body comp, 15 sec") {
                healthTrackerManager.startBIA()
            }
        }

        if (availableTrackers.contains(HealthTrackerType.SWEAT_LOSS)) {
            addTrackerButton("Sweat", HealthTrackerType.SWEAT_LOSS, "Hydration") {
                healthTrackerManager.startSweatLoss()
            }
        }

        val stopAllButton = Button(this).apply {
            text = "⏹ STOP ALL"
            setBackgroundColor(Color.RED)
            setTextColor(Color.WHITE)
            setPadding(12, 12, 12, 12)
            setOnClickListener {
                healthTrackerManager.stopAllTrackers()
                activeTrackerButtons.values.forEach {
                    it.setBackgroundColor(Color.DKGRAY)
                    it.text = it.text.toString().replace("⏹", "▶")
                }
                activeTrackerButtons.clear()
                statusText.text = "All trackers stopped"
            }
        }
        buttonContainer.addView(LinearLayout(this).apply {
            setPadding(0, 16, 0, 0)
            addView(stopAllButton)
        })

        // Update buffer stats periodically
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(object : Runnable {
            override fun run() {
                batchInfoText.text = batchDataManager.getBufferStats()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this, 5000)
            }
        }, 5000)
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
        start: () -> Boolean
    ) {
        val button = Button(this).apply {
            text = "▶ $name"
            setBackgroundColor(Color.DKGRAY)
            setTextColor(Color.WHITE)
            textSize = 11f
            setPadding(12, 12, 12, 12)
            setOnClickListener {
                if (activeTrackerButtons.containsKey(type)) {
                    healthTrackerManager.stopTracker(type)
                    activeTrackerButtons.remove(type)
                    setBackgroundColor(Color.DKGRAY)
                    text = "▶ $name"
                    statusText.text = "Stopped: $name"
                } else {
                    if (start()) {
                        activeTrackerButtons[type] = this
                        setBackgroundColor(Color.GREEN)
                        text = "⏹ $name"
                        statusText.text = "✓ $name active (batch mode)"
                    } else {
                        statusText.text = "❌ Failed to start: $name"
                    }
                }
            }
        }

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

    override fun onDestroy() {
        super.onDestroy()
        healthTrackerManager.disconnect()
        batchDataManager.cleanup()
    }
}