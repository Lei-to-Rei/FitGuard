package com.example.fitguard

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.fitguard.auth.LoginActivity
import com.example.fitguard.data.repository.AuthRepository
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import android.util.Log
import com.example.fitguard.analysis.PPGAnalysisUtils
import java.text.SimpleDateFormat
import java.util.*

class  MainActivity : AppCompatActivity(), DataClient.OnDataChangedListener {

    private lateinit var dataClient: DataClient
    private lateinit var sensorDataText: TextView
    private val batchHistory = mutableListOf<String>()
    private val maxHistorySize = 10

    // Store latest data for cross-wavelength analysis
    private var latestGreenData: Pair<IntArray, LongArray>? = null
    private var latestRedData: Pair<IntArray, LongArray>? = null
    private var latestIRData: Pair<IntArray, LongArray>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check authentication
        if (!AuthRepository.isUserLoggedIn()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        val scrollView = ScrollView(this)
        sensorDataText = TextView(this).apply {
            setPadding(32, 32, 32, 32)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE

            // Show user info
            val user = AuthRepository.currentUser
            text = "Welcome, ${user?.displayName ?: user?.email}!\n\n" +
                    "Waiting for PPG batch data from watch..."
        }
        scrollView.addView(sensorDataText)
        setContentView(scrollView)

        dataClient = Wearable.getDataClient(this)
        Log.d("PhonePPG", "Phone app started, waiting for PPG batch data...")
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_logout -> {
                AuthRepository.signOut()
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        dataClient.addListener(this)
    }

    override fun onPause() {
        super.onPause()
        dataClient.removeListener(this)
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        Log.d("PhonePPG", "Data received from watch!")

        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED) {
                val path = event.dataItem.uri.path ?: continue
                Log.d("PhonePPG", "Path: $path")

                // Handle different wavelength paths
                if (path.startsWith("/ppg_batch_data")) {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap

                    val wavelengthName = dataMap.getString("wavelength_name")
                    val wavelengthNm = dataMap.getInt("wavelength_nm")
                    val valuesList = dataMap.getIntegerArrayList("values")
                    val values = valuesList?.toIntArray()
                    val timestamps = dataMap.getLongArray("timestamps")
                    val startTime = dataMap.getLong("start_time")
                    val endTime = dataMap.getLong("end_time")
                    val count = dataMap.getInt("count")
                    val mean = dataMap.getDouble("mean")
                    val stdDev = dataMap.getDouble("std_dev")
                    val min = dataMap.getInt("min")
                    val max = dataMap.getInt("max")
                    val batchTimestamp = dataMap.getLong("batch_timestamp")

                    Log.d("PhonePPG", "Batch received: $wavelengthName - $count samples")

                    // Store latest data for each wavelength
                    if (values != null && timestamps != null) {
                        when (wavelengthName) {
                            "Green" -> latestGreenData = Pair(values, timestamps)
                            "Red" -> latestRedData = Pair(values, timestamps)
                            "IR" -> latestIRData = Pair(values, timestamps)
                        }
                    }

                    runOnUiThread {
                        displayPPGBatchData(
                            wavelengthName, wavelengthNm, values, timestamps,
                            startTime, endTime, count, mean, stdDev, min, max, batchTimestamp
                        )
                    }
                }
            }
        }
    }

    private fun displayPPGBatchData(
        wavelengthName: String?,
        wavelengthNm: Int,
        values: IntArray?,
        timestamps: LongArray?,
        startTime: Long,
        endTime: Long,
        count: Int,
        mean: Double,
        stdDev: Double,
        min: Int,
        max: Int,
        batchTimestamp: Long
    ) {
        if (wavelengthName == null || values == null || timestamps == null) return

        val displayText = StringBuilder()
        val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

        // Header
        displayText.append("╔═══════════════════════════════════════════╗\n")
        displayText.append("║       PPG BATCH DATA RECEIVED             ║\n")
        displayText.append("╚═══════════════════════════════════════════╝\n\n")

        // Wavelength info
        displayText.append("🔴 Wavelength: $wavelengthName ($wavelengthNm nm)\n")
        displayText.append("─────────────────────────────────────\n\n")

        // Batch summary
        displayText.append("📊 BATCH SUMMARY\n")
        displayText.append("─────────────────────────────────────\n")
        displayText.append("Sample Count:    $count\n")
        displayText.append("Duration:        ${(endTime - startTime) / 1000.0} sec\n")
        val samplingRate = count / ((endTime - startTime) / 1000.0)
        displayText.append("Sampling Rate:   ${"%.2f".format(samplingRate)} Hz\n")
        displayText.append("\n")

        // Statistics
        displayText.append("📈 STATISTICS\n")
        displayText.append("─────────────────────────────────────\n")
        displayText.append("Mean:            ${"%.2f".format(mean)}\n")
        displayText.append("Std Dev:         ${"%.2f".format(stdDev)}\n")
        displayText.append("Min:             $min\n")
        displayText.append("Max:             $max\n")
        displayText.append("Range:           ${max - min}\n")
        displayText.append("\n")

        // ========== ADVANCED ANALYSIS ==========

        // 1. HEART RATE CALCULATION
        if (wavelengthName == "Green") {
            displayText.append("💓 HEART RATE ANALYSIS\n")
            displayText.append("─────────────────────────────────────\n")

            val hrResult = PPGAnalysisUtils.calculateHeartRate(values, timestamps, samplingRate)
            if (hrResult != null) {
                displayText.append("Heart Rate:      ${"%.1f".format(hrResult.bpm)} BPM\n")
                displayText.append("Confidence:      ${"%.1f".format(hrResult.confidence)}%\n")
                displayText.append("Peaks Detected:  ${hrResult.peakCount}\n")
                displayText.append("Avg IBI:         ${"%.0f".format(hrResult.avgIBI)} ms\n")
                displayText.append("IBI Std Dev:     ${"%.0f".format(hrResult.ibiStdDev)} ms\n")
            } else {
                displayText.append("Insufficient data for HR calculation\n")
            }
            displayText.append("\n")

            // 3. HEART RATE VARIABILITY
            displayText.append("📉 HRV ANALYSIS\n")
            displayText.append("─────────────────────────────────────\n")

            val hrvResult = PPGAnalysisUtils.calculateHRV(values, timestamps, samplingRate)
            if (hrvResult != null) {
                displayText.append("Mean RR:         ${"%.0f".format(hrvResult.meanRR)} ms\n")
                displayText.append("SDNN:            ${"%.2f".format(hrvResult.sdnn)} ms\n")
                displayText.append("RMSSD:           ${"%.2f".format(hrvResult.rmssd)} ms\n")
                displayText.append("pNN50:           ${"%.2f".format(hrvResult.pnn50)}%\n")
                displayText.append("Status:          ${hrvResult.stressIndex}\n")
                displayText.append("\nHRV Interpretation:\n")
                when {
                    hrvResult.sdnn > 50 -> displayText.append("  ✓ Excellent recovery state\n")
                    hrvResult.sdnn > 30 -> displayText.append("  • Normal variability\n")
                    else -> displayText.append("  ⚠ Low variability - may indicate stress\n")
                }
            } else {
                displayText.append("Insufficient data for HRV calculation\n")
            }
            displayText.append("\n")
        }

        // 2. SPO2 ESTIMATION (when both Red and IR are available)
        if ((wavelengthName == "Red" || wavelengthName == "IR") &&
            latestRedData != null && latestIRData != null) {

            displayText.append("🫁 BLOOD OXYGEN (SpO2) ESTIMATION\n")
            displayText.append("─────────────────────────────────────\n")

            val spO2Result = PPGAnalysisUtils.estimateSpO2(
                latestRedData!!.first,
                latestIRData!!.first,
                latestRedData!!.second,
                latestIRData!!.second
            )

            if (spO2Result != null) {
                displayText.append("SpO2:            ${"%.1f".format(spO2Result.spO2)}%\n")
                displayText.append("Perfusion Index: ${"%.2f".format(spO2Result.perfusionIndex)}%\n")
                displayText.append("R Value:         ${"%.4f".format(spO2Result.ratioOfRatios)}\n")
                displayText.append("Signal Quality:  ${spO2Result.quality}\n")

                if (spO2Result.needsCalibration) {
                    displayText.append("\n⚠ Note: Uncalibrated estimate\n")
                    displayText.append("  For accurate readings, calibrate\n")
                    displayText.append("  against pulse oximeter\n")
                }

                displayText.append("\nSpO2 Interpretation:\n")
                when {
                    spO2Result.spO2 >= 95 -> displayText.append("  ✓ Normal oxygen saturation\n")
                    spO2Result.spO2 >= 90 -> displayText.append("  • Acceptable for most people\n")
                    else -> displayText.append("  ⚠ May indicate low oxygen\n")
                }
            } else {
                displayText.append("Unable to calculate SpO2\n")
            }
            displayText.append("\n")
        }

        // Time info
        displayText.append("⏰ TIMING\n")
        displayText.append("─────────────────────────────────────\n")
        displayText.append("Start:           ${dateFormat.format(Date(startTime))}\n")
        displayText.append("End:             ${dateFormat.format(Date(endTime))}\n")
        displayText.append("Received:        ${dateFormat.format(Date(batchTimestamp))}\n")
        displayText.append("\n")

        // Sample preview (first 10 and last 10 values)
        displayText.append("📋 SAMPLE DATA PREVIEW\n")
        displayText.append("─────────────────────────────────────\n")
        displayText.append("First 10 samples:\n")
        values.take(10).forEachIndexed { index, value ->
            displayText.append("  [$index] $value\n")
        }

        if (values.size > 20) {
            displayText.append("  ...\n")
            displayText.append("Last 10 samples:\n")
            values.takeLast(10).forEachIndexed { index, value ->
                val actualIndex = values.size - 10 + index
                displayText.append("  [$actualIndex] $value\n")
            }
        } else if (values.size > 10) {
            values.drop(10).forEachIndexed { index, value ->
                val actualIndex = 10 + index
                displayText.append("  [$actualIndex] $value\n")
            }
        }

        displayText.append("\n")

        // Derived metrics
        displayText.append("🧮 BASIC DERIVED METRICS\n")
        displayText.append("─────────────────────────────────────\n")

        // Calculate AC and DC components
        val acComponent = stdDev
        val dcComponent = mean
        val acDcRatio = if (dcComponent != 0.0) acComponent / dcComponent else 0.0

        displayText.append("AC Component:    ${"%.2f".format(acComponent)}\n")
        displayText.append("DC Component:    ${"%.2f".format(dcComponent)}\n")
        displayText.append("AC/DC Ratio:     ${"%.4f".format(acDcRatio)}\n")

        // Perfusion Index estimation (simplified)
        val perfusionIndex = (acDcRatio * 100)
        displayText.append("Perfusion Index: ${"%.2f".format(perfusionIndex)}%\n")

        displayText.append("\n")

        // Add to history
        val historyEntry = "[$wavelengthName] ${dateFormat.format(Date(batchTimestamp))} - $count samples"
        batchHistory.add(0, historyEntry)
        if (batchHistory.size > maxHistorySize) {
            batchHistory.removeAt(batchHistory.lastIndex)
        }

        // Display history
        displayText.append("📜 RECENT BATCHES\n")
        displayText.append("─────────────────────────────────────\n")
        batchHistory.forEach { entry ->
            displayText.append("  $entry\n")
        }

        displayText.append("\n")
        displayText.append("═════════════════════════════════════\n")
        displayText.append("Total batches received: ${batchHistory.size}\n")

        sensorDataText.text = displayText.toString()

        // Log for debugging
        Log.d("PhonePPG", "Displayed batch: $wavelengthName with $count samples")
    }
}