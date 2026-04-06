package com.example.fitguard

import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Window
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.fitguard.auth.LoginActivity
import com.example.fitguard.data.processing.CsvWriter
import com.example.fitguard.data.processing.SequenceProcessor
import com.example.fitguard.data.repository.ActivityHistoryRepository
import com.example.fitguard.data.repository.AuthRepository
import com.example.fitguard.databinding.ActivityMainBinding
import com.example.fitguard.features.activitytracking.ActivityHistoryActivity
import com.example.fitguard.features.activitytracking.ActivityTrackingActivity
import com.example.fitguard.features.fatigue.FatiguePredictionActivity
import com.example.fitguard.features.metrics.MetricsMonitoringActivity
import com.example.fitguard.features.nutrition.NutritionTrackingActivity
import com.example.fitguard.features.health.SamsungHealthActivity
import com.example.fitguard.features.sleep.SleepStressActivity
import com.example.fitguard.features.profile.UserHomeActivity
import com.example.fitguard.ui.adapter.DashboardAdapter
import com.example.fitguard.ui.model.DashboardItem
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.time.LocalDate
import java.time.ZoneId

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var dashboardAdapter: DashboardAdapter
    private var storagePermissionDialogShown = false
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())
    private var lastSpO2Value = 0

    private val healthReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val type = intent?.getStringExtra("type") ?: return
            val json = JSONObject(intent.getStringExtra("data") ?: return)
            runOnUiThread {
                when (type) {
                    "HeartRate" -> {
                        val hr = json.optInt("heart_rate", 0)
                        val status = json.optInt("status", -1)
                        if (hr > 0 && status == 1) {
                            binding.tvHeartRateValue.text = "$hr bpm"
                            binding.chartHeartRateMini.addDataPoint(hr.toFloat())
                        }
                    }
                    "SpO2" -> {
                        val spo2 = json.optInt("spo2", 0)
                        val status = json.optInt("status", -1)
                        if (spo2 > 0 && status == 2) {
                            lastSpO2Value = spo2
                            binding.tvBloodOxygenValue.text = "$spo2%"
                            updateSpO2Dot(spo2)
                        }
                    }
                }
            }
        }
    }

    // Fallback: PPG-derived HR from SequenceProcessor during active sessions
    private val featureReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            intent ?: return
            val hr = intent.getDoubleExtra("mean_hr_bpm", 0.0)
            if (hr > 0) {
                runOnUiThread {
                    binding.tvHeartRateValue.text = "${String.format("%.0f", hr)} bpm"
                    binding.chartHeartRateMini.addDataPoint(hr.toFloat())
                }
            }
        }
    }

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (!Environment.isExternalStorageManager()) {
            Toast.makeText(this, "Storage permission not granted. Some features may not work.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check authentication
        if (!AuthRepository.isUserLoggedIn()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupHeader()
        setupDashboard()
        setupBottomNavigation()
        checkStoragePermission()

        binding.chartHeartRateMini.showGrid = false
        registerReceiver(healthReceiver, IntentFilter("com.example.fitguard.HEALTH_DATA"), RECEIVER_NOT_EXPORTED)
        registerReceiver(featureReceiver, IntentFilter(SequenceProcessor.ACTION_SEQUENCE_PROCESSED), RECEIVER_NOT_EXPORTED)
        loadHealthData()
        loadProgressStats()
        updateRecoveryProgress()

        binding.containerRecovered.setOnClickListener { showRecoveryDialog() }
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            binding.bottomNavigation.selectedItemId = R.id.nav_home
            loadHealthData()
            loadProgressStats()
            updateRecoveryProgress()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
        try { unregisterReceiver(healthReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(featureReceiver) } catch (_: Exception) {}
    }

    private fun setupHeader() {
        val user = AuthRepository.currentUser
        val userName = user?.displayName ?: user?.email?.substringBefore("@") ?: "User"
        binding.tvWelcome.text = "Welcome, $userName!"
    }

    private fun setupDashboard() {
        val dashboardItems = listOf(
            DashboardItem(
                title = "Metrics Monitoring",
                description = "Heart rate, SpO2, and vital signs tracking",
                icon = R.drawable.ic_health_podcast,
                activityClass = MetricsMonitoringActivity::class.java
            ),
            DashboardItem(
                title = "Workout History",
                description = "Past Workout and trends",
                icon = R.drawable.ic_dumbbell_ray,
                activityClass = ActivityHistoryActivity::class.java
            ),
            DashboardItem(
                title = "Sleep & Stress Monitoring",
                description = "Monitor sleep quality & Stress levels",
                icon = R.drawable.ic_bed,
                activityClass = SleepStressActivity::class.java
            ),
            DashboardItem(
                title = "Nutrition Monitoring",
                description = "Log and Track your nutrition",
                icon = R.drawable.ic_salad,
                activityClass = NutritionTrackingActivity::class.java
            ),
            DashboardItem(
                title = "Samsung Health",
                description = "Steps, body, vitals & more from Health Connect",
                icon = R.drawable.ic_heart_rate,
                activityClass = SamsungHealthActivity::class.java
            )
        )

        dashboardAdapter = DashboardAdapter(dashboardItems) { item ->
            startActivity(Intent(this, item.activityClass))
        }

        binding.rvDashboard.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = dashboardAdapter
            isNestedScrollingEnabled = false
        }
    }

    private fun updateSpO2Dot(spo2: Int) {
        binding.dotSpO2.post {
            val parent = binding.dotSpO2.parent as android.view.View
            val barWidth = parent.width - binding.dotSpO2.width
            val fraction = ((spo2.toFloat() - 70f) / (100f - 70f)).coerceIn(0f, 1f)
            binding.dotSpO2.translationX = barWidth * fraction
        }
    }

    private fun loadHealthData() {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return@launch
                val dateFolder = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                val dir = File(CsvWriter.getOutputDir(userId, ""), dateFolder)

                // Heart Rate — try sensor data first, fall back to PPG features
                var hrLoaded = false
                val hrFile = File(dir, "HeartRate.jsonl")
                if (hrFile.exists()) {
                    val readings = hrFile.readLines()
                        .filter { it.isNotBlank() }
                        .mapNotNull { line ->
                            try { JSONObject(line).optInt("heart_rate", 0).toFloat() }
                            catch (_: Exception) { null }
                        }
                        .filter { it > 0f }

                    if (readings.isNotEmpty()) {
                        hrLoaded = true
                        val chartPoints = readings.takeLast(12)
                        val lastHr = readings.last().toInt()
                        withContext(Dispatchers.Main) {
                            binding.tvHeartRateValue.text = "$lastHr bpm"
                            binding.chartHeartRateMini.setData(chartPoints)
                        }
                    }
                }

                // Fallback: read mean_hr_bpm from features.csv (PPG-derived)
                if (!hrLoaded) {
                    val hrFromFeatures = readHrFromFeaturesCsv(userId)
                    if (hrFromFeatures.isNotEmpty()) {
                        val chartPoints = hrFromFeatures.takeLast(12)
                        val lastHr = hrFromFeatures.last().toInt()
                        withContext(Dispatchers.Main) {
                            binding.tvHeartRateValue.text = "$lastHr bpm"
                            binding.chartHeartRateMini.setData(chartPoints)
                        }
                    }
                }

                // SpO2
                val spo2File = File(dir, "SpO2.jsonl")
                if (spo2File.exists()) {
                    val readings = spo2File.readLines()
                        .filter { it.isNotBlank() }
                        .mapNotNull { line ->
                            try { JSONObject(line).optInt("spo2", 0).toFloat() }
                            catch (_: Exception) { null }
                        }
                        .filter { it > 0f }

                    if (readings.isNotEmpty()) {
                        val lastSpo2 = readings.last().toInt()
                        withContext(Dispatchers.Main) {
                            lastSpO2Value = lastSpo2
                            binding.tvBloodOxygenValue.text = "$lastSpo2%"
                            updateSpO2Dot(lastSpo2)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun loadProgressStats() {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return@launch
                val sessions = ActivityHistoryRepository.loadSessions(userId)

                val today = LocalDate.now(ZoneId.systemDefault())
                val zone = ZoneId.systemDefault()

                // Filter to current month
                val monthSessions = sessions.filter { session ->
                    val date = java.time.Instant.ofEpochMilli(session.startTimeMillis)
                        .atZone(zone).toLocalDate()
                    date.year == today.year && date.monthValue == today.monthValue
                }

                val activitiesCount = monthSessions.size

                // Estimate calories via MET × 70 kg × hours
                val totalCalories = monthSessions.sumOf { session ->
                    val hours = session.durationMillis / 3_600_000.0
                    val met = when {
                        session.activityType.contains("Run", ignoreCase = true) -> 8.0
                        session.activityType.contains("Cycl", ignoreCase = true) -> 6.0
                        session.activityType.contains("Walk", ignoreCase = true) -> 3.5
                        else -> 5.0
                    }
                    (met * 70.0 * hours).toInt()
                }

                // Streak: consecutive days ending today that have at least one session
                val sessionDates = sessions.map { session ->
                    java.time.Instant.ofEpochMilli(session.startTimeMillis)
                        .atZone(zone).toLocalDate()
                }.toSet()

                var streak = 0
                var day = today
                while (day in sessionDates) {
                    streak++
                    day = day.minusDays(1)
                }

                val caloriesText = if (totalCalories >= 1000)
                    String.format("%.1fk", totalCalories / 1000.0)
                else
                    totalCalories.toString()

                withContext(Dispatchers.Main) {
                    binding.tvActivities.text = activitiesCount.toString()
                    binding.tvCalories.text = caloriesText
                    binding.tvStreak.text = streak.toString()
                }
            } catch (_: Exception) {}
        }
    }

    private fun updateRecoveryProgress() {
        val recoveryPrefs = getSharedPreferences("recovery_state", MODE_PRIVATE)
        val sessionEndTime = recoveryPrefs.getLong("session_end_time", 0L)
        val restHours = recoveryPrefs.getInt("rest_hours", 0)

        if (sessionEndTime == 0L || restHours == 0) {
            // No recovery data — show 100%
            binding.progressRecovered.progress = 100
            binding.tvRecoveredPercent.text = "100%"
            return
        }

        val elapsedMs = System.currentTimeMillis() - sessionEndTime
        val restMs = restHours * 3_600_000L
        val percent = ((elapsedMs.toFloat() / restMs) * 100f).coerceIn(0f, 100f).toInt()

        binding.progressRecovered.progress = percent
        binding.tvRecoveredPercent.text = "$percent%"
    }

    private fun checkStoragePermission() {
        if (!Environment.isExternalStorageManager()) {
            showStoragePermissionDialog()
        }
    }

    private fun showStoragePermissionDialog() {
        if (storagePermissionDialogShown) return
        storagePermissionDialogShown = true

        AlertDialog.Builder(this)
            .setTitle("Storage Access Required")
            .setMessage("FitGuard needs access to manage files in your Downloads folder to save and review workout data. Please grant \"All files access\" on the next screen.")
            .setPositiveButton("Grant") { _, _ ->
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                manageStorageLauncher.launch(intent)
            }
            .setNegativeButton("Later", null)
            .setCancelable(false)
            .show()
    }

    /**
     * Read mean_hr_bpm from features.csv — checks active session dir first, then base user dir.
     */
    private fun readHrFromFeaturesCsv(userId: String): List<Float> {
        val sessionDir = com.example.fitguard.features.activitytracking.ActivityTrackingViewModel.activeSessionDir ?: ""
        val candidates = mutableListOf<File>()
        if (sessionDir.isNotEmpty()) {
            candidates.add(File(CsvWriter.getOutputDir(userId, sessionDir), "features.csv"))
        }
        candidates.add(File(CsvWriter.getOutputDir(userId, ""), "features.csv"))

        for (file in candidates) {
            if (!file.exists()) continue
            try {
                val readings = file.readLines().drop(1) // skip header
                    .mapNotNull { line ->
                        val cols = line.split(",")
                        // mean_hr_bpm is column index 4
                        cols.getOrNull(4)?.toFloatOrNull()
                    }
                    .filter { it > 0f }
                if (readings.isNotEmpty()) return readings
            } catch (_: Exception) {}
        }
        return emptyList()
    }

    private fun showRecoveryDialog() {
        val recoveryPrefs = getSharedPreferences("recovery_state", MODE_PRIVATE)
        val sessionEndTime = recoveryPrefs.getLong("session_end_time", 0L)
        val restHours      = recoveryPrefs.getInt("rest_hours", 0)
        val recTitle       = recoveryPrefs.getString("phone_title", null)
        val recBody        = recoveryPrefs.getString("phone_body", null)

        val percent = if (sessionEndTime == 0L || restHours == 0) {
            100
        } else {
            val elapsedMs = System.currentTimeMillis() - sessionEndTime
            val restMs    = restHours * 3_600_000L
            ((elapsedMs.toFloat() / restMs) * 100f).coerceIn(0f, 100f).toInt()
        }

        val statusMsg = when {
            percent >= 100 -> "Fully recovered. Ready to train!"
            percent >= 75  -> "Nearly fully recovered. Ready to train."
            percent >= 50  -> "Good recovery! Ready for moderate training."
            percent >= 25  -> "Partial recovery. Light activity is fine."
            else           -> "Still recovering. Avoid intense training."
        }

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = layoutInflater.inflate(R.layout.dialog_recovery_progress, null)
        dialog.setContentView(view)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                (resources.displayMetrics.widthPixels * 0.9f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }

        view.findViewById<ProgressBar>(R.id.dialogProgressGauge).progress = percent
        view.findViewById<TextView>(R.id.dialogTvPercent).text   = "$percent%"
        view.findViewById<TextView>(R.id.dialogTvStatusMsg).text = statusMsg

        val recTitleView = view.findViewById<TextView>(R.id.dialogTvRecTitle)
        val recBodyView  = view.findViewById<TextView>(R.id.dialogTvRecBody)
        if (recTitle != null && recBody != null) {
            recTitleView.text = recTitle
            recBodyView.text  = recBody
        } else {
            recTitleView.text = "No recommendation yet"
            recBodyView.text  = "Complete a workout session to receive a personalized recovery recommendation."
        }

        view.findViewById<TextView>(R.id.btnDialogBack).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.selectedItemId = R.id.nav_home

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> true
                R.id.nav_activity -> {
                    startActivity(Intent(this, ActivityTrackingActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    })
                    overridePendingTransition(0, 0)
                    true
                }
                R.id.nav_health -> {
                    startActivity(Intent(this, FatiguePredictionActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    })
                    overridePendingTransition(0, 0)
                    true
                }
                R.id.nav_profile -> {
                    startActivity(Intent(this, UserHomeActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    })
                    overridePendingTransition(0, 0)
                    true
                }
                else -> false
            }
        }
    }
}
