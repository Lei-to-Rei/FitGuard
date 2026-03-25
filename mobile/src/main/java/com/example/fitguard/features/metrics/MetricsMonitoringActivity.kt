package com.example.fitguard.features.metrics

import android.content.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.fitguard.features.activitytracking.ActivityTrackingViewModel
import com.example.fitguard.data.processing.CsvWriter
import com.example.fitguard.data.processing.SequenceProcessor
import com.example.fitguard.databinding.ActivityMetricsMonitoringBinding
import com.example.fitguard.services.HealthMonitorService
import com.google.android.gms.wearable.Wearable
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MetricsMonitoringActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMetricsMonitoringBinding
    private val receiver = HealthDataReceiver()
    private val hrvReceiver = HrvResultReceiver()

    companion object {
        private const val TAG = "MetricsMonitoring"

        // Manual measurement durations (only used for manual "Measure" button)
        private const val HR_MEASUREMENT_DURATION_MS = 15_000L
        private const val SPO2_MEASUREMENT_TIMEOUT_MS = 90_000L
        private const val SKIN_TEMP_MEASUREMENT_TIMEOUT_MS = 30_000L
        private const val MAX_HR_RETRIES = 2

        private const val PREFS_NAME = "health_tracker_prefs"
    }

    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())
    private val handler = Handler(Looper.getMainLooper())
    private var hrStopRunnable: Runnable? = null

    // Manual measurement tracking (only for "Measure" button presses)
    private var isWatchConnected = false
    private var isManualHrMeasuring = false
    private var isManualSpo2Measuring = false
    private var isManualSkinTempMeasuring = false
    private var hrReceivedValid = false
    private var hrRetryCount = 0

    // HR stats tracking
    private val hrValues = mutableListOf<Float>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMetricsMonitoringBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        registerReceiver(receiver, IntentFilter("com.example.fitguard.HEALTH_DATA"), RECEIVER_NOT_EXPORTED)
        registerReceiver(hrvReceiver, IntentFilter(SequenceProcessor.ACTION_SEQUENCE_PROCESSED), RECEIVER_NOT_EXPORTED)

        // Restore switch states before setting up listeners
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        binding.switchHeartRate.isChecked = prefs.getBoolean("switch_hr", false)
        binding.switchSpO2.isChecked = prefs.getBoolean("switch_spo2", false)
        binding.switchSkinTemp.isChecked = prefs.getBoolean("switch_skin_temp", false)

        setupSensorToggles()
        setupMeasureButtons()
        loadHistoricalData()
    }

    private fun setupSensorToggles() {
        binding.switchHeartRate.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean("switch_hr", isChecked).apply()
            updateServiceState()
            updateMeasureButtons()
        }

        binding.switchSkinTemp.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean("switch_skin_temp", isChecked).apply()
            updateServiceState()
            updateMeasureButtons()
        }

        binding.switchSpO2.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean("switch_spo2", isChecked).apply()
            updateServiceState()
            updateMeasureButtons()
        }
    }

    private fun updateServiceState() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val hrEnabled = prefs.getBoolean("switch_hr", false)
        val spo2Enabled = prefs.getBoolean("switch_spo2", false)
        val skinTempEnabled = prefs.getBoolean("switch_skin_temp", false)

        val hrMode = prefs.getString("hr_mode", "Manual") ?: "Manual"
        val spo2Mode = prefs.getString("spo2_mode", "Manual") ?: "Manual"
        val skinTempMode = prefs.getString("skin_temp_mode", "Manual") ?: "Manual"

        // Service needed if any enabled tracker has a non-Manual mode
        val needsService = (hrEnabled && hrMode != "Manual")
                || (spo2Enabled && spo2Mode != "Manual")
                || (skinTempEnabled && skinTempMode != "Manual")

        if (needsService) {
            HealthMonitorService.start(this)
        } else {
            HealthMonitorService.stop(this)
        }
    }

    override fun onResume() {
        super.onResume()

        // Restore switch states (may have changed in Settings)
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        binding.switchHeartRate.isChecked = prefs.getBoolean("switch_hr", false)
        binding.switchSpO2.isChecked = prefs.getBoolean("switch_spo2", false)
        binding.switchSkinTemp.isChecked = prefs.getBoolean("switch_skin_temp", false)

        updateMeasureButtons()
        checkWatchConnection()

        // Ensure service state matches current prefs
        updateServiceState()
    }

    override fun onPause() {
        super.onPause()
        // Only stop manual measurements — service handles scheduled ones
        stopManualMeasurement("HeartRate")
        stopManualMeasurement("SpO2")
        stopManualMeasurement("SkinTemp")
    }

    private fun setupMeasureButtons() {
        binding.btnMeasureHeartRate.setOnClickListener {
            startManualMeasurement("HeartRate")
        }
        binding.btnMeasureSpO2.setOnClickListener {
            startManualMeasurement("SpO2")
        }
        binding.btnMeasureSkinTemp.setOnClickListener {
            startManualMeasurement("SkinTemp")
        }
    }

    private fun updateMeasureButtons() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val hrMode = prefs.getString("hr_mode", "Manual")
        val spo2Mode = prefs.getString("spo2_mode", "Manual")
        val skinTempMode = prefs.getString("skin_temp_mode", "Manual")

        val showHr = hrMode == "Manual" && binding.switchHeartRate.isChecked
        val showSpO2 = spo2Mode == "Manual" && binding.switchSpO2.isChecked
        val showSkinTemp = skinTempMode == "Manual" && binding.switchSkinTemp.isChecked

        binding.btnMeasureHeartRate.visibility = if (showHr) View.VISIBLE else View.GONE
        binding.btnMeasureSpO2.visibility = if (showSpO2) View.VISIBLE else View.GONE
        binding.btnMeasureSkinTemp.visibility = if (showSkinTemp) View.VISIBLE else View.GONE
    }

    // ===== Watch Connection Check =====

    private fun checkWatchConnection() {
        coroutineScope.launch(Dispatchers.IO) {
            val connected = try {
                Wearable.getNodeClient(this@MetricsMonitoringActivity)
                    .connectedNodes.await().isNotEmpty()
            } catch (_: Exception) { false }

            withContext(Dispatchers.Main) {
                isWatchConnected = connected
                binding.btnMeasureHeartRate.isEnabled = connected
                binding.btnMeasureSpO2.isEnabled = connected
                binding.btnMeasureSkinTemp.isEnabled = connected

                if (!connected) {
                    binding.btnMeasureHeartRate.text = "No Watch Connected"
                    binding.btnMeasureSpO2.text = "No Watch Connected"
                    binding.btnMeasureSkinTemp.text = "No Watch Connected"
                } else if (ActivityTrackingViewModel.activeSessionId != null) {
                    binding.btnMeasureHeartRate.text = "Workout Active"
                    binding.btnMeasureSpO2.text = "Workout Active"
                    binding.btnMeasureSkinTemp.text = "Workout Active"
                    binding.btnMeasureHeartRate.isEnabled = false
                    binding.btnMeasureSpO2.isEnabled = false
                    binding.btnMeasureSkinTemp.isEnabled = false
                } else {
                    binding.btnMeasureHeartRate.text = "Measure"
                    binding.btnMeasureSpO2.text = "Measure"
                    binding.btnMeasureSkinTemp.text = "Measure"
                }
            }
        }
    }

    // ===== Manual Measurement (Measure button only) =====

    private suspend fun sendTrackerCommand(command: String, trackerType: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val nodes = Wearable.getNodeClient(this@MetricsMonitoringActivity)
                    .connectedNodes.await()
                if (nodes.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MetricsMonitoringActivity,
                            "No watch connected", Toast.LENGTH_SHORT).show()
                    }
                    return@withContext false
                }
                val payload = JSONObject().apply {
                    put("tracker_type", trackerType)
                }.toString().toByteArray(Charsets.UTF_8)

                for (node in nodes) {
                    Wearable.getMessageClient(this@MetricsMonitoringActivity)
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

    private fun startManualMeasurement(trackerType: String) {
        if (!isWatchConnected) {
            Toast.makeText(this, "No watch connected", Toast.LENGTH_SHORT).show()
            return
        }
        if (ActivityTrackingViewModel.activeSessionId != null) {
            Toast.makeText(this, "Workout active — passive measurements paused", Toast.LENGTH_SHORT).show()
            return
        }
        coroutineScope.launch {
            val sent = sendTrackerCommand("start", trackerType)
            if (!sent) return@launch

            when (trackerType) {
                "HeartRate" -> {
                    isManualHrMeasuring = true
                    hrReceivedValid = false
                    binding.btnMeasureHeartRate.text = "Measuring..."
                    binding.btnMeasureHeartRate.isEnabled = false
                    hrStopRunnable = Runnable {
                        if (!hrReceivedValid && hrRetryCount < MAX_HR_RETRIES) {
                            hrRetryCount++
                            Log.d(TAG, "HR retry $hrRetryCount — no valid data yet")
                            handler.postDelayed(hrStopRunnable!!, HR_MEASUREMENT_DURATION_MS)
                        } else {
                            stopManualMeasurement("HeartRate")
                            if (!hrReceivedValid) {
                                Toast.makeText(this@MetricsMonitoringActivity,
                                    "HR measurement failed — try again", Toast.LENGTH_SHORT).show()
                            }
                            hrRetryCount = 0
                        }
                    }
                    handler.postDelayed(hrStopRunnable!!, HR_MEASUREMENT_DURATION_MS)
                }
                "SpO2" -> {
                    isManualSpo2Measuring = true
                    binding.btnMeasureSpO2.text = "Measuring..."
                    binding.btnMeasureSpO2.isEnabled = false
                    handler.postDelayed({ stopManualMeasurement("SpO2") }, SPO2_MEASUREMENT_TIMEOUT_MS)
                }
                "SkinTemp" -> {
                    isManualSkinTempMeasuring = true
                    binding.btnMeasureSkinTemp.text = "Measuring..."
                    binding.btnMeasureSkinTemp.isEnabled = false
                    handler.postDelayed({ stopManualMeasurement("SkinTemp") }, SKIN_TEMP_MEASUREMENT_TIMEOUT_MS)
                }
            }
        }
    }

    private fun stopManualMeasurement(trackerType: String) {
        when (trackerType) {
            "HeartRate" -> {
                if (!isManualHrMeasuring) return
                isManualHrMeasuring = false
                coroutineScope.launch { sendTrackerCommand("stop", trackerType) }
                hrStopRunnable?.let { handler.removeCallbacks(it) }
                hrStopRunnable = null
                binding.btnMeasureHeartRate.text = "Measure"
                binding.btnMeasureHeartRate.isEnabled = true
            }
            "SpO2" -> {
                if (!isManualSpo2Measuring) return
                isManualSpo2Measuring = false
                coroutineScope.launch { sendTrackerCommand("stop", trackerType) }
                binding.btnMeasureSpO2.text = "Measure"
                binding.btnMeasureSpO2.isEnabled = true
            }
            "SkinTemp" -> {
                if (!isManualSkinTempMeasuring) return
                isManualSkinTempMeasuring = false
                coroutineScope.launch { sendTrackerCommand("stop", trackerType) }
                binding.btnMeasureSkinTemp.text = "Measure"
                binding.btnMeasureSkinTemp.isEnabled = true
            }
        }
    }

    // ===== HR Stats =====

    private fun updateHrStats() {
        if (hrValues.isEmpty()) return
        binding.tvHrAvg.text = String.format("%.0f", hrValues.average())
        binding.tvHrMin.text = String.format("%.0f", hrValues.min())
        binding.tvHrMax.text = String.format("%.0f", hrValues.max())
    }

    // ===== Historical Data Loading =====

    private fun loadHistoricalData() {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val userId = FirebaseAuth.getInstance().currentUser?.uid ?: ""
                val dateFolder = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                val baseDir = CsvWriter.getOutputDir(userId, "")
                val dir = File(baseDir, dateFolder)

                // --- Heart Rate ---
                val hrFile = File(dir, "HeartRate.jsonl")
                if (hrFile.exists()) {
                    val readings = hrFile.readLines()
                        .filter { it.isNotBlank() }
                        .mapNotNull { line ->
                            try {
                                JSONObject(line).optInt("heart_rate", 0).toFloat()
                            } catch (_: Exception) { null }
                        }
                        .filter { it > 0f }

                    if (readings.isNotEmpty()) {
                        val chartPoints = readings.takeLast(12)
                        val lastHr = readings.last()
                        withContext(Dispatchers.Main) {
                            binding.tvHeartRateValue.text = "${lastHr.toInt()} bpm"
                            binding.chartHeartRate.setData(chartPoints)
                            hrValues.addAll(readings)
                            updateHrStats()
                        }
                    }
                }

                // --- SpO2 ---
                val spo2File = File(dir, "SpO2.jsonl")
                if (spo2File.exists()) {
                    val readings = spo2File.readLines()
                        .filter { it.isNotBlank() }
                        .mapNotNull { line ->
                            try {
                                JSONObject(line).optInt("spo2", 0).toFloat()
                            } catch (_: Exception) { null }
                        }
                        .filter { it > 0f }

                    if (readings.isNotEmpty()) {
                        val chartPoints = readings.takeLast(12)
                        val lastSpO2 = readings.last()
                        withContext(Dispatchers.Main) {
                            binding.tvSpO2Value.text = "${lastSpO2.toInt()}% SpO2"
                            binding.chartBloodOxygen.setData(chartPoints)
                        }
                    }
                }

                // --- Skin Temperature ---
                val skinTempFile = File(dir, "SkinTemp.jsonl")
                if (skinTempFile.exists()) {
                    val objTemps = mutableListOf<Float>()
                    val ambTemps = mutableListOf<Float>()

                    skinTempFile.readLines()
                        .filter { it.isNotBlank() }
                        .forEach { line ->
                            try {
                                val json = JSONObject(line)
                                val obj = json.optDouble("object_temp", 0.0).toFloat()
                                val amb = json.optDouble("ambient_temp", 0.0).toFloat()
                                if (obj > 0f) {
                                    objTemps.add(obj)
                                    ambTemps.add(amb)
                                }
                            } catch (_: Exception) {}
                        }

                    if (objTemps.isNotEmpty()) {
                        val lastObj = objTemps.last()
                        val skinLast12 = objTemps.takeLast(12)
                        val ambLast12 = ambTemps.takeLast(12)
                        withContext(Dispatchers.Main) {
                            binding.tvSkinTempValue.text = String.format("%.1f °C", lastObj)
                            binding.chartSkinTemp.setData(skinLast12, ambLast12)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load historical data: ${e.message}")
            }
        }
    }

    // ===== Data Receivers (UI updates only) =====

    inner class HealthDataReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val type = intent?.getStringExtra("type") ?: return
            val json = JSONObject(intent.getStringExtra("data") ?: return)

            runOnUiThread {
                when (type) {
                    "HeartRate" -> {
                        val hr = json.optInt("heart_rate", 0)
                        val status = json.optInt("status", -1)
                        if (hr > 0 && status == 1) {
                            hrReceivedValid = true
                            binding.tvHeartRateValue.text = "$hr bpm"
                            binding.chartHeartRate.addDataPoint(hr.toFloat())
                            hrValues.add(hr.toFloat())
                            updateHrStats()
                        }
                    }
                    "SpO2" -> {
                        val spo2 = json.optInt("spo2", 0)
                        val status = json.optInt("status", -1)
                        if (spo2 > 0 && status == 2) {
                            binding.tvSpO2Value.text = "$spo2% SpO2"
                            binding.chartBloodOxygen.addDataPoint(spo2.toFloat())
                            if (isManualSpo2Measuring) stopManualMeasurement("SpO2")
                        } else if (status < 0 && isManualSpo2Measuring) {
                            stopManualMeasurement("SpO2")
                            Toast.makeText(this@MetricsMonitoringActivity,
                                "SpO2 measurement failed — try again", Toast.LENGTH_SHORT).show()
                        }
                    }
                    "SkinTemp" -> {
                        val objTemp = json.optDouble("object_temp", 0.0).toFloat()
                        val ambTemp = json.optDouble("ambient_temp", 0.0).toFloat()
                        val status = json.optInt("status", -1)
                        if (objTemp > 0f) {
                            binding.tvSkinTempValue.text = String.format("%.1f °C", objTemp)
                            binding.chartSkinTemp.addDataPoint(objTemp, ambTemp)
                        }
                        if (status == 0 && isManualSkinTempMeasuring) {
                            stopManualMeasurement("SkinTemp")
                        }
                    }
                    "PPG" -> {
                        // PPG data handled by sequence pipeline
                    }
                }
            }
        }
    }

    inner class HrvResultReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            intent ?: return
            runOnUiThread {
                val hr = intent.getDoubleExtra("mean_hr_bpm", 0.0)
                val spo2 = intent.getDoubleExtra("spo2_mean_pct", 0.0)

                if (hr > 0) {
                    binding.tvHeartRateValue.text = "${String.format("%.0f", hr)} bpm"
                    binding.chartHeartRate.addDataPoint(hr.toFloat())
                    hrValues.add(hr.toFloat())
                    updateHrStats()
                }

                if (spo2 > 0) {
                    binding.tvSpO2Value.text = "${String.format("%.0f", spo2)}% SpO2"
                    binding.chartBloodOxygen.addDataPoint(spo2.toFloat())
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        try { unregisterReceiver(hrvReceiver) } catch (_: Exception) {}
    }
}
