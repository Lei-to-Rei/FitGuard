package com.example.fitguard

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.fitguard.auth.LoginActivity
import com.example.fitguard.data.repository.AuthRepository
import com.example.fitguard.databinding.ActivityMainBinding
import com.example.fitguard.features.activity.ActivityTrackingActivity
import com.example.fitguard.features.fatigue.FatiguePredictionActivity
import com.example.fitguard.features.metrics.MetricsMonitoringActivity
import com.example.fitguard.features.nutrition.NutritionTrackingActivity
import com.example.fitguard.features.recovery.RecoveryProgressActivity
import com.example.fitguard.features.recommendations.RecoveryRecommendationsActivity
import com.example.fitguard.features.sleep.SleepStressActivity
import com.example.fitguard.features.workout.WorkoutHistoryActivity
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

        setupToolbar()
        setupDashboard()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "FitGuard"

        // Display user name
        val user = AuthRepository.currentUser
        val userName = user?.displayName ?: user?.email?.substringBefore("@") ?: "User"
        binding.tvWelcome.text = "Welcome, $userName!"
    }

    private fun setupDashboard() {
        val dashboardItems = listOf(
            DashboardItem(
                title = "Metrics Monitoring",
                description = "View heart rate, SpO2, and vital signs",
                icon = R.drawable.ic_metrics,
                activityClass = MetricsMonitoringActivity::class.java
            ),
            DashboardItem(
                title = "GPS-Based Activity Tracking",
                description = "Track your runs, walks, and outdoor activities",
                icon = R.drawable.ic_activity,
                activityClass = ActivityTrackingActivity::class.java
            ),
            DashboardItem(
                title = "Workout History & Insights",
                description = "Review past workouts and performance trends",
                icon = R.drawable.ic_workout,
                activityClass = WorkoutHistoryActivity::class.java
            ),
            DashboardItem(
                title = "Sleep & Stress Tracking",
                description = "Monitor your sleep quality and stress levels",
                icon = R.drawable.ic_sleep,
                activityClass = SleepStressActivity::class.java
            ),
            DashboardItem(
                title = "Nutrition Tracking",
                description = "Log meals and track your daily nutrition",
                icon = R.drawable.ic_nutrition,
                activityClass = NutritionTrackingActivity::class.java
            ),
            DashboardItem(
                title = "Recovery Progress Tracking",
                description = "Monitor recovery metrics and readiness",
                icon = R.drawable.ic_recovery,
                activityClass = RecoveryProgressActivity::class.java
            ),
            DashboardItem(
                title = "Fatigue Prediction (CNN–LSTM)",
                description = "AI-powered fatigue analysis and predictions",
                icon = R.drawable.ic_fatigue,
                activityClass = FatiguePredictionActivity::class.java
            ),
            DashboardItem(
                title = "Recovery Recommendations",
                description = "Personalized recovery tips and guidance",
                icon = R.drawable.ic_recommendations,
                activityClass = RecoveryRecommendationsActivity::class.java
            )
        )

        dashboardAdapter = DashboardAdapter(dashboardItems) { item ->
            startActivity(Intent(this, item.activityClass))
        }

        binding.rvDashboard.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = dashboardAdapter
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_logout -> {
                AuthRepository.signOut()
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}