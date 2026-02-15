package com.example.fitguard.features.workout

import android.content.*
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.fitguard.R
import com.example.fitguard.databinding.ActivityWorkoutControlBinding
import com.example.fitguard.services.WearableDataListenerService

class WorkoutHistoryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityWorkoutControlBinding
    private val viewModel: WorkoutControlViewModel by viewModels()

    private val ackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            viewModel.onWatchAck(
                ackSessionId = intent.getStringExtra("session_id") ?: "",
                status = intent.getStringExtra("status") ?: ""
            )
        }
    }

    private val stoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            viewModel.onWatchStopped(
                stoppedSessionId = intent.getStringExtra("session_id") ?: "",
                reason = intent.getStringExtra("reason") ?: "",
                sequenceCount = intent.getIntExtra("sequence_count", 0)
            )
        }
    }

    private val heartbeatReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            viewModel.onHeartbeat(
                hbSessionId = intent.getStringExtra("session_id") ?: "",
                sequenceCount = intent.getIntExtra("sequence_count", 0),
                elapsedS = intent.getIntExtra("elapsed_s", 0)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWorkoutControlBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = "Workout Control"
            setDisplayHomeAsUpEnabled(true)
        }

        setupActivityTypeSelection()
        setupStartStopButton()
        observeViewModel()
        registerReceivers()
    }

    private fun setupActivityTypeSelection() {
        binding.rgActivityType.setOnCheckedChangeListener { _, checkedId ->
            val isOther = checkedId == R.id.rbOther
            binding.tilCustomActivity.visibility = if (isOther) View.VISIBLE else View.GONE

            val type = when (checkedId) {
                R.id.rbWalking -> "Walking"
                R.id.rbRunning -> "Running"
                R.id.rbCycling -> "Cycling"
                R.id.rbOther -> binding.etCustomActivity.text?.toString()?.ifBlank { "Other" } ?: "Other"
                else -> "Walking"
            }
            viewModel.setActivityType(type)
        }
    }

    private fun setupStartStopButton() {
        binding.btnStartStop.setOnClickListener {
            when (viewModel.state.value) {
                WorkoutControlViewModel.SessionState.IDLE -> {
                    val type = getSelectedActivityType()
                    viewModel.startSession(type)
                }
                WorkoutControlViewModel.SessionState.ACTIVE -> {
                    viewModel.stopSession()
                }
                else -> {} // CONNECTING or STOPPING - ignore
            }
        }
    }

    private fun getSelectedActivityType(): String {
        return when (binding.rgActivityType.checkedRadioButtonId) {
            R.id.rbWalking -> "Walking"
            R.id.rbRunning -> "Running"
            R.id.rbCycling -> "Cycling"
            R.id.rbOther -> binding.etCustomActivity.text?.toString()?.ifBlank { "Other" } ?: "Other"
            else -> "Walking"
        }
    }

    private fun observeViewModel() {
        viewModel.state.observe(this) { state ->
            when (state) {
                WorkoutControlViewModel.SessionState.IDLE -> {
                    binding.btnStartStop.text = "Start Session"
                    binding.btnStartStop.isEnabled = true
                    binding.btnStartStop.setBackgroundColor(getColor(R.color.blue_primary))
                    binding.tvSessionStatus.text = "Idle"
                    binding.rgActivityType.isEnabled = true
                    setRadioGroupEnabled(true)
                }
                WorkoutControlViewModel.SessionState.CONNECTING -> {
                    binding.btnStartStop.text = "Connecting..."
                    binding.btnStartStop.isEnabled = false
                    binding.tvSessionStatus.text = "Connecting to watch..."
                    setRadioGroupEnabled(false)
                }
                WorkoutControlViewModel.SessionState.ACTIVE -> {
                    binding.btnStartStop.text = "Stop Session"
                    binding.btnStartStop.isEnabled = true
                    binding.btnStartStop.setBackgroundColor(getColor(R.color.red_stop))
                    binding.tvSessionStatus.text = "Active - ${viewModel.activityType.value}"
                    setRadioGroupEnabled(false)
                }
                WorkoutControlViewModel.SessionState.STOPPING -> {
                    binding.btnStartStop.text = "Stopping..."
                    binding.btnStartStop.isEnabled = false
                    binding.tvSessionStatus.text = "Stopping session..."
                }
                null -> {}
            }
        }

        viewModel.elapsedSeconds.observe(this) { seconds ->
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            val s = seconds % 60
            binding.tvElapsedTime.text = String.format("%02d:%02d:%02d", h, m, s)
        }

        viewModel.sequenceCount.observe(this) { count ->
            binding.tvSequenceCount.text = "Sequences: $count"
        }

        viewModel.error.observe(this) { errorMsg ->
            if (errorMsg != null) {
                binding.tvError.text = errorMsg
                binding.tvError.visibility = View.VISIBLE
            } else {
                binding.tvError.visibility = View.GONE
            }
        }
    }

    private fun setRadioGroupEnabled(enabled: Boolean) {
        for (i in 0 until binding.rgActivityType.childCount) {
            binding.rgActivityType.getChildAt(i).isEnabled = enabled
        }
        binding.etCustomActivity.isEnabled = enabled
    }

    private fun registerReceivers() {
        registerReceiver(ackReceiver, IntentFilter(WearableDataListenerService.ACTION_ACTIVITY_ACK), RECEIVER_NOT_EXPORTED)
        registerReceiver(stoppedReceiver, IntentFilter(WearableDataListenerService.ACTION_ACTIVITY_STOPPED), RECEIVER_NOT_EXPORTED)
        registerReceiver(heartbeatReceiver, IntentFilter(WearableDataListenerService.ACTION_ACTIVITY_HEARTBEAT), RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(ackReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(stoppedReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(heartbeatReceiver) } catch (_: Exception) {}
    }

    override fun onSupportNavigateUp() = true.also { onBackPressed() }
}
