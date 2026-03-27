package com.example.fitguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.fitguard.auth.LoginActivity
import com.example.fitguard.data.processing.CsvWriter
import com.example.fitguard.data.repository.AuthRepository
import com.example.fitguard.databinding.ActivityMainBinding
import com.example.fitguard.features.activitytracking.ActivityHistoryActivity
import com.example.fitguard.features.activitytracking.ActivityTrackingActivity
import com.example.fitguard.features.fatigue.FatiguePredictionActivity
import com.example.fitguard.features.metrics.MetricsMonitoringActivity
import com.example.fitguard.features.nutrition.NutritionTrackingActivity
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
        setupIndicatorDots()
        checkStoragePermission()

        binding.chartHeartRateMini.showGrid = false
        registerReceiver(healthReceiver, IntentFilter("com.example.fitguard.HEALTH_DATA"), RECEIVER_NOT_EXPORTED)
        loadHealthData()
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            binding.bottomNavigation.selectedItemId = R.id.nav_home
            loadHealthData()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
        try { unregisterReceiver(healthReceiver) } catch (_: Exception) {}
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

    private fun setupIndicatorDots() {
        // SpO2 dot will be positioned by updateSpO2Dot() once data loads

        // Position stress dot: 54/100 = 54%
        binding.dotStress.post {
            val parent = binding.dotStress.parent as android.view.View
            val barWidth = parent.width - binding.dotStress.width
            val stressFraction = 54f / 100f
            binding.dotStress.translationX = barWidth * stressFraction
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

                // Heart Rate
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
                        val chartPoints = readings.takeLast(12)
                        val lastHr = readings.last().toInt()
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
