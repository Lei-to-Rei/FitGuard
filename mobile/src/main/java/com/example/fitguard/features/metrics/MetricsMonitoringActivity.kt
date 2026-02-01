package com.example.fitguard.features.metrics

import android.content.*
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.fitguard.R
import com.example.fitguard.data.analysis.PPGAnalyzer
import com.example.fitguard.data.local.HealthDataRepository
import com.example.fitguard.databinding.ActivityMetricsMonitoringBinding
import com.example.fitguard.services.WearableDataListenerService
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * Enhanced Metrics Monitoring Activity with PPG Analysis
 */
class MetricsMonitoringActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMetricsMonitoringBinding
    private val receiver = HealthDataReceiver()
    private val metricViews = mutableMapOf<String, View>()

    private lateinit var repository: HealthDataRepository
    private val ppgAnalyzer = PPGAnalyzer()

    // Real-time PPG buffer
    private val ppgBuffer = mutableListOf<PPGSample>()
    private val maxBufferSize = 500

    companion object {
        private const val TAG = "MetricsMonitoring"
    }

    data class PPGSample(
        val green: Int, val ir: Int, val red: Int, val timestamp: Long
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMetricsMonitoringBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = HealthDataRepository(this)
        setupToolbar()
        setupTabs()
        setupRealTimeView()

        registerReceiver(receiver, IntentFilter(WearableDataListenerService.ACTION_HEALTH_DATA), RECEIVER_NOT_EXPORTED)
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = "Health Metrics"
            setDisplayHomeAsUpEnabled(true)
        }
    }

    private fun setupTabs() {
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Live"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Analysis"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("History"))

        binding.tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                when (tab.position) {
                    0 -> showRealTimeView()
                    1 -> showAnalysisView()
                    2 -> showHistoryView()
                }
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
        })
    }

    private fun setupRealTimeView() {
        binding.tvInstructions.text = "📱 Live Health Data\n\n• Start trackers on watch\n• Data appears here in real-time\n• Switch tabs for analysis & history"
    }

    private fun showRealTimeView() {
        binding.scrollViewRealTime.visibility = View.VISIBLE
        binding.scrollViewAnalysis.visibility = View.GONE
        binding.scrollViewHistory.visibility = View.GONE
    }

    private fun showAnalysisView() {
        binding.scrollViewRealTime.visibility = View.GONE
        binding.scrollViewAnalysis.visibility = View.VISIBLE
        binding.scrollViewHistory.visibility = View.GONE
        loadAnalysis()
    }

    private fun showHistoryView() {
        binding.scrollViewRealTime.visibility = View.GONE
        binding.scrollViewAnalysis.visibility = View.GONE
        binding.scrollViewHistory.visibility = View.VISIBLE
        loadHistory()
    }

    private fun loadAnalysis() {
        lifecycleScope.launch {
            val today = repository.getTodayDateString()
            val ppgData = repository.loadPPGForAnalysis(today)

            binding.analysisContainer.removeAllViews()

            if (ppgData == null || ppgData.sampleCount < 100) {
                binding.analysisContainer.addView(createInfoCard(
                    "📊 Insufficient Data",
                    "Need at least 100 PPG samples.\nCurrent: ${ppgData?.sampleCount ?: 0}\n\nStart PPG tracking on your watch."
                ))
                return@launch
            }

            binding.analysisContainer.addView(createInfoCard("⏳ Analyzing...", "Processing ${ppgData.sampleCount} samples..."))

            try {
                val result = withContext(Dispatchers.Default) {
                    ppgAnalyzer.analyze(ppgData.greenSignal, ppgData.timestamps, ppgData.estimatedSampleRate)
                }
                displayAnalysisResults(result, ppgData)
            } catch (e: Exception) {
                Log.e(TAG, "Analysis failed", e)
                binding.analysisContainer.removeAllViews()
                binding.analysisContainer.addView(createInfoCard("❌ Error", "Analysis failed: ${e.message}"))
            }
        }
    }

    private fun displayAnalysisResults(result: PPGAnalyzer.AnalysisResult, ppgData: HealthDataRepository.PPGAnalysisData) {
        binding.analysisContainer.removeAllViews()

        // Summary
        binding.analysisContainer.addView(createAnalysisCard("📊 Summary", """
            Duration: ${String.format("%.1f", ppgData.durationSeconds)}s
            Samples: ${ppgData.sampleCount}
            Sample Rate: ${String.format("%.1f", ppgData.estimatedSampleRate)} Hz
            Peaks: ${result.peakCount}
            Quality: ${String.format("%.0f", result.signalQuality)}%
        """.trimIndent(), getQualityColor(result.signalQuality)))

        // Heart Rate
        binding.analysisContainer.addView(createAnalysisCard("❤️ Heart Rate", """
            Heart Rate: ${String.format("%.0f", result.heartRate)} BPM
            Mean RR: ${String.format("%.0f", result.meanRR)} ms
            Median RR: ${String.format("%.0f", result.medianRR)} ms
        """.trimIndent(), Color.parseColor("#E91E63")))

        // HRV Time Domain
        binding.analysisContainer.addView(createAnalysisCard("📈 HRV Time Domain", """
            SDNN: ${String.format("%.1f", result.sdnn)} ms
            RMSSD: ${String.format("%.1f", result.rmssd)} ms
            pNN50: ${String.format("%.1f", result.pnn50)}%
            SDSD: ${String.format("%.1f", result.sdsd)} ms
        """.trimIndent(), Color.parseColor("#4CAF50")))

        // HRV Frequency Domain
        if (result.lfPower != null) {
            binding.analysisContainer.addView(createAnalysisCard("🔊 HRV Frequency Domain", """
                LF Power: ${String.format("%.1f", result.lfPower)} ms²
                HF Power: ${String.format("%.1f", result.hfPower)} ms²
                LF/HF Ratio: ${String.format("%.2f", result.lfHfRatio)}
                Total Power: ${String.format("%.1f", result.totalPower)} ms²
            """.trimIndent(), Color.parseColor("#2196F3")))
        }

        // Respiratory
        result.respiratoryRate?.let {
            binding.analysisContainer.addView(createAnalysisCard("🫁 Respiratory", "Estimated: ${String.format("%.0f", it)} breaths/min", Color.parseColor("#00BCD4")))
        }

        // Add chart
        binding.analysisContainer.addView(createPPGChartCard(ppgData))

        // Add RR histogram
        if (result.rrIntervals.size > 10) {
            binding.analysisContainer.addView(createRRHistogramCard(result.rrIntervals))
        }
    }

    private fun loadHistory() {
        lifecycleScope.launch {
            val dates = repository.getAvailableDates()
            binding.historyContainer.removeAllViews()

            if (dates.isEmpty()) {
                binding.historyContainer.addView(createInfoCard("📅 No Data", "No health data recorded yet.\nStart tracking on your watch."))
                return@launch
            }

            // Date selector
            val spinner = Spinner(this@MetricsMonitoringActivity)
            spinner.adapter = ArrayAdapter(this@MetricsMonitoringActivity, android.R.layout.simple_spinner_dropdown_item, dates)
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    loadDataForDate(dates[pos])
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            binding.historyContainer.addView(spinner)

            if (dates.isNotEmpty()) loadDataForDate(dates[0])
        }
    }

    private fun loadDataForDate(date: String) {
        lifecycleScope.launch {
            val summary = repository.getDataSummary(date)

            // Remove previous data cards but keep spinner
            while (binding.historyContainer.childCount > 1) {
                binding.historyContainer.removeViewAt(1)
            }

            binding.historyContainer.addView(createInfoCard("📅 $date", "Total entries: ${summary.totalEntries}\n\n" +
                    summary.entryCounts.entries.joinToString("\n") { "${getTypeEmoji(it.key)} ${it.key}: ${it.value}" }))

            // Load and display each data type
            loadHeartRateChart(date)
            loadSpO2Chart(date)
            loadTempChart(date)
        }
    }

    private fun loadHeartRateChart(date: String) {
        lifecycleScope.launch {
            val data = repository.loadHeartRateData(date)
            if (data.isNotEmpty()) {
                binding.historyContainer.addView(createLineChartCard(
                    "❤️ Heart Rate Over Time",
                    data.map { it.timestamp to it.heartRate.toFloat() },
                    "BPM", Color.parseColor("#E91E63")
                ))
            }
        }
    }

    private fun loadSpO2Chart(date: String) {
        lifecycleScope.launch {
            val data = repository.loadSpO2Data(date)
            if (data.isNotEmpty()) {
                binding.historyContainer.addView(createLineChartCard(
                    "🫁 SpO2 Over Time",
                    data.map { it.timestamp to it.spo2.toFloat() },
                    "%", Color.parseColor("#2196F3")
                ))
            }
        }
    }

    private fun loadTempChart(date: String) {
        lifecycleScope.launch {
            val data = repository.loadSkinTempData(date)
            val validData = data.filter { it.objectTemp != null }
            if (validData.isNotEmpty()) {
                binding.historyContainer.addView(createLineChartCard(
                    "🌡️ Skin Temperature",
                    validData.map { it.timestamp to it.objectTemp!! },
                    "°C", Color.parseColor("#FF9800")
                ))
            }
        }
    }

    // ==================== UI HELPERS ====================

    private fun createInfoCard(title: String, content: String): View {
        return createAnalysisCard(title, content, Color.parseColor("#607D8B"))
    }

    private fun createAnalysisCard(title: String, content: String, accentColor: Int): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 24) }
            radius = 24f
            cardElevation = 8f
            setCardBackgroundColor(Color.WHITE)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val titleView = TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(accentColor)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        val contentView = TextView(this).apply {
            text = content
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, 16, 0, 0)
        }

        container.addView(titleView)
        container.addView(contentView)
        card.addView(container)
        return card
    }

    private fun createPPGChartCard(ppgData: HealthDataRepository.PPGAnalysisData): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 400
            ).apply { setMargins(0, 0, 0, 24) }
            radius = 24f
            cardElevation = 8f
        }

        val chartView = PPGChartView(this).apply {
            setWindowSize(200) // Show last 200 samples (~8 seconds at 25Hz)
            setData(ppgData.greenSignal) // Pass all data, chart will show most recent
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        container.addView(TextView(this).apply {
            text = "📈 PPG Signal (Green Channel)"
            textSize = 16f
            setTextColor(Color.parseColor("#4CAF50"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        container.addView(chartView.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 300
            )
        })

        card.addView(container)
        return card
    }

    private fun createRRHistogramCard(rrIntervals: List<Double>): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 350
            ).apply { setMargins(0, 0, 0, 24) }
            radius = 24f
            cardElevation = 8f
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        container.addView(TextView(this).apply {
            text = "📊 RR Interval Distribution"
            textSize = 16f
            setTextColor(Color.parseColor("#9C27B0"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        val histView = HistogramView(this).apply {
            setData(rrIntervals, "ms")
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 250
            )
        }

        container.addView(histView)
        card.addView(container)
        return card
    }

    private fun createLineChartCard(title: String, data: List<Pair<Long, Float>>, unit: String, color: Int): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 350
            ).apply { setMargins(0, 0, 0, 24) }
            radius = 24f
            cardElevation = 8f
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        container.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(color)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        val chartView = LineChartView(this).apply {
            setData(data, unit, color)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 250
            )
        }

        container.addView(chartView)
        card.addView(container)
        return card
    }

    private fun getQualityColor(quality: Double): Int {
        return when {
            quality >= 80 -> Color.parseColor("#4CAF50") // Green
            quality >= 60 -> Color.parseColor("#FFC107") // Yellow
            quality >= 40 -> Color.parseColor("#FF9800") // Orange
            else -> Color.parseColor("#F44336") // Red
        }
    }

    private fun getTypeEmoji(type: String): String {
        return when (type) {
            "PPG" -> "📊"
            "HeartRate" -> "❤️"
            "SpO2" -> "🫁"
            "ECG" -> "📈"
            "SkinTemp" -> "🌡️"
            "BIA" -> "⚖️"
            "Sweat" -> "💧"
            else -> "📱"
        }
    }

    // ==================== BROADCAST RECEIVER ====================

    inner class HealthDataReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val type = intent?.getStringExtra("type") ?: return
            val data = intent.getStringExtra("data") ?: return

            try {
                val json = JSONObject(data)

                // Buffer PPG for real-time analysis
                if (type == "PPG") {
                    ppgBuffer.add(PPGSample(
                        json.optInt("green", 0),
                        json.optInt("ir", 0),
                        json.optInt("red", 0),
                        json.optLong("timestamp", System.currentTimeMillis())
                    ))
                    if (ppgBuffer.size > maxBufferSize) ppgBuffer.removeAt(0)

                    // Run quick analysis every 100 samples
                    if (ppgBuffer.size % 100 == 0 && ppgBuffer.size >= 100) {
                        runQuickPPGAnalysis()
                    }
                }

                runOnUiThread { updateMetricCard(type, json) }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing data: ${e.message}")
            }
        }
    }

    private fun runQuickPPGAnalysis() {
        lifecycleScope.launch(Dispatchers.Default) {
            val signal = ppgBuffer.map { it.green.toDouble() }.toDoubleArray()
            val timestamps = ppgBuffer.map { it.timestamp }.toLongArray()

            try {
                val result = ppgAnalyzer.quickAnalyze(signal, timestamps)

                withContext(Dispatchers.Main) {
                    updateOrCreateAnalysisCard(
                        "🔬 Live Analysis",
                        """
                            HR: ${String.format("%.0f", result.heartRate)} BPM
                            HRV (SDNN): ${String.format("%.1f", result.sdnn)} ms
                            Quality: ${String.format("%.0f", result.signalQuality)}%
                            Peaks: ${result.peakCount}
                        """.trimIndent()
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Quick analysis error: ${e.message}")
            }
        }
    }

    private fun updateOrCreateAnalysisCard(title: String, content: String) {
        val existingCard = metricViews["live_analysis"]

        if (existingCard != null) {
            existingCard.findViewById<TextView>(R.id.tvMetricValue)?.text = content
        } else {
            val card = layoutInflater.inflate(R.layout.item_metric_card, binding.metricsContainer, false)
            card.findViewById<TextView>(R.id.tvMetricTitle)?.text = title
            card.findViewById<TextView>(R.id.tvMetricValue)?.text = content
            card.findViewById<TextView>(R.id.tvMetricTimestamp)?.text = "Real-time analysis"

            binding.metricsContainer.addView(card, 0)
            metricViews["live_analysis"] = card
        }
    }

    private fun updateMetricCard(type: String, json: JSONObject) {
        binding.tvInstructions.visibility = View.GONE

        val card = metricViews.getOrPut(type) {
            layoutInflater.inflate(R.layout.item_metric_card, binding.metricsContainer, false).also {
                binding.metricsContainer.addView(it)
            }
        }

        card.findViewById<TextView>(R.id.tvMetricTitle)?.text = "${getTypeEmoji(type)} $type"
        card.findViewById<TextView>(R.id.tvMetricValue)?.text = formatMetricData(type, json)
        card.findViewById<TextView>(R.id.tvMetricTimestamp)?.text =
            "Updated: ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(json.optLong("timestamp", System.currentTimeMillis())))}"

        card.animate().alpha(0.5f).setDuration(100).withEndAction {
            card.animate().alpha(1f).setDuration(100).start()
        }
    }

    private fun formatMetricData(type: String, d: JSONObject): String {
        return when (type) {
            "PPG" -> "Green: ${d.optInt("green")}\nIR: ${d.optInt("ir")}\nRed: ${d.optInt("red")}"
            "SpO2" -> "SpO2: ${d.optInt("spo2")}%\nHR: ${d.optInt("hr", d.optInt("heart_rate"))} BPM"
            "HeartRate" -> "HR: ${d.optInt("hr", d.optInt("heart_rate"))} BPM"
            "ECG" -> "ECG: ${String.format("%.2f", d.optDouble("ecg_mv"))} mV\nSeq: ${d.optInt("sequence")}"
            "SkinTemp" -> {
                val obj = d.optDouble("obj", d.optDouble("object_temp", Double.NaN))
                val amb = d.optDouble("amb", d.optDouble("ambient_temp", Double.NaN))
                "Skin: ${if (!obj.isNaN()) String.format("%.1f", obj) + "°C" else "N/A"}\n" +
                        "Ambient: ${if (!amb.isNaN()) String.format("%.1f", amb) + "°C" else "N/A"}"
            }
            "BIA" -> "BMR: ${String.format("%.0f", d.optDouble("bmr"))} kcal\nFat: ${String.format("%.1f", d.optDouble("fat_ratio", d.optDouble("body_fat_ratio")))}%"
            "Sweat" -> "Loss: ${String.format("%.2f", d.optDouble("loss", d.optDouble("sweat_loss")))} mL"
            else -> d.toString()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(receiver) } catch (e: Exception) {}
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}