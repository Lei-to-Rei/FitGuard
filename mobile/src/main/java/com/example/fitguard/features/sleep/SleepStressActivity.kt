package com.example.fitguard.features.sleep

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.fitguard.features.activitytracking.ActivityTrackingViewModel
import com.example.fitguard.data.processing.CsvWriter
import com.example.fitguard.data.processing.StressCalculator
import com.example.fitguard.databinding.ActivitySleepStressBinding
import com.example.fitguard.services.WearableDataListenerService
import com.google.android.gms.wearable.Wearable
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class SleepStressActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySleepStressBinding
    private val stressCalculator = StressCalculator()
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())
    private var isWatchConnected = false
    private var isHrMeasuring = false
    private val stressHistory = mutableListOf<Float>()
    private var isSleepMonitoring = false
    private var sleepSessionReceiver: BroadcastReceiver? = null

    companion object {
        private const val TAG = "SleepStress"
        private const val HR_MEASUREMENT_DURATION_MS = 15_000L
    }

    private val healthDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val type = intent?.getStringExtra("type") ?: return
            if (type != "HeartRate") return
            val json = JSONObject(intent.getStringExtra("data") ?: return)

            runOnUiThread {
                val ibiString = json.optString("ibi_list", "[]")
                val ibis = ibiString.removeSurrounding("[", "]")
                    .split(",")
                    .mapNotNull { it.trim().toIntOrNull() }
                val timestamp = json.optLong("timestamp", System.currentTimeMillis())

                if (ibis.isNotEmpty()) {
                    stressCalculator.addIbiValues(ibis, timestamp)
                    updateStressUI()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySleepStressBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        registerReceiver(
            healthDataReceiver,
            IntentFilter("com.example.fitguard.HEALTH_DATA"),
            RECEIVER_NOT_EXPORTED
        )

        setupSleepMonitoring()
        loadSleepData()
        loadStressHistory()
    }

    override fun onResume() {
        super.onResume()
        checkWatchConnection()
    }

    override fun onPause() {
        super.onPause()
        stopHrMeasurement()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopHrMeasurement()
        coroutineScope.cancel()
        try { unregisterReceiver(healthDataReceiver) } catch (_: Exception) {}
        sleepSessionReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
    }

    private fun checkWatchConnection() {
        coroutineScope.launch(Dispatchers.IO) {
            val connected = try {
                Wearable.getNodeClient(this@SleepStressActivity)
                    .connectedNodes.await().isNotEmpty()
            } catch (_: Exception) { false }

            withContext(Dispatchers.Main) {
                isWatchConnected = connected
                binding.switchSleepMonitor.isEnabled = connected

                if (!connected && !isSleepMonitoring) {
                    binding.tvSleepStatus.text = "No watch connected"
                    binding.tvSleepStatus.visibility = View.VISIBLE
                }

                if (connected) {
                    startHrMeasurement()
                }
            }
        }
    }

    private fun startHrMeasurement() {
        if (ActivityTrackingViewModel.activeSessionId != null) {
            Log.d(TAG, "HR measurement skipped — workout session active")
            return
        }
        WearableDataListenerService.sendTrackerCommand(this, "start", "HeartRate")
        isHrMeasuring = true
        Log.d(TAG, "HR measurement started for stress calculation")
    }

    private fun stopHrMeasurement() {
        if (isHrMeasuring) {
            WearableDataListenerService.sendTrackerCommand(this, "stop", "HeartRate")
            isHrMeasuring = false
        }
    }

    private fun updateStressUI() {
        val score = stressCalculator.getStressScore() ?: return
        val label = stressCalculator.getStressLabel(score)
        val rmssd = stressCalculator.getRmssd() ?: 0f

        binding.tvStressValue.text = String.format("%.0f", score)
        binding.tvStressStatus.text = "Status: $label"
        binding.gaugeStress.setStressValue(score)

        // Save and update chart
        saveStressReading(score, rmssd, label)
        stressHistory.add(mapStressToChartLevel(score))
        if (stressHistory.size > 6) stressHistory.removeAt(0)
        binding.chartStress.setData(stressHistory.toList())
    }

    private fun mapStressToChartLevel(score: Float): Float {
        // StressChartView uses 1.0=Relaxed, 2.0=Average, 3.0=High
        return when {
            score < 33f -> 1.0f
            score < 66f -> 2.0f
            else -> 3.0f
        }
    }

    private fun saveStressReading(score: Float, rmssd: Float, label: String) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val userId = FirebaseAuth.getInstance().currentUser?.uid ?: ""
                val dateFolder = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                val baseDir = CsvWriter.getOutputDir(userId, "")
                val dir = File(baseDir, dateFolder)
                dir.mkdirs()
                val json = JSONObject().apply {
                    put("timestamp", System.currentTimeMillis())
                    put("stress_score", score.toDouble())
                    put("rmssd", rmssd.toDouble())
                    put("label", label)
                }
                File(dir, "Stress.jsonl").appendText(json.toString() + "\n")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save stress reading: ${e.message}")
            }
        }
    }

    // ===== Sleep Monitoring =====

    private fun setupSleepMonitoring() {
        isSleepMonitoring = SleepMonitorService.isRunning(this)
        binding.switchSleepMonitor.isChecked = isSleepMonitoring

        binding.switchSleepMonitor.setOnCheckedChangeListener { _, isChecked ->
            if (!isWatchConnected && isChecked) {
                binding.switchSleepMonitor.isChecked = false
                Toast.makeText(this, "No watch connected", Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }
            if (ActivityTrackingViewModel.activeSessionId != null && isChecked) {
                binding.switchSleepMonitor.isChecked = false
                Toast.makeText(this, "Workout active — sleep monitoring unavailable", Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }
            if (isChecked) {
                SleepMonitorService.start(this)
                isSleepMonitoring = true
                binding.tvSleepStatus.text = "Monitoring active — tracking sleep"
                binding.tvSleepStatus.visibility = View.VISIBLE
            } else {
                SleepMonitorService.stop(this)
                isSleepMonitoring = false
                binding.tvSleepStatus.text = "Processing sleep data..."
                binding.tvSleepStatus.visibility = View.VISIBLE
            }
        }

        // Listen for session completion to refresh UI
        sleepSessionReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                runOnUiThread {
                    binding.tvSleepStatus.text = "Sleep session complete"
                    loadSleepData()
                }
            }
        }
        registerReceiver(
            sleepSessionReceiver,
            IntentFilter(SleepMonitorService.ACTION_SLEEP_SESSION_COMPLETE),
            RECEIVER_NOT_EXPORTED
        )
    }

    private fun loadSleepData() {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val userId = FirebaseAuth.getInstance().currentUser?.uid ?: ""
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                val session = SleepDataLoader.loadLatestSession(userId, today) ?: return@launch

                val chartPoints = SleepDataLoader.sessionToChartPoints(session)

                withContext(Dispatchers.Main) {
                    binding.tvSleepDuration.text =
                        "Sleep Duration: ${SleepDataLoader.formatDuration(session.totalSleepDurationMs)}"
                    binding.tvSleepQuality.text = "Quality: ${session.qualityLabel}"

                    if (chartPoints.isNotEmpty()) {
                        binding.chartSleep.setData(chartPoints)
                    }

                    // SpO2 warning
                    if (session.minSpO2 in 1..89) {
                        binding.tvSpO2Warning.text = "Low SpO2 detected: ${session.minSpO2}%"
                        binding.tvSpO2Warning.visibility = View.VISIBLE
                    } else {
                        binding.tvSpO2Warning.visibility = View.GONE
                    }

                    binding.tvSleepStatus.visibility = View.GONE
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load sleep data: ${e.message}")
            }
        }
    }

    // ===== Stress =====

    private fun loadStressHistory() {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val userId = FirebaseAuth.getInstance().currentUser?.uid ?: ""
                val dateFolder = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                val baseDir = CsvWriter.getOutputDir(userId, "")
                val file = File(File(baseDir, dateFolder), "Stress.jsonl")
                if (!file.exists()) return@launch

                val readings = file.readLines()
                    .filter { it.isNotBlank() }
                    .mapNotNull {
                        try {
                            val json = JSONObject(it)
                            json.optDouble("stress_score", -1.0).toFloat()
                        } catch (_: Exception) { null }
                    }
                    .filter { it >= 0f }
                    .takeLast(6)
                    .map { mapStressToChartLevel(it) }

                if (readings.isNotEmpty()) {
                    val lastScore = file.readLines()
                        .filter { it.isNotBlank() }
                        .lastOrNull()?.let {
                            try { JSONObject(it).optDouble("stress_score", -1.0).toFloat() }
                            catch (_: Exception) { null }
                        }

                    withContext(Dispatchers.Main) {
                        stressHistory.clear()
                        stressHistory.addAll(readings)
                        binding.chartStress.setData(stressHistory.toList())
                        lastScore?.let { score ->
                            if (score >= 0f) {
                                binding.tvStressValue.text = String.format("%.0f", score)
                                binding.tvStressStatus.text = "Status: ${stressCalculator.getStressLabel(score)}"
                                binding.gaugeStress.setStressValue(score)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load stress history: ${e.message}")
            }
        }
    }
}
