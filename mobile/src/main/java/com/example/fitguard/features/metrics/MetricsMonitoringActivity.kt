package com.example.fitguard.features.metrics

import android.content.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.fitguard.data.processing.CsvWriter
import com.example.fitguard.data.processing.SequenceProcessor
import com.example.fitguard.databinding.ActivityMetricsMonitoringBinding
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

        // Samsung recommended measurement durations
        private const val HR_MEASUREMENT_DURATION_MS = 15_000L       // 15s for single HR reading
        private const val SPO2_MEASUREMENT_TIMEOUT_MS = 30_000L      // 30s max, auto-stops on valid
        private const val SKIN_TEMP_MEASUREMENT_TIMEOUT_MS = 30_000L // 30s max, auto-stops on valid

        // Auto-measurement interval
        private const val AUTO_INTERVAL_MS = 600_000L // 10 minutes

        private const val PREFS_NAME = "health_tracker_prefs"
    }

    // Coroutine scope for sending messages to watch
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())

    // Interval scheduling
    private val handler = Handler(Looper.getMainLooper())
    private var hrIntervalRunnable: Runnable? = null
    private var spo2IntervalRunnable: Runnable? = null
    private var skinTempIntervalRunnable: Runnable? = null
    private var hrStopRunnable: Runnable? = null

    // Active measurement tracking
    private var isHrMeasuring = false
    private var isSpo2Measuring = false
    private var isSkinTempMeasuring = false

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

        setupSensorToggles()
        setupMeasureButtons()
        loadHistoricalData()
    }

    private fun setupSensorToggles() {
        binding.switchHeartRate.setOnCheckedChangeListener { _, isChecked ->
            val vis = if (isChecked) View.VISIBLE else View.GONE
            binding.tvHeartRateValue.visibility = vis
            binding.chartHeartRate.visibility = vis
            binding.hrStatsRow.visibility = vis
            if (!isChecked && isHrMeasuring) {
                stopMeasurement("HeartRate")
                hrIntervalRunnable?.let { handler.removeCallbacks(it) }
                hrIntervalRunnable = null
            }
            updateMeasureButtons()
        }

        binding.switchSkinTemp.setOnCheckedChangeListener { _, isChecked ->
            val vis = if (isChecked) View.VISIBLE else View.GONE
            binding.tvSkinTempValue.visibility = vis
            binding.chartSkinTemp.visibility = vis
            if (!isChecked && isSkinTempMeasuring) {
                stopMeasurement("SkinTemp")
                skinTempIntervalRunnable?.let { handler.removeCallbacks(it) }
                skinTempIntervalRunnable = null
            }
            updateMeasureButtons()
        }

        binding.switchSpO2.setOnCheckedChangeListener { _, isChecked ->
            val vis = if (isChecked) View.VISIBLE else View.GONE
            binding.tvSpO2Value.visibility = vis
            binding.chartBloodOxygen.visibility = vis
            if (!isChecked && isSpo2Measuring) {
                stopMeasurement("SpO2")
                spo2IntervalRunnable?.let { handler.removeCallbacks(it) }
                spo2IntervalRunnable = null
            }
            updateMeasureButtons()
        }
    }

    override fun onResume() {
        super.onResume()
        updateMeasureButtons()
        startScheduledMeasurements()
    }

    override fun onPause() {
        super.onPause()
        stopAllMeasurements()
    }

    private fun setupMeasureButtons() {
        binding.btnMeasureHeartRate.setOnClickListener {
            startSingleMeasurement("HeartRate")
        }
        binding.btnMeasureSpO2.setOnClickListener {
            startSingleMeasurement("SpO2")
        }
        binding.btnMeasureSkinTemp.setOnClickListener {
            startSingleMeasurement("SkinTemp")
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

    // ===== Measurement Control =====

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

    private fun startSingleMeasurement(trackerType: String) {
        coroutineScope.launch {
            val sent = sendTrackerCommand("start", trackerType)
            if (!sent) return@launch

            when (trackerType) {
                "HeartRate" -> {
                    isHrMeasuring = true
                    binding.btnMeasureHeartRate.text = "Measuring..."
                    binding.btnMeasureHeartRate.isEnabled = false
                    hrStopRunnable = Runnable { stopMeasurement("HeartRate") }
                    handler.postDelayed(hrStopRunnable!!, HR_MEASUREMENT_DURATION_MS)
                }
                "SpO2" -> {
                    isSpo2Measuring = true
                    binding.btnMeasureSpO2.text = "Measuring..."
                    binding.btnMeasureSpO2.isEnabled = false
                    handler.postDelayed({ stopMeasurement("SpO2") }, SPO2_MEASUREMENT_TIMEOUT_MS)
                }
                "SkinTemp" -> {
                    isSkinTempMeasuring = true
                    binding.btnMeasureSkinTemp.text = "Measuring..."
                    binding.btnMeasureSkinTemp.isEnabled = false
                    handler.postDelayed({ stopMeasurement("SkinTemp") }, SKIN_TEMP_MEASUREMENT_TIMEOUT_MS)
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
                binding.btnMeasureHeartRate.text = "Measure"
                binding.btnMeasureHeartRate.isEnabled = true
            }
            "SpO2" -> {
                isSpo2Measuring = false
                binding.btnMeasureSpO2.text = "Measure"
                binding.btnMeasureSpO2.isEnabled = true
            }
            "SkinTemp" -> {
                isSkinTempMeasuring = false
                binding.btnMeasureSkinTemp.text = "Measure"
                binding.btnMeasureSkinTemp.isEnabled = true
            }
        }
    }

    private fun startScheduledMeasurements() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val hrMode = prefs.getString("hr_mode", "Manual") ?: "Manual"
        val spo2Mode = prefs.getString("spo2_mode", "Manual") ?: "Manual"
        val skinTempMode = prefs.getString("skin_temp_mode", "Manual") ?: "Manual"

        // Heart Rate
        if (binding.switchHeartRate.isChecked) {
            when (hrMode) {
                "Continuous" -> {
                    coroutineScope.launch {
                        val sent = sendTrackerCommand("start", "HeartRate")
                        if (sent) isHrMeasuring = true
                    }
                }
                "Every 10 minutes" -> {
                    startSingleMeasurement("HeartRate")
                    hrIntervalRunnable = object : Runnable {
                        override fun run() {
                            if (binding.switchHeartRate.isChecked) {
                                startSingleMeasurement("HeartRate")
                                handler.postDelayed(this, AUTO_INTERVAL_MS)
                            }
                        }
                    }
                    handler.postDelayed(hrIntervalRunnable!!, AUTO_INTERVAL_MS)
                }
                // "Manual" -> user clicks Measure button
            }
        }

        // SpO2
        if (binding.switchSpO2.isChecked && spo2Mode == "Every 10 minutes") {
            startSingleMeasurement("SpO2")
            spo2IntervalRunnable = object : Runnable {
                override fun run() {
                    if (binding.switchSpO2.isChecked) {
                        startSingleMeasurement("SpO2")
                        handler.postDelayed(this, AUTO_INTERVAL_MS)
                    }
                }
            }
            handler.postDelayed(spo2IntervalRunnable!!, AUTO_INTERVAL_MS)
        }

        // Skin Temperature
        if (binding.switchSkinTemp.isChecked && skinTempMode == "Every 10 minutes") {
            startSingleMeasurement("SkinTemp")
            skinTempIntervalRunnable = object : Runnable {
                override fun run() {
                    if (binding.switchSkinTemp.isChecked) {
                        startSingleMeasurement("SkinTemp")
                        handler.postDelayed(this, AUTO_INTERVAL_MS)
                    }
                }
            }
            handler.postDelayed(skinTempIntervalRunnable!!, AUTO_INTERVAL_MS)
        }
    }

    private fun stopAllMeasurements() {
        // Cancel interval runnables
        hrIntervalRunnable?.let { handler.removeCallbacks(it) }
        spo2IntervalRunnable?.let { handler.removeCallbacks(it) }
        skinTempIntervalRunnable?.let { handler.removeCallbacks(it) }
        hrIntervalRunnable = null
        spo2IntervalRunnable = null
        skinTempIntervalRunnable = null

        // Cancel pending HR stop
        hrStopRunnable?.let { handler.removeCallbacks(it) }
        hrStopRunnable = null

        // Stop active measurements on watch
        if (isHrMeasuring) {
            coroutineScope.launch { sendTrackerCommand("stop", "HeartRate") }
            isHrMeasuring = false
        }
        if (isSpo2Measuring) {
            coroutineScope.launch { sendTrackerCommand("stop", "SpO2") }
            isSpo2Measuring = false
        }
        if (isSkinTempMeasuring) {
            coroutineScope.launch { sendTrackerCommand("stop", "SkinTemp") }
            isSkinTempMeasuring = false
        }

        // Reset button states
        binding.btnMeasureHeartRate.text = "Measure"
        binding.btnMeasureHeartRate.isEnabled = true
        binding.btnMeasureSpO2.text = "Measure"
        binding.btnMeasureSpO2.isEnabled = true
        binding.btnMeasureSkinTemp.text = "Measure"
        binding.btnMeasureSkinTemp.isEnabled = true
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

    // ===== Data Receivers =====

    inner class HealthDataReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val type = intent?.getStringExtra("type") ?: return
            val json = JSONObject(intent.getStringExtra("data") ?: return)

            runOnUiThread {
                when (type) {
                    "HeartRate" -> {
                        val hr = json.optInt("heart_rate", 0)
                        if (hr > 0) {
                            binding.tvHeartRateValue.text = "$hr bpm"
                            binding.chartHeartRate.addDataPoint(hr.toFloat())
                            hrValues.add(hr.toFloat())
                            updateHrStats()
                        }
                    }
                    "SpO2" -> {
                        val spo2 = json.optInt("spo2", 0)
                        val status = json.optInt("status", -1)
                        if (spo2 > 0) {
                            binding.tvSpO2Value.text = "$spo2% SpO2"
                            binding.chartBloodOxygen.addDataPoint(spo2.toFloat())
                        }
                        if (status == 0 && isSpo2Measuring) {
                            stopMeasurement("SpO2")
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
                        if (status == 0 && isSkinTempMeasuring) {
                            stopMeasurement("SkinTemp")
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
        stopAllMeasurements()
        coroutineScope.cancel()
        try { unregisterReceiver(receiver) } catch (e: Exception) {}
        try { unregisterReceiver(hrvReceiver) } catch (e: Exception) {}
    }
}
