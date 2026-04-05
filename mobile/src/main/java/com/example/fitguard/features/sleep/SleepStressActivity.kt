package com.example.fitguard.features.sleep

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.example.fitguard.data.health.HealthConnectManager
import com.example.fitguard.data.health.SleepResult
import com.example.fitguard.data.health.StressResult
import com.example.fitguard.data.processing.CsvWriter
import com.example.fitguard.data.processing.StressCalculator
import com.example.fitguard.databinding.ActivitySleepStressBinding
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

class SleepStressActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySleepStressBinding
    private lateinit var healthConnectManager: HealthConnectManager

    private val prefs by lazy { getSharedPreferences("hc_prefs", Context.MODE_PRIVATE) }

    private val stressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            Log.d(TAG, "Watch stress broadcast received")
            // Reload full history so chart includes the new point
            runOnUiThread { loadSavedWatchStress() }
        }
    }

    // Track how many times the user has denied permissions (per the HC UI guidelines)
    private var denyCount: Int
        get() = prefs.getInt("permission_deny_count", 0)
        set(v) = prefs.edit().putInt("permission_deny_count", v).apply()

    companion object {
        private const val TAG = "SleepStressActivity"
        const val ACTION_MANAGE_HEALTH_PERMISSIONS = "androidx.health.ACTION_MANAGE_HEALTH_PERMISSIONS"
    }

    private val requestPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        Log.d(TAG, "Permission result: $granted")
        if (granted.containsAll(HealthConnectManager.PERMISSIONS)) {
            denyCount = 0
            lifecycleScope.launch { fetchAndDisplay() }
        } else {
            denyCount++
            Log.w(TAG, "Permissions denied. denyCount=$denyCount")
            if (denyCount >= 2) {
                showPermanentlyDeniedCard()
            } else {
                showSetupCard(
                    "FitGuard needs permission to read Sleep & Stress data from Health Connect.",
                    showGrantButton = true
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySleepStressBinding.inflate(layoutInflater)
        setContentView(binding.root)

        healthConnectManager = HealthConnectManager(this)

        binding.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.btnSync.setOnClickListener {
            Toast.makeText(this, "Syncing…", Toast.LENGTH_SHORT).show()
            checkAndLoad()
        }
        binding.btnGrantPermissions.setOnClickListener {
            requestPermissions.launch(HealthConnectManager.PERMISSIONS)
        }
        binding.btnManageAccess.setOnClickListener {
            openHealthConnectSettings()
        }

        registerReceiver(
            stressReceiver,
            IntentFilter(StressCalculator.ACTION_STRESS_RESULT),
            RECEIVER_NOT_EXPORTED
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(stressReceiver) } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        loadSavedWatchStress()
        checkAndLoad()
    }

    private fun checkAndLoad() {
        val sdkStatus = try {
            HealthConnectClient.getSdkStatus(this)
        } catch (e: Exception) {
            Log.e(TAG, "getSdkStatus threw", e)
            showSetupCard("Health Connect check failed:\n${e.localizedMessage}", showGrantButton = false)
            return
        }

        Log.d(TAG, "SDK status=$sdkStatus, denyCount=$denyCount")

        when (sdkStatus) {
            HealthConnectClient.SDK_AVAILABLE -> {
                lifecycleScope.launch {
                    try {
                        val hasPermissions = healthConnectManager.checkPermissions()
                        Log.d(TAG, "hasPermissions=$hasPermissions")
                        when {
                            hasPermissions -> fetchAndDisplay()
                            denyCount >= 2 -> showPermanentlyDeniedCard()
                            else -> showSetupCard(
                                "FitGuard needs permission to read Sleep & Stress data from Health Connect.",
                                showGrantButton = true
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "checkPermissions threw", e)
                        showSetupCard(
                            "Health Connect error (${e.javaClass.simpleName}):\n${e.localizedMessage}",
                            showGrantButton = false
                        )
                    }
                }
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                showSetupCard("Health Connect needs to be updated.", showGrantButton = false)
                binding.btnGrantPermissions.text = "Update Health Connect"
                binding.btnGrantPermissions.visibility = View.VISIBLE
                binding.btnGrantPermissions.setOnClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=com.google.android.apps.healthdata")))
                }
            }
            else -> {
                showSetupCard("Health Connect is not available (status=$sdkStatus).\nPlease install it from Play Store.", showGrantButton = false)
                binding.btnGrantPermissions.text = "Install Health Connect"
                binding.btnGrantPermissions.visibility = View.VISIBLE
                binding.btnGrantPermissions.setOnClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=com.google.android.apps.healthdata")))
                }
            }
        }
    }

    private suspend fun fetchAndDisplay() {
        hideSetupCard()
        showStatus("Syncing from Samsung Health…")
        try {
            val sleep = healthConnectManager.readLatestSleep()
            val stress = healthConnectManager.readLatestStress()
            Log.d(TAG, "sleep=${sleep != null}, stress=${stress != null}")
            updateSleepUI(sleep)
            updateStressUI(stress)
            hideStatus()
            binding.btnManageAccess.visibility = View.VISIBLE
        } catch (e: Exception) {
            Log.e(TAG, "fetchAndDisplay error", e)
            showStatus("Failed to load: ${e.localizedMessage ?: e.javaClass.simpleName}")
        }
    }

    // ===== Setup cards =====

    private fun showSetupCard(message: String, showGrantButton: Boolean) {
        binding.cardSetup.visibility = View.VISIBLE
        binding.tvSetupMessage.text = message
        binding.tvSetupTitle.text = "Health Connect"
        binding.btnGrantPermissions.text = "Connect Health Connect"
        binding.btnGrantPermissions.visibility = if (showGrantButton) View.VISIBLE else View.GONE
        binding.btnManageAccess.visibility = View.GONE
        hideStatus()
    }

    private fun showPermanentlyDeniedCard() {
        binding.cardSetup.visibility = View.VISIBLE
        binding.tvSetupTitle.text = "Permissions Required"
        binding.tvSetupMessage.text =
            "You've declined permissions twice.\nPlease open Health Connect Settings to grant access to FitGuard."
        binding.btnGrantPermissions.visibility = View.GONE
        binding.btnManageAccess.visibility = View.VISIBLE
        binding.btnManageAccess.text = "Open Health Connect Settings"
        hideStatus()
    }

    private fun hideSetupCard() {
        binding.cardSetup.visibility = View.GONE
    }

    private fun openHealthConnectSettings() {
        try {
            startActivity(Intent(ACTION_MANAGE_HEALTH_PERMISSIONS).apply {
                putExtra(Intent.EXTRA_PACKAGE_NAME, packageName)
            })
        } catch (e: Exception) {
            // Fallback: open Health Connect main settings
            startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS))
        }
    }

    // ===== Status banner =====

    private fun showStatus(message: String) {
        binding.tvHealthConnectStatus.text = message
        binding.tvHealthConnectStatus.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        binding.tvHealthConnectStatus.visibility = View.GONE
    }

    // ===== Data UI =====

    private fun updateSleepUI(sleep: SleepResult?) {
        if (sleep == null) {
            binding.tvSleepQuality.text = "Quality: --"
            binding.tvSleepDuration.text = "No sleep data — open Samsung Health to track sleep"
            binding.tvSleepSource.visibility = View.GONE
            return
        }
        val qualityColor = when (sleep.qualityLabel) {
            "Excellent" -> Color.parseColor("#4CAF50")
            "Good"      -> Color.parseColor("#6EDB34")
            "Fair"      -> Color.parseColor("#FFC107")
            else        -> Color.parseColor("#F44336")
        }
        binding.tvSleepQuality.text = "Quality: ${sleep.qualityLabel}"
        binding.tvSleepQuality.setTextColor(qualityColor)
        binding.tvSleepDuration.text = "Duration: ${formatDuration(sleep.durationMs)}"
        if (sleep.chartPoints.isNotEmpty()) {
            binding.chartSleep.setTimeRange(sleep.startTime, sleep.endTime)
            binding.chartSleep.setData(sleep.chartPoints)
        }
        binding.tvSleepStatus.visibility = View.GONE
        binding.tvSleepSource.text = "Samsung Health · ended ${formatTime(sleep.endTime)}"
        binding.tvSleepSource.visibility = View.VISIBLE
    }

    private fun loadSavedWatchStress() {
        lifecycleScope.launch {
            val history = withContext(Dispatchers.IO) { loadStressHistory() }
            if (history.isEmpty()) return@launch

            val latest = history.last()
            // Only show if latest is less than 24 hours old
            if (System.currentTimeMillis() - latest.timestamp > 24 * 60 * 60 * 1000L) return@launch

            updateWatchStressUI(latest, history)
        }
    }

    /**
     * Reads WatchStress.jsonl from the last 7 daily folders.
     * Returns entries sorted by timestamp.
     */
    private fun loadStressHistory(): List<WatchStressEntry> {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: ""
        val baseDir = CsvWriter.getOutputDir(userId, "")
        val entries = mutableListOf<WatchStressEntry>()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = Calendar.getInstance()

        for (dayOffset in 6 downTo 0) {
            cal.time = Date()
            cal.add(Calendar.DAY_OF_YEAR, -dayOffset)
            val dateFolder = dateFormat.format(cal.time)
            val file = File(File(baseDir, dateFolder), "WatchStress.jsonl")
            if (!file.exists()) continue
            try {
                file.readLines().filter { it.isNotBlank() }.forEach { line ->
                    try {
                        val json = JSONObject(line)
                        entries.add(WatchStressEntry(
                            score = json.optDouble("score", -1.0).toFloat(),
                            label = json.optString("label", ""),
                            rmssd = json.optDouble("rmssd", 0.0).toFloat(),
                            meanHr = json.optDouble("mean_hr", 0.0).toFloat(),
                            ibiCount = json.optInt("ibi_count", 0),
                            timestamp = json.optLong("timestamp", 0L)
                        ))
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
        return entries.filter { it.score >= 0f }.sortedBy { it.timestamp }
    }

    private fun updateWatchStressUI(latest: WatchStressEntry, history: List<WatchStressEntry>) {
        val stressColor = when {
            latest.score < 33f -> Color.parseColor("#6EDB34")
            latest.score < 66f -> Color.parseColor("#FFC107")
            else               -> Color.parseColor("#F44336")
        }
        binding.tvStressValue.text = String.format("%.0f", latest.score)
        binding.tvStressValue.setTextColor(stressColor)
        binding.tvStressStatus.text = "Status: ${latest.label}"
        binding.tvStressStatus.setTextColor(stressColor)

        // Gauge
        binding.gaugeStress.setStressValue(latest.score)

        // Chart — convert 0-100 score to 1-3 scale (1=Relaxed, 2=Average, 3=High)
        if (history.isNotEmpty()) {
            val chartPoints = history.map { 1f + (it.score / 100f) * 2f }
            val timeFormat = SimpleDateFormat("HH:mm", Locale.US)
            val dateLabels = history.map { timeFormat.format(Date(it.timestamp)) }
            binding.chartStress.setDateLabels(dateLabels)
            binding.chartStress.setData(chartPoints)
        }

        binding.tvStressSource.text = "Watch · HRV-based (RMSSD: ${String.format("%.1f", latest.rmssd)}ms, ${latest.ibiCount} IBIs)"
        binding.tvStressSource.visibility = View.VISIBLE
    }

    private data class WatchStressEntry(
        val score: Float,
        val label: String,
        val rmssd: Float,
        val meanHr: Float,
        val ibiCount: Int,
        val timestamp: Long
    )

    private fun updateStressUI(stress: StressResult?) {
        if (stress == null) {
            // Don't overwrite watch-derived stress if already showing
            val hasWatchStress = binding.tvStressSource.visibility == View.VISIBLE
                    && binding.tvStressSource.text.toString().startsWith("Watch")
            if (!hasWatchStress) {
                binding.tvStressValue.text = "--"
                binding.tvStressStatus.text = "No HRV data — open Samsung Health to measure stress"
                binding.tvStressSource.visibility = View.GONE
            }
            return
        }
        val stressColor = when {
            stress.score < 33f -> Color.parseColor("#6EDB34")
            stress.score < 66f -> Color.parseColor("#FFC107")
            else               -> Color.parseColor("#F44336")
        }
        binding.tvStressValue.text = String.format("%.0f", stress.score)
        binding.tvStressValue.setTextColor(stressColor)
        binding.tvStressStatus.text = "Status: ${stress.label}"
        binding.tvStressStatus.setTextColor(stressColor)
        binding.gaugeStress.setStressValue(stress.score)
        if (stress.history.isNotEmpty()) {
            binding.chartStress.setDateLabels(stress.historyDates)
            binding.chartStress.setData(stress.history)
        }
        binding.tvStressSource.text = "Samsung Health · HRV-based"
        binding.tvStressSource.visibility = View.VISIBLE
    }

    private fun formatDuration(durationMs: Long): String {
        val hours = durationMs / 3_600_000
        val minutes = (durationMs % 3_600_000) / 60_000
        return "${hours}hr ${minutes}min"
    }

    private fun formatTime(instant: Instant): String =
        DateTimeFormatter.ofPattern("MMM d, h:mm a")
            .withZone(ZoneId.systemDefault())
            .format(instant)
}
