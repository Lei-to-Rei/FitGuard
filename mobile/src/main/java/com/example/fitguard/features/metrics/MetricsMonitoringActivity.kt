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
                val pnn50 = intent.getDoubleExtra("pnn50_pct", 0.0)
                val lfHf = intent.getDoubleExtra("lf_hf_ratio", 0.0)
                val spo2 = intent.getDoubleExtra("spo2_mean_pct", 0.0)
                val skinTemp = intent.getDoubleExtra("skin_temp_obj", 0.0)
                val steps = intent.getIntExtra("total_steps", 0)
                val cadence = intent.getDoubleExtra("cadence_spm", 0.0)

                v.findViewById<TextView>(R.id.tvMetricTitle).text = "Feature Analysis"
                v.findViewById<TextView>(R.id.tvMetricValue).text =
                    "HR: ${String.format("%.1f", hr)} BPM\n" +
                    "SDNN: ${String.format("%.2f", sdnn)} ms | RMSSD: ${String.format("%.2f", rmssd)} ms\n" +
                    "pNN50: ${String.format("%.1f", pnn50)}% | LF/HF: ${String.format("%.2f", lfHf)}\n" +
                    "SpO2: ${String.format("%.1f", spo2)}%\n" +
                    "Skin: ${String.format("%.1f", skinTemp)}°C\n" +
                    "Steps: $steps | Cadence: ${String.format("%.1f", cadence)} spm"
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
        "SkinTemp" -> "🌡️ Skin Temp"
        else -> type
    }

    private fun formatData(type: String, d: JSONObject) = when(type) {
        "PPG" -> "Green: ${d.getInt("green")}\nIR: ${d.getInt("ir")}\nRed: ${d.getInt("red")}"
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
