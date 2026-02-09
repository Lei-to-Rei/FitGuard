package com.example.fitguard.features.metrics

import android.content.*
import android.os.Bundle
import android.view.*
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
    private val views = mutableMapOf<String, View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMetricsMonitoringBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = "Metrics Monitoring"
            setDisplayHomeAsUpEnabled(true)
        }

        binding.tvInstructions.text = "📱 Live Health Data\n\n• Start trackers on watch\n• Data appears here\n• Saved to Downloads/FitGuard_Data/"
        registerReceiver(receiver, IntentFilter("com.example.fitguard.HEALTH_DATA"), RECEIVER_NOT_EXPORTED)
        registerReceiver(hrvReceiver, IntentFilter(SequenceProcessor.ACTION_SEQUENCE_PROCESSED), RECEIVER_NOT_EXPORTED)
    }

    inner class HealthDataReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val type = intent?.getStringExtra("type") ?: return
            val json = JSONObject(intent.getStringExtra("data") ?: return)

            runOnUiThread {
                if (views.isEmpty()) binding.tvInstructions.visibility = View.GONE

                val v = views.getOrPut(type) {
                    LayoutInflater.from(this@MetricsMonitoringActivity)
                        .inflate(R.layout.item_metric_card, binding.metricsContainer, false).also {
                            binding.metricsContainer.addView(it)
                        }
                }

                v.findViewById<TextView>(R.id.tvMetricTitle).text = getTitle(type)
                v.findViewById<TextView>(R.id.tvMetricValue).text = formatData(type, json)
                v.findViewById<TextView>(R.id.tvMetricTimestamp).text =
                    "Updated: ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(json.getLong("timestamp")))}"

                v.animate().alpha(0.5f).setDuration(100).withEndAction {
                    v.animate().alpha(1f).setDuration(100).start()
                }
            }
        }
    }

    inner class HrvResultReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            intent ?: return
            runOnUiThread {
                if (views.isEmpty()) binding.tvInstructions.visibility = View.GONE

                val v = views.getOrPut("HRV") {
                    LayoutInflater.from(this@MetricsMonitoringActivity)
                        .inflate(R.layout.item_metric_card, binding.metricsContainer, false).also {
                            binding.metricsContainer.addView(it, 0)
                        }
                }

                val seqId = intent.getStringExtra("sequence_id") ?: "?"
                val hr = intent.getDoubleExtra("mean_hr_bpm", 0.0)
                val sdnn = intent.getDoubleExtra("sdnn_ms", 0.0)
                val rmssd = intent.getDoubleExtra("rmssd_ms", 0.0)
                val pnn20 = intent.getDoubleExtra("pnn20_pct", 0.0)
                val pnn50 = intent.getDoubleExtra("pnn50_pct", 0.0)
                val sdsd = intent.getDoubleExtra("sdsd_ms", 0.0)
                val peaks = intent.getIntExtra("peaks_detected", 0)
                val nn = intent.getIntExtra("nn_intervals_used", 0)
                val dur = intent.getDoubleExtra("duration_seconds", 0.0)
                val steps = intent.getIntExtra("total_steps", -1)
                val cadence = intent.getDoubleExtra("cadence_spm", -1.0)
                val meanAccelMag = intent.getDoubleExtra("mean_accel_mag", -1.0)
                val accelVar = intent.getDoubleExtra("accel_variance", -1.0)
                val peakAccelMag = intent.getDoubleExtra("peak_accel_mag", -1.0)

                val accelText = if (steps >= 0) {
                    "\n--- Activity ---\n" +
                    "Steps: $steps | Cadence: ${String.format("%.1f", cadence)} spm\n" +
                    "Mean Mag: ${String.format("%.2f", meanAccelMag)}\n" +
                    "Variance: ${String.format("%.4f", accelVar)}\n" +
                    "Peak Mag: ${String.format("%.2f", peakAccelMag)}"
                } else ""

                v.findViewById<TextView>(R.id.tvMetricTitle).text = "HRV Analysis"
                v.findViewById<TextView>(R.id.tvMetricValue).text =
                    "Mean HR: ${String.format("%.1f", hr)} BPM\n" +
                    "SDNN: ${String.format("%.2f", sdnn)} ms\n" +
                    "RMSSD: ${String.format("%.2f", rmssd)} ms\n" +
                    "pNN20: ${String.format("%.1f", pnn20)}%\n" +
                    "pNN50: ${String.format("%.1f", pnn50)}%\n" +
                    "SDSD: ${String.format("%.2f", sdsd)} ms\n" +
                    "Peaks: $peaks | NN: $nn | ${String.format("%.0f", dur)}s" +
                    accelText
                v.findViewById<TextView>(R.id.tvMetricTimestamp).text =
                    "Processed: ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}"

                v.animate().alpha(0.5f).setDuration(100).withEndAction {
                    v.animate().alpha(1f).setDuration(100).start()
                }
            }
        }
    }

    private fun getTitle(type: String) = when(type) {
        "PPG" -> "📊 PPG"
        "SpO2" -> "🫁 SpO2"
        "HeartRate" -> "❤️ Heart Rate"
        "SkinTemp" -> "🌡️ Skin Temp"
        else -> type
    }

    private fun formatData(type: String, d: JSONObject) = when(type) {
        "PPG" -> "Green: ${d.getInt("green")}\nIR: ${d.getInt("ir")}\nRed: ${d.getInt("red")}"
        "SpO2" -> "SpO2: ${d.getInt("spo2")}%\nHR: ${d.getInt("hr")} BPM"
        "HeartRate" -> "HR: ${d.getInt("hr")} BPM\nIBI: ${if(d.optString("ibi").length>2) "Available" else "None"}"
        "SkinTemp" -> "Skin: ${if(d.has("obj")) String.format("%.1f", d.getDouble("obj"))+"°C" else "N/A"}\nAmbient: ${if(d.has("amb")) String.format("%.1f", d.getDouble("amb"))+"°C" else "N/A"}"
        else -> "Unknown"
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(receiver) } catch (e: Exception) {}
        try { unregisterReceiver(hrvReceiver) } catch (e: Exception) {}
    }

    override fun onSupportNavigateUp() = true.also { onBackPressed() }
}