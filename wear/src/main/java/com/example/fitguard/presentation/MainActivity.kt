package com.example.fitguard.presentation

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
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

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var buttonContainer: LinearLayout
    private lateinit var healthTrackerManager: HealthTrackerManager
    private val activeTrackerButtons = mutableMapOf<HealthTrackerType, Button>()

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val TAG = "WatchHealthTrackers"
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
            text = "Health Trackers\nInitializing..."
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

        if (!hasRequiredPermissions()) {
            requestPermissions()
        } else {
            initializeHealthTrackers()
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val bodySensors = ContextCompat.checkSelfPermission(
            this, Manifest.permission.BODY_SENSORS
        ) == PackageManager.PERMISSION_GRANTED

        val activityRecognition = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED

        return bodySensors && activityRecognition
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.BODY_SENSORS,
                Manifest.permission.ACTIVITY_RECOGNITION,
                Manifest.permission.BODY_SENSORS_BACKGROUND
            ),
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
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                initializeHealthTrackers()
            } else {
                statusText.text = "Permissions denied.\nGo to Settings to enable."
                addSettingsButton()
            }
        }
    }

    private fun addSettingsButton() {
        val settingsButton = Button(this).apply {
            text = "Open Settings"
            setOnClickListener {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.fromParts("package", packageName, null)
                startActivity(intent)
            }
        }
        buttonContainer.addView(settingsButton)
    }

    private fun initializeHealthTrackers() {
        healthTrackerManager = HealthTrackerManager(
            context = this,
            onDataCallback = { data ->
                sendDataToPhone(data)
            }
        )

        healthTrackerManager.initialize(
            onSuccess = {
                runOnUiThread {
                    statusText.text = "✓ Connected to Health Service"
                    createTrackerButtons()
                }
            },
            onError = { error ->
                runOnUiThread {
                    statusText.text = "Connection failed: ${error.errorCode}"
                }
            }
        )
    }

    private fun createTrackerButtons() {
        buttonContainer.removeAllViews()

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
                activeTrackerButtons.values.forEach { it.setBackgroundColor(Color.DKGRAY); it.text = it.text.toString().replace("⏹", "▶") }
                activeTrackerButtons.clear()
                statusText.text = "All stopped"
            }
        }
        buttonContainer.addView(LinearLayout(this).apply { setPadding(0, 16, 0, 0); addView(stopAllButton) })
    }

    private fun addSectionHeader(title: String) {
        buttonContainer.addView(TextView(this).apply {
            text = "\n$title"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(8, 8, 8, 8)
        })
    }

    private fun addTrackerButton(name: String, type: HealthTrackerType, desc: String, start: () -> Boolean) {
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
                        statusText.text = "✓ $name"
                    } else {
                        statusText.text = "❌ Failed: $name"
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
                    dataMap.putInt("status", data.status)
                    dataMap.putLong("timestamp", data.timestamp)
                }
                is HealthTrackerManager.TrackerData.ECGData -> {
                    dataMap.putString("type", "ECG")
                    dataMap.putInt("ppg_green", data.ppgGreen)
                    dataMap.putInt("sequence", data.sequence)
                    dataMap.putFloat("ecg_mv", data.ecgMv)
                    dataMap.putInt("lead_off", data.leadOff)
                    dataMap.putFloat("max_threshold_mv", data.maxThresholdMv)
                    dataMap.putFloat("min_threshold_mv", data.minThresholdMv)
                    dataMap.putLong("timestamp", data.timestamp)
                }
                is HealthTrackerManager.TrackerData.SkinTemperatureData -> {
                    dataMap.putString("type", "SkinTemp")
                    dataMap.putInt("status", data.status)
                    data.objectTemperature?.let { dataMap.putFloat("object_temp", it) }
                    data.ambientTemperature?.let { dataMap.putFloat("ambient_temp", it) }
                    dataMap.putLong("timestamp", data.timestamp)
                }
                is HealthTrackerManager.TrackerData.BIAData -> {
                    dataMap.putString("type", "BIA")
                    dataMap.putFloat("bmr", data.basalMetabolicRate)
                    dataMap.putFloat("body_fat_mass", data.bodyFatMass)
                    dataMap.putFloat("body_fat_ratio", data.bodyFatRatio)
                    dataMap.putFloat("fat_free_mass", data.fatFreeMass)
                    dataMap.putFloat("muscle_mass", data.skeletalMuscleMass)
                    dataMap.putLong("timestamp", data.timestamp)
                }
                is HealthTrackerManager.TrackerData.SweatLossData -> {
                    dataMap.putString("type", "Sweat")
                    dataMap.putFloat("sweat_loss", data.sweatLoss)
                    dataMap.putLong("timestamp", data.timestamp)
                }
            }
            dataMap.putLong("sent_at", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()

        Wearable.getDataClient(this).putDataItem(request)
            .addOnSuccessListener { Log.d(TAG, "✓ Sent") }
            .addOnFailureListener { Log.e(TAG, "✗ Failed: ${it.message}") }
    }

    override fun onDestroy() {
        super.onDestroy()
        healthTrackerManager.disconnect()
    }
}