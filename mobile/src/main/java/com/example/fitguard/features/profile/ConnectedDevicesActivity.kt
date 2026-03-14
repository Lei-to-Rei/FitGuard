package com.example.fitguard.features.profile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.fitguard.databinding.ActivityConnectedDevicesBinding
import com.example.fitguard.services.WearableDataListenerService

class ConnectedDevicesActivity : AppCompatActivity() {
    private lateinit var binding: ActivityConnectedDevicesBinding
    private val viewModel: ConnectedDevicesViewModel by viewModels()

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val nodeId = intent.getStringExtra("node_id") ?: return
            val percent = intent.getIntExtra("battery_percent", -1)
            if (percent >= 0) {
                viewModel.onWatchBatteryResponse(nodeId, percent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectedDevicesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.btnAddDevice.setOnClickListener {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            Toast.makeText(this, "Pair your Samsung Watch 7 or above", Toast.LENGTH_SHORT).show()
        }

        // Phone is always "Connected"
        binding.tvPhoneStatus.text = "Connected"
        binding.tvPhoneStatus.setTextColor(0xFF6EDB34.toInt())

        observeViewModel()

        registerReceiver(
            batteryReceiver,
            IntentFilter(WearableDataListenerService.ACTION_WATCH_BATTERY_RESPONSE),
            Context.RECEIVER_NOT_EXPORTED
        )
    }

    private fun observeViewModel() {
        viewModel.phoneName.observe(this) { name ->
            binding.tvPhoneName.text = name
        }

        viewModel.phoneBattery.observe(this) { percent ->
            binding.tvPhoneBattery.text = "${percent}% battery"
        }

        viewModel.watchDevices.observe(this) { devices ->
            if (devices.isEmpty()) {
                binding.deviceWatch.visibility = View.GONE
            } else {
                binding.deviceWatch.visibility = View.VISIBLE
                val watch = devices.first()
                binding.tvWatchName.text = watch.displayName
                if (watch.isConnected) {
                    binding.tvWatchStatus.text = "Connected"
                    binding.tvWatchStatus.setTextColor(0xFF6EDB34.toInt())
                } else {
                    binding.tvWatchStatus.text = "Disconnected"
                    binding.tvWatchStatus.setTextColor(0xFF999999.toInt())
                }
                binding.tvWatchBattery.text = if (watch.batteryPercent != null) {
                    "${watch.batteryPercent}% battery"
                } else {
                    "--"
                }
            }
        }

        viewModel.lastSyncTime.observe(this) { timestamp ->
            val text = formatRelativeTime(timestamp)
            binding.tvPhoneSync.text = "Last sync: $text"
            binding.tvWatchSync.text = "Last sync: $text"
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.startPolling()
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopPolling()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(batteryReceiver)
    }

    private fun formatRelativeTime(timestamp: Long): String {
        if (timestamp == 0L) return "Never"
        val diffMs = System.currentTimeMillis() - timestamp
        val diffMin = diffMs / 60_000
        return when {
            diffMin < 1 -> "Just now"
            diffMin < 60 -> "$diffMin min ago"
            else -> "${diffMin / 60} hr ago"
        }
    }
}
