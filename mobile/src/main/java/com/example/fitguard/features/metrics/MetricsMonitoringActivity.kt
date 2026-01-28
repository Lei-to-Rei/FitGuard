package com.example.fitguard.features.metrics

import android.content.*
import android.os.Bundle
import android.view.*
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.fitguard.R
import com.example.fitguard.databinding.ActivityMetricsMonitoringBinding
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class MetricsMonitoringActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMetricsMonitoringBinding
    private val receiver = HealthDataReceiver()
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

    private fun getTitle(type: String) = when(type) {
        "PPG" -> "📊 PPG"
        "SpO2" -> "🫁 SpO2"
        "HeartRate" -> "❤️ Heart Rate"
        "ECG" -> "📈 ECG"
        "SkinTemp" -> "🌡️ Skin Temp"
        "BIA" -> "⚖️ Body Comp"
        "Sweat" -> "💧 Sweat"
        else -> type
    }

    private fun formatData(type: String, d: JSONObject) = when(type) {
        "PPG" -> "Green: ${d.getInt("green")}\nIR: ${d.getInt("ir")}\nRed: ${d.getInt("red")}"
        "SpO2" -> "SpO2: ${d.getInt("spo2")}%\nHR: ${d.getInt("hr")} BPM"
        "HeartRate" -> "HR: ${d.getInt("hr")} BPM\nIBI: ${if(d.optString("ibi").length>2) "Available" else "None"}"
        "ECG" -> "ECG: ${String.format("%.2f", d.getDouble("ecg_mv"))} mV\nSeq: ${d.getInt("sequence")}\nLead: ${if(d.getInt("lead_off")==0) "On" else "Off"}"
        "SkinTemp" -> "Skin: ${if(d.has("obj")) String.format("%.1f", d.getDouble("obj"))+"°C" else "N/A"}\nAmbient: ${if(d.has("amb")) String.format("%.1f", d.getDouble("amb"))+"°C" else "N/A"}"
        "BIA" -> "BMR: ${String.format("%.0f", d.getDouble("bmr"))} kcal\nFat: ${String.format("%.1f", d.getDouble("fat_ratio"))}%\nMuscle: ${String.format("%.1f", d.getDouble("muscle"))} kg"
        "Sweat" -> "Loss: ${String.format("%.2f", d.getDouble("loss"))} mL"
        else -> "Unknown"
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(receiver) } catch (e: Exception) {}
    }

    override fun onSupportNavigateUp() = true.also { onBackPressed() }
}