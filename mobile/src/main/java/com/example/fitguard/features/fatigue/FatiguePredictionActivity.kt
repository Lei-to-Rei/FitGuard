package com.example.fitguard.features.fatigue

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.fitguard.MainActivity
import com.example.fitguard.R
import android.widget.TextView
import com.example.fitguard.data.processing.FatigueResult
import com.example.fitguard.data.processing.SequenceProcessor
import com.example.fitguard.databinding.ActivityFatiguePredictionBinding
import com.example.fitguard.features.profile.UserHomeActivity
import com.example.fitguard.features.activitytracking.ActivityTrackingActivity
import com.example.fitguard.ui.chart.SessionFatigueTrendChartView

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
        setupNoSessionOverlay()
        observeViewModel()

        val filter = IntentFilter(SequenceProcessor.ACTION_SEQUENCE_PROCESSED)
        ContextCompat.registerReceiver(this, sequenceReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            binding.bottomNavigation.selectedItemId = R.id.nav_health
        }
        viewModel.refreshSessionState()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(sequenceReceiver)
    }

    private fun setupNoSessionOverlay() {
        binding.btnStartSession.setOnClickListener {
            startActivity(Intent(this, ActivityTrackingActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
        }

        binding.btnSimulate.setOnClickListener {
            viewModel.resetForNewSession()
            binding.btnSimulate.isEnabled = false
            binding.btnSimulate.text = "Running..."
            lifecycleScope.launch(Dispatchers.IO) {
                val userId = FirebaseAuth.getInstance().currentUser?.uid ?: ""
                val result = SimulationRunner.run(this@FatiguePredictionActivity, userId)
                withContext(Dispatchers.Main) {
                    binding.btnSimulate.isEnabled = true
                    binding.btnSimulate.text = "Run Simulation"
                    when (result) {
                        is SimulationRunner.SimResult.Error ->
                            Toast.makeText(this@FatiguePredictionActivity, result.message, Toast.LENGTH_LONG).show()
                        is SimulationRunner.SimResult.Success ->
                            Toast.makeText(this@FatiguePredictionActivity, "Simulation queued ~${result.windowsQueued} windows", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun observeViewModel() {
        // Session-active gating
        viewModel.isSessionActive.observe(this) { active ->
            if (active) {
                binding.layoutNoSession.visibility = View.GONE
                binding.scrollContent.visibility = View.VISIBLE
            } else {
                binding.layoutNoSession.visibility = View.VISIBLE
                binding.scrollContent.visibility = View.GONE
            }
        }

        viewModel.isModelReady.observe(this) { ready ->
            if (!ready) {
                binding.tvFatigueStatus.text = "Loading model..."
                binding.tvFatigueStatus.setTextColor(Color.parseColor("#999999"))
            }
        }

        viewModel.windowCount.observe(this) { count ->
            // Show warmup progress when we don't have a prediction yet
            if (viewModel.currentResult.value == null && count < 5) {
                binding.tvFatigueStatus.text = "Collecting data... ($count/5)"
                binding.tvFatigueStatus.setTextColor(Color.parseColor("#999999"))
                binding.tvFatigueClassification.text = "--"
                binding.gaugeFatigue.setRecovery(0f, "...", Color.parseColor("#999999"))
            }
        }

        viewModel.currentResult.observe(this) { result ->
            if (result == null) {
                // Show warmup state
                val count = viewModel.windowCount.value ?: 0
                binding.tvFatigueStatus.text = if (count > 0) "Collecting data... ($count/5)" else "Waiting for data..."
                binding.tvFatigueStatus.setTextColor(Color.parseColor("#999999"))
                binding.tvFatigueClassification.text = "--"
                binding.gaugeFatigue.setRecovery(0f, "...", Color.parseColor("#999999"))
                return@observe
            }
            val color = when (result.levelIndex) {
                0 -> Color.parseColor("#4CAF50")  // Mild - green
                1 -> Color.parseColor("#FF8C00")  // Moderate - orange
                2 -> Color.parseColor("#FF5722")  // High - deep orange
                3 -> Color.parseColor("#D32F2F")  // Critical - red
                else -> Color.parseColor("#FF8C00")
            }
            binding.tvFatigueStatus.text = result.level
            binding.tvFatigueStatus.setTextColor(color)
            binding.tvFatigueClassification.text = String.format("%.2f", result.pHigh)
            binding.gaugeFatigue.setRecovery(
                result.percentDisplay.toFloat(),
                result.level,
                color
            )
        }

        // Session fatigue trend chart
        viewModel.sessionTrend.observe(this) { points ->
            if (points.isNotEmpty()) {
                val chartPoints = points.map {
                    SessionFatigueTrendChartView.TrendPoint(it.timeMs, it.fatiguePercent)
                }
                binding.chartSessionTrend.setData(chartPoints)
            }
        }

        // 5-minute prediction
        viewModel.predictionTrend.observe(this) { points ->
            val chartPoints = points.map {
                SessionFatigueTrendChartView.TrendPoint(it.timeMs, it.fatiguePercent)
            }
            binding.chartSessionTrend.setPrediction(chartPoints)
        }
    }

    private fun updateComparisonColumn(
        result: FatigueResult?,
        ready: Boolean,
        levelView: TextView,
        pHighView: TextView,
        statusView: TextView
    ) {
        if (!ready) {
            levelView.text = "N/A"
            levelView.setTextColor(Color.parseColor("#999999"))
            pHighView.text = "P(H): N/A"
            statusView.text = "Not available"
            statusView.setTextColor(Color.parseColor("#999999"))
            return
        }
        if (result == null) {
            levelView.text = "..."
            levelView.setTextColor(Color.parseColor("#999999"))
            pHighView.text = "P(H): ..."
            statusView.text = "Buffering"
            statusView.setTextColor(Color.parseColor("#999999"))
            return
        }
        val color = when (result.levelIndex) {
            0 -> Color.parseColor("#4CAF50")
            1 -> Color.parseColor("#FF8C00")
            2 -> Color.parseColor("#FF5722")
            3 -> Color.parseColor("#D32F2F")
            else -> Color.parseColor("#FF8C00")
        }
        levelView.text = result.level
        levelView.setTextColor(color)
        pHighView.text = "P(H): ${String.format("%.3f", result.pHigh)}"
        statusView.text = "${result.percentDisplay}%"
        statusView.setTextColor(color)
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
