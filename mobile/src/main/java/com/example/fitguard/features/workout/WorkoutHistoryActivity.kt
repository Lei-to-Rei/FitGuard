package com.example.fitguard.features.workout

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.SeekBar
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.fitguard.R
import com.example.fitguard.databinding.ActivityWorkoutControlBinding
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject

class WorkoutHistoryActivity : AppCompatActivity(), MessageClient.OnMessageReceivedListener {
    private lateinit var binding: ActivityWorkoutControlBinding
    private val viewModel: WorkoutControlViewModel by viewModels()

    companion object {
        private const val TAG = "WorkoutHistoryActivity"
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
        setupRpeIntervalSlider()
        observeViewModel()

        Wearable.getMessageClient(this).addListener(this)
    }

    override fun onMessageReceived(event: MessageEvent) {
        val json = try {
            JSONObject(String(event.data, Charsets.UTF_8))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse message on ${event.path}: ${e.message}")
            return
        }

        when (event.path) {
            "/fitguard/activity/ack" -> runOnUiThread {
                viewModel.onWatchAck(
                    ackSessionId = json.optString("session_id", ""),
                    status = json.optString("status", "")
                )
            }
            "/fitguard/activity/stopped" -> runOnUiThread {
                viewModel.onWatchStopped(
                    stoppedSessionId = json.optString("session_id", ""),
                    reason = json.optString("reason", ""),
                    sequenceCount = json.optInt("sequence_count", 0)
                )
            }
            "/fitguard/activity/heartbeat" -> runOnUiThread {
                viewModel.onHeartbeat(
                    hbSessionId = json.optString("session_id", ""),
                    sequenceCount = json.optInt("sequence_count", 0),
                    elapsedS = json.optInt("elapsed_s", 0)
                )
            }
            "/fitguard/activity/rpe" -> runOnUiThread {
                viewModel.onRpeReceived(
                    sessionId = json.optString("session_id", ""),
                    rpeValue = json.optInt("rpe_value", -1)
                )
            }
        }
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

    private fun setupRpeIntervalSlider() {
        binding.seekRpeInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvRpeIntervalValue.text = "$progress min"
                if (fromUser) {
                    viewModel.setRpeInterval(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
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
                    binding.seekRpeInterval.isEnabled = true
                }
                WorkoutControlViewModel.SessionState.CONNECTING -> {
                    binding.btnStartStop.text = "Connecting..."
                    binding.btnStartStop.isEnabled = false
                    binding.tvSessionStatus.text = "Connecting to watch..."
                    setRadioGroupEnabled(false)
                    binding.seekRpeInterval.isEnabled = false
                }
                WorkoutControlViewModel.SessionState.ACTIVE -> {
                    binding.btnStartStop.text = "Stop Session"
                    binding.btnStartStop.isEnabled = true
                    binding.btnStartStop.setBackgroundColor(getColor(R.color.red_stop))
                    binding.tvSessionStatus.text = "Active - ${viewModel.activityType.value}"
                    setRadioGroupEnabled(false)
                    binding.seekRpeInterval.isEnabled = false
                }
                WorkoutControlViewModel.SessionState.STOPPING -> {
                    binding.btnStartStop.text = "Stopping..."
                    binding.btnStartStop.isEnabled = false
                    binding.tvSessionStatus.text = "Stopping session..."
                    binding.seekRpeInterval.isEnabled = false
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

        viewModel.lastRpe.observe(this) { rpe ->
            binding.tvLastRpe.text = if (rpe >= 0) "Last RPE: $rpe" else "Last RPE: --"
        }

        viewModel.rpeIntervalMinutes.observe(this) { minutes ->
            binding.seekRpeInterval.progress = minutes
            binding.tvRpeIntervalValue.text = "$minutes min"
        }

        viewModel.activityType.observe(this) { type ->
            val radioId = when (type) {
                "Walking" -> R.id.rbWalking
                "Running" -> R.id.rbRunning
                "Cycling" -> R.id.rbCycling
                else -> R.id.rbOther
            }
            if (binding.rgActivityType.checkedRadioButtonId != radioId) {
                binding.rgActivityType.check(radioId)
            }
            if (radioId == R.id.rbOther && type != "Other") {
                binding.etCustomActivity.setText(type)
            }
        }
    }

    private fun setRadioGroupEnabled(enabled: Boolean) {
        for (i in 0 until binding.rgActivityType.childCount) {
            binding.rgActivityType.getChildAt(i).isEnabled = enabled
        }
        binding.etCustomActivity.isEnabled = enabled
    }

    override fun onDestroy() {
        Wearable.getMessageClient(this).removeListener(this)
        super.onDestroy()
    }

    override fun onSupportNavigateUp() = true.also { onBackPressed() }
}
