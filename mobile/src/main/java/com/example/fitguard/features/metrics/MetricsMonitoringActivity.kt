package com.example.fitguard.features.metrics

import android.content.*
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.fitguard.R
import com.example.fitguard.data.processing.SequenceProcessor
import com.example.fitguard.databinding.ActivityMetricsMonitoringBinding
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class MetricsMonitoringActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMetricsMonitoringBinding
    private val receiver = HealthDataReceiver()
    private val hrvReceiver = HrvResultReceiver()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMetricsMonitoringBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        registerReceiver(receiver, IntentFilter("com.example.fitguard.HEALTH_DATA"), RECEIVER_NOT_EXPORTED)
        registerReceiver(hrvReceiver, IntentFilter(SequenceProcessor.ACTION_SEQUENCE_PROCESSED), RECEIVER_NOT_EXPORTED)
    }

    inner class HealthDataReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val type = intent?.getStringExtra("type") ?: return
            val json = JSONObject(intent.getStringExtra("data") ?: return)

            runOnUiThread {
                when (type) {
                    "PPG" -> {
                        // Could extract HR from PPG data in future
                    }
                }
            }
        }
    }

    inner class HrvResultReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            intent ?: return
            runOnUiThread {
                val hr = intent.getDoubleExtra("mean_hr_bpm", 0.0)
                val spo2 = intent.getDoubleExtra("spo2_mean_pct", 0.0)

                // Update heart rate
                binding.tvHeartRateValue.text = "${String.format("%.0f", hr)} bpm"

                // Update SpO2
                binding.tvSpO2Value.text = "${String.format("%.0f", spo2)}% SpO2"

                // Update heart rate chart with new data point
                // Charts use sample data from init; live data updates the value TextViews
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(receiver) } catch (e: Exception) {}
        try { unregisterReceiver(hrvReceiver) } catch (e: Exception) {}
    }
}
