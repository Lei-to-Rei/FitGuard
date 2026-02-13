package com.example.fitguard.features.sleep

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.fitguard.data.processing.SequenceProcessor
import com.example.fitguard.databinding.ActivitySleepStressBinding
import java.text.SimpleDateFormat
import java.util.*

class SleepStressActivity : AppCompatActivity() {

    companion object {
        const val ACTION_SLEEP_STRESS_STATUS = "com.example.fitguard.SLEEP_STRESS_STATUS"
    }

    private lateinit var binding: ActivitySleepStressBinding
    private val viewModel: SleepStressViewModel by viewModels()
    private val sequenceReceiver = SequenceProcessedReceiver()
    private val watchStatusReceiver = WatchStatusReceiver()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySleepStressBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = "Sleep & Stress"
            setDisplayHomeAsUpEnabled(true)
        }

        setupToggles()
        observeViewModel()

        registerReceiver(
            sequenceReceiver,
            IntentFilter(SequenceProcessor.ACTION_SEQUENCE_PROCESSED),
            RECEIVER_NOT_EXPORTED
        )
        registerReceiver(
            watchStatusReceiver,
            IntentFilter(ACTION_SLEEP_STRESS_STATUS),
            RECEIVER_NOT_EXPORTED
        )
    }

    private fun setupToggles() {
        binding.switchSleep.setOnCheckedChangeListener { _, isChecked ->
            viewModel.toggleSleepMonitoring(isChecked)
        }
        binding.switchStress.setOnCheckedChangeListener { _, isChecked ->
            viewModel.toggleStressMonitoring(isChecked)
        }
    }

    private fun observeViewModel() {
        viewModel.sleepMonitoringEnabled.observe(this) { enabled ->
            // Sync switch state without re-triggering listener
            if (binding.switchSleep.isChecked != enabled) {
                binding.switchSleep.isChecked = enabled
            }
            binding.tvSleepStatus.text = if (enabled) "Waiting for data..." else "Off"
            if (!enabled) {
                binding.cardSleepDetails.visibility = View.GONE
            }
        }

        viewModel.stressMonitoringEnabled.observe(this) { enabled ->
            if (binding.switchStress.isChecked != enabled) {
                binding.switchStress.isChecked = enabled
            }
            binding.tvStressStatus.text = if (enabled) "Waiting for data..." else "Off"
            if (!enabled) {
                binding.cardStressDetails.visibility = View.GONE
            }
        }

        viewModel.watchStatus.observe(this) { status ->
            updateWatchStatusUI(status)
        }

        viewModel.sleepResult.observe(this) { result ->
            binding.tvInstructions.visibility = View.GONE
            binding.cardSleepDetails.visibility = View.VISIBLE

            val stateText = when (result.state) {
                SleepState.AWAKE -> "Awake"
                SleepState.LIGHT_SLEEP -> "Light Sleep"
                SleepState.DEEP_SLEEP -> "Deep Sleep"
                SleepState.UNKNOWN -> "Unknown"
            }

            val stateColor = when (result.state) {
                SleepState.AWAKE -> Color.parseColor("#4CAF50")       // green
                SleepState.LIGHT_SLEEP -> Color.parseColor("#2196F3") // blue
                SleepState.DEEP_SLEEP -> Color.parseColor("#3F51B5")  // indigo
                SleepState.UNKNOWN -> Color.GRAY
            }

            binding.tvSleepState.text = stateText
            binding.tvSleepState.setTextColor(stateColor)
            binding.tvSleepStatus.text = stateText

            binding.tvSleepConfidence.text =
                "Confidence: ${String.format("%.0f", result.confidence * 100)}%"
            binding.tvSleepHr.text =
                "Heart Rate: ${String.format("%.1f", result.hr)} BPM"
            binding.tvSleepAccelVar.text = if (result.accelVariance >= 0) {
                "Accel Variance: ${String.format("%.4f", result.accelVariance)}"
            } else {
                "Accel Variance: N/A"
            }
            binding.tvSleepTimestamp.text =
                "Updated: ${timeFormat.format(Date(result.timestamp))}"
        }

        viewModel.stressResult.observe(this) { result ->
            binding.tvInstructions.visibility = View.GONE
            binding.cardStressDetails.visibility = View.VISIBLE

            val scoreColor = when (result.level) {
                StressLevel.LOW -> Color.parseColor("#4CAF50")        // green
                StressLevel.MODERATE -> Color.parseColor("#FFC107")   // amber
                StressLevel.HIGH -> Color.parseColor("#FF9800")       // orange
                StressLevel.VERY_HIGH -> Color.parseColor("#F44336")  // red
            }

            val levelText = when (result.level) {
                StressLevel.LOW -> "Low"
                StressLevel.MODERATE -> "Moderate"
                StressLevel.HIGH -> "High"
                StressLevel.VERY_HIGH -> "Very High"
            }

            binding.tvStressScore.text = "${result.score}"
            binding.tvStressScore.setTextColor(scoreColor)
            binding.tvStressLevel.text = levelText
            binding.tvStressLevel.setTextColor(scoreColor)
            binding.tvStressStatus.text = "Score: ${result.score}/100 ($levelText)"

            binding.tvStressRmssd.text =
                "RMSSD: ${String.format("%.2f", result.rmssd)} ms"
            binding.tvStressSdnn.text =
                "SDNN: ${String.format("%.2f", result.sdnn)} ms"
            binding.tvStressHr.text =
                "Heart Rate: ${String.format("%.1f", result.hr)} BPM"
            binding.tvStressTimestamp.text =
                "Updated: ${timeFormat.format(Date(result.timestamp))}"
        }
    }

    private fun updateWatchStatusUI(status: SleepStressViewModel.WatchStatus) {
        val tvStatus = binding.tvWatchStatus
        when (status) {
            SleepStressViewModel.WatchStatus.IDLE -> {
                tvStatus.visibility = View.GONE
            }
            SleepStressViewModel.WatchStatus.STARTING -> {
                tvStatus.visibility = View.VISIBLE
                tvStatus.text = "Connecting to watch..."
                tvStatus.setTextColor(Color.GRAY)
            }
            SleepStressViewModel.WatchStatus.STARTED -> {
                tvStatus.visibility = View.VISIBLE
                tvStatus.text = "Watch sensors active"
                tvStatus.setTextColor(Color.parseColor("#4CAF50"))
            }
            SleepStressViewModel.WatchStatus.STOPPED -> {
                tvStatus.visibility = View.VISIBLE
                tvStatus.text = "Watch sensors stopped"
                tvStatus.setTextColor(Color.GRAY)
            }
            SleepStressViewModel.WatchStatus.REJECTED_ACTIVITY -> {
                tvStatus.visibility = View.VISIBLE
                tvStatus.text = "Watch busy: Activity sequence running.\nStop it first."
                tvStatus.setTextColor(Color.parseColor("#FF9800"))
            }
            SleepStressViewModel.WatchStatus.REJECTED_OPEN_WATCH -> {
                tvStatus.visibility = View.VISIBLE
                tvStatus.text = "Open FitGuard on watch first to initialize sensors."
                tvStatus.setTextColor(Color.parseColor("#FF9800"))
            }
            SleepStressViewModel.WatchStatus.NO_WATCH -> {
                tvStatus.visibility = View.VISIBLE
                tvStatus.text = "No watch connected. Check Bluetooth."
                tvStatus.setTextColor(Color.parseColor("#F44336"))
            }
            SleepStressViewModel.WatchStatus.ERROR -> {
                tvStatus.visibility = View.VISIBLE
                tvStatus.text = "Watch communication error."
                tvStatus.setTextColor(Color.parseColor("#F44336"))
            }
        }
    }

    inner class SequenceProcessedReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return

            val rmssd = intent.getDoubleExtra("rmssd_ms", 0.0)
            val sdnn = intent.getDoubleExtra("sdnn_ms", 0.0)
            val meanHr = intent.getDoubleExtra("mean_hr_bpm", 0.0)
            val accelVariance = intent.getDoubleExtra("accel_variance", -1.0)
            val hasAccelData = accelVariance >= 0

            viewModel.onSequenceProcessed(rmssd, sdnn, meanHr, accelVariance, hasAccelData)
        }
    }

    inner class WatchStatusReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            val status = intent.getStringExtra("status") ?: return
            val reason = intent.getStringExtra("reason") ?: ""
            viewModel.onWatchStatusReceived(status, reason)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(sequenceReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(watchStatusReceiver) } catch (_: Exception) {}
    }

    override fun onSupportNavigateUp() = true.also { onBackPressed() }
}
