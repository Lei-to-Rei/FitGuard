package com.example.fitguard

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.fitguard.auth.LoginActivity
import com.example.fitguard.data.repository.AuthRepository
import com.example.fitguard.databinding.ActivityMainBinding
import com.example.fitguard.features.activitytracking.ActivityHistoryActivity
import com.example.fitguard.features.activitytracking.ActivityTrackingActivity
import com.example.fitguard.features.fatigue.FatiguePredictionActivity
import com.example.fitguard.features.metrics.MetricsMonitoringActivity
import com.example.fitguard.features.nutrition.NutritionTrackingActivity
import com.example.fitguard.features.recovery.RecoveryProgressActivity
import com.example.fitguard.features.sleep.SleepStressActivity
import com.example.fitguard.features.profile.UserHomeActivity
import com.example.fitguard.ui.adapter.DashboardAdapter
import com.example.fitguard.ui.model.DashboardItem

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var dashboardAdapter: DashboardAdapter

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
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            binding.bottomNavigation.selectedItemId = R.id.nav_home
        }
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
                title = "Recovery Progress Tracking",
                description = "Track how close you are to recovery",
                icon = R.drawable.ic_dumbbell_ray,
                activityClass = RecoveryProgressActivity::class.java
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
        // Position SpO2 dot: 98% on 70-100 range = (98-70)/(100-70) = 93.3%
        binding.dotSpO2.post {
            val parent = binding.dotSpO2.parent as android.view.View
            val barWidth = parent.width - binding.dotSpO2.width
            val spo2Fraction = (98f - 70f) / (100f - 70f)
            binding.dotSpO2.translationX = barWidth * spo2Fraction
        }

        // Position stress dot: 54/100 = 54%
        binding.dotStress.post {
            val parent = binding.dotStress.parent as android.view.View
            val barWidth = parent.width - binding.dotStress.width
            val stressFraction = 54f / 100f
            binding.dotStress.translationX = barWidth * stressFraction
        }
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
