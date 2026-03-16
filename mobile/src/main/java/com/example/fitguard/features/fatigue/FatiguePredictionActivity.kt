package com.example.fitguard.features.fatigue

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.fitguard.MainActivity
import com.example.fitguard.R
import com.example.fitguard.databinding.ActivityFatiguePredictionBinding
import com.example.fitguard.features.profile.UserHomeActivity
import com.example.fitguard.features.activitytracking.ActivityTrackingActivity

class FatiguePredictionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFatiguePredictionBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFatiguePredictionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupFeature()
        setupBottomNavigation()
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            binding.bottomNavigation.selectedItemId = R.id.nav_health
        }
    }

    private fun setupFeature() {
        binding.tvFeatureTitle.text = "Fatigue Prediction (CNN\u2013LSTM)"
        binding.tvFeatureDescription.text = """
            This AI-powered feature will:
            • Predict fatigue levels
            • Analyze activity patterns
            • Use CNN-LSTM neural networks
            • Provide early warnings
            • Suggest optimal training times
            • Prevent overtraining
        """.trimIndent()
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.selectedItemId = R.id.nav_health

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    startActivity(Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    })
                    overridePendingTransition(0, 0)
                    finish()
                    true
                }
                R.id.nav_activity -> {
                    startActivity(Intent(this, ActivityTrackingActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    })
                    overridePendingTransition(0, 0)
                    true
                }
                R.id.nav_stats -> {
                    Toast.makeText(this, "Stats coming soon", Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.nav_health -> true
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
