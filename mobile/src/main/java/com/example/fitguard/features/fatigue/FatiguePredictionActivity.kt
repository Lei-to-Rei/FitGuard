package com.example.fitguard.features.fatigue

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import androidx.core.content.ContextCompat
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.fitguard.MainActivity
import com.example.fitguard.R
import com.example.fitguard.data.processing.SequenceProcessor
import com.example.fitguard.databinding.ActivityFatiguePredictionBinding
import com.example.fitguard.features.profile.UserHomeActivity
import com.example.fitguard.features.activitytracking.ActivityTrackingActivity

class FatiguePredictionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFatiguePredictionBinding
    private val viewModel: FatigueViewModel by viewModels()

    private val sequenceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val features = intent.getFloatArrayExtra("feature_array") ?: return
            viewModel.onNewFeatureWindow(features)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFatiguePredictionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupBottomNavigation()
        observeViewModel()

        val filter = IntentFilter(SequenceProcessor.ACTION_SEQUENCE_PROCESSED)
        ContextCompat.registerReceiver(this, sequenceReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            binding.bottomNavigation.selectedItemId = R.id.nav_health
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(sequenceReceiver)
    }

    private fun observeViewModel() {
        viewModel.isModelReady.observe(this) { ready ->
            if (!ready) {
                binding.tvFatigueStatus.text = "Loading model..."
                binding.tvFatigueStatus.setTextColor(Color.parseColor("#999999"))
            }
        }

        viewModel.currentResult.observe(this) { result ->
            if (result == null) return@observe
            val color = when (result.levelIndex) {
                0 -> Color.parseColor("#4CAF50")  // Mild - green
                1 -> Color.parseColor("#FF8C00")  // Moderate - orange
                2 -> Color.parseColor("#FF5722")  // High - deep orange
                3 -> Color.parseColor("#D32F2F")  // Critical - red
                else -> Color.parseColor("#FF8C00")
            }
            binding.tvFatigueStatus.text = result.level
            binding.tvFatigueStatus.setTextColor(color)
            binding.gaugeFatigue.setRecovery(
                result.percentDisplay.toFloat(),
                result.level,
                color
            )
        }

        viewModel.windowCount.observe(this) { count ->
            if (viewModel.currentResult.value == null && count > 0) {
                binding.tvHrvValue.text = "Collecting data ($count/5 windows)"
            }
        }

        viewModel.weeklyData.observe(this) { data ->
            if (data.isNotEmpty()) {
                binding.chartFatigueWeek.setData(data)
            }
        }
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
