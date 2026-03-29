package com.example.fitguard.features.fatigue

import android.app.Application
import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.fitguard.data.db.AppDatabase
import com.example.fitguard.data.processing.CsvWriter
import com.example.fitguard.data.processing.FatigueDetector
import com.example.fitguard.data.processing.FatigueResult
import com.example.fitguard.features.activitytracking.ActivityTrackingViewModel
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class FatigueTrendPoint(val timeMs: Long, val fatiguePercent: Float)

data class BaselineComparison(
    val currentHr: Double,
    val baselineHr: Double,
    val hrDiffPercent: Double,
    val currentRmssd: Double,
    val baselineRmssd: Double,
    val rmssdDiffPercent: Double
)

data class ScalerComparisonResult(
    val global: FatigueResult?,
    val external: FatigueResult?,
    val onDevice: FatigueResult?,
    val globalReady: Boolean,
    val externalReady: Boolean,
    val onDeviceReady: Boolean
)

class FatigueViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "FatigueViewModel"
        private const val DIR_NAME = "FitGuard_Data"
        private const val BASELINE_WINDOW_COUNT = 3
        private const val PREDICTION_MINUTES = 5
        private const val PREDICTION_STEP_MS = 30_000L
        private const val MIN_POINTS_FOR_PREDICTION = 3
        private const val MAX_REGRESSION_POINTS = 10
    }

    private val globalDetector = FatigueDetector(application)
    private val externalDetector = FatigueDetector(application)
    private val onDeviceDetector = FatigueDetector(application)

    private val _currentResult = MutableLiveData<FatigueResult?>()
    val currentResult: LiveData<FatigueResult?> = _currentResult

    private val _weeklyData = MutableLiveData<List<Float>>()
    val weeklyData: LiveData<List<Float>> = _weeklyData

    private val _isModelReady = MutableLiveData(false)
    val isModelReady: LiveData<Boolean> = _isModelReady

    private val _windowCount = MutableLiveData(0)
    val windowCount: LiveData<Int> = _windowCount

    // Session-active gating
    private val _isSessionActive = MutableLiveData(false)
    val isSessionActive: LiveData<Boolean> = _isSessionActive

    // Baseline HR/HRV comparison
    private val _baselineComparison = MutableLiveData<BaselineComparison?>()
    val baselineComparison: LiveData<BaselineComparison?> = _baselineComparison

    private val _baselineWindowsCollected = MutableLiveData(0)
    val baselineWindowsCollected: LiveData<Int> = _baselineWindowsCollected

    // Session fatigue trend
    private val sessionTrendPoints = mutableListOf<FatigueTrendPoint>()
    private val _sessionTrend = MutableLiveData<List<FatigueTrendPoint>>()
    val sessionTrend: LiveData<List<FatigueTrendPoint>> = _sessionTrend

    // 5-minute prediction
    private val _predictionTrend = MutableLiveData<List<FatigueTrendPoint>>()
    val predictionTrend: LiveData<List<FatigueTrendPoint>> = _predictionTrend

    // Scaler comparison
    private val _comparisonResult = MutableLiveData<ScalerComparisonResult?>()
    val comparisonResult: LiveData<ScalerComparisonResult?> = _comparisonResult
    private var comparisonWindowIndex = 0

    // Baseline accumulation
    private val baselineHrValues = mutableListOf<Double>()
    private val baselineRmssdValues = mutableListOf<Double>()
    private var baselineEstablished = false
    private var baselineHr = 0.0
    private var baselineRmssd = 0.0

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val userId = FirebaseAuth.getInstance().currentUser?.uid ?: ""

            // 1. Global detector — always available
            globalDetector.initialize()

            if (userId.isNotEmpty()) {
                val personalizedDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "$DIR_NAME/$userId/personalized"
                )

                // 2. External detector — from Python training pipeline
                val extScalerFile = File(personalizedDir, "user_${userId}_scaler.json")
                externalDetector.initializeWithScalerFile(extScalerFile)

                // 3. On-device detector — generate scaler if needed, then load
                val generator = FatigueDetector(getApplication())
                generator.generatePersonalizedScaler(userId)
                generator.close()

                val genScalerFile = File(personalizedDir, "user_${userId}_scaler_generated.json")
                onDeviceDetector.initializeWithScalerFile(genScalerFile)
            }

            _isModelReady.postValue(globalDetector.isReady)
            Log.d(TAG, "Detectors ready: global=${globalDetector.isReady}, " +
                "external=${externalDetector.isReady}, onDevice=${onDeviceDetector.isReady}")

            if (globalDetector.isReady) {
                loadWeeklyHistory(userId)
            }

            // Load static baselines from UserProfile
            if (userId.isNotEmpty()) {
                loadStaticBaselines(userId)
            }

            // Restore fatigue history if session is active
            val sessionDir = ActivityTrackingViewModel.activeSessionDir ?: ""
            if (userId.isNotEmpty() && sessionDir.isNotEmpty()) {
                restoreFatigueHistory(userId, sessionDir)
            }
        }

        checkSessionActive()
    }

    fun refreshSessionState() {
        checkSessionActive()
    }

    private fun checkSessionActive() {
        val staticActive = ActivityTrackingViewModel.activeSessionId != null
        if (staticActive) {
            _isSessionActive.postValue(true)
            return
        }
        // Fallback: check SharedPreferences for process death recovery
        val prefs = getApplication<Application>().getSharedPreferences("workout_session", Context.MODE_PRIVATE)
        val prefsActive = prefs.getBoolean("is_active", false)
        _isSessionActive.postValue(prefsActive)
    }

    private suspend fun loadStaticBaselines(userId: String) {
        try {
            val db = AppDatabase.getInstance(getApplication())
            val profile = db.userProfileDao().getByUid(userId) ?: return
            if (profile.restingHeartRateBpm > 0) {
                baselineHr = profile.restingHeartRateBpm.toDouble()
            }
            if (profile.restingHrvRmssd > 0.0) {
                baselineRmssd = profile.restingHrvRmssd
            }
            if (baselineHr > 0.0 && baselineRmssd > 0.0) {
                baselineEstablished = true
                _baselineWindowsCollected.postValue(BASELINE_WINDOW_COUNT)
                Log.d(TAG, "Static baselines loaded: HR=$baselineHr, RMSSD=$baselineRmssd")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load static baselines: ${e.message}")
        }
    }

    fun onNewFeatureWindow(features: FloatArray) {
        if (!globalDetector.isReady) return

        // Receiving a broadcast implies session is active
        _isSessionActive.postValue(true)

        // Extract HR and RMSSD from feature array (index 0 = mean_hr_bpm, index 8 = rmssd_ms)
        val currentHr = features.getOrElse(0) { 0f }.toDouble()
        val currentRmssd = features.getOrElse(8) { 0f }.toDouble()

        // Baseline accumulation
        if (!baselineEstablished) {
            baselineHrValues.add(currentHr)
            baselineRmssdValues.add(currentRmssd)
            _baselineWindowsCollected.postValue(baselineHrValues.size)

            if (baselineHrValues.size >= BASELINE_WINDOW_COUNT) {
                if (baselineHr <= 0.0) {
                    baselineHr = baselineHrValues.average()
                }
                if (baselineRmssd <= 0.0) {
                    baselineRmssd = baselineRmssdValues.average()
                }
                baselineEstablished = true
                Log.d(TAG, "Baseline established: HR=$baselineHr, RMSSD=$baselineRmssd")
            }
        }

        // Post baseline comparison
        if (baselineEstablished && baselineHr > 0.0 && baselineRmssd > 0.0) {
            val hrDiff = ((currentHr - baselineHr) / baselineHr) * 100.0
            val rmssdDiff = ((currentRmssd - baselineRmssd) / baselineRmssd) * 100.0
            _baselineComparison.postValue(
                BaselineComparison(
                    currentHr = currentHr,
                    baselineHr = baselineHr,
                    hrDiffPercent = hrDiff,
                    currentRmssd = currentRmssd,
                    baselineRmssd = baselineRmssd,
                    rmssdDiffPercent = rmssdDiff
                )
            )
        }

        viewModelScope.launch(Dispatchers.Default) {
            val globalResult = globalDetector.addWindowAndPredict(features)
            val externalResult = if (externalDetector.isReady) externalDetector.addWindowAndPredict(features) else null
            val onDeviceResult = if (onDeviceDetector.isReady) onDeviceDetector.addWindowAndPredict(features) else null

            _windowCount.postValue(globalDetector.bufferedWindows)

            // Use global as primary for existing UI
            if (globalResult != null) {
                _currentResult.postValue(globalResult)
                Log.d(TAG, "Prediction: ${globalResult.level} (P(High)=${String.format("%.2f", globalResult.pHigh)}, ${globalResult.percentDisplay}%)")

                // Add to session trend
                val point = FatigueTrendPoint(System.currentTimeMillis(), globalResult.percentDisplay.toFloat())
                sessionTrendPoints.add(point)
                _sessionTrend.postValue(sessionTrendPoints.toList())

                // Compute 5-minute prediction
                computePrediction()
            }

            // Post comparison result
            _comparisonResult.postValue(
                ScalerComparisonResult(
                    global = globalResult,
                    external = externalResult,
                    onDevice = onDeviceResult,
                    globalReady = globalDetector.isReady,
                    externalReady = externalDetector.isReady,
                    onDeviceReady = onDeviceDetector.isReady
                )
            )

            // Write comparison CSV
            if (globalResult != null || externalResult != null || onDeviceResult != null) {
                writeComparisonCsvRow(globalResult, externalResult, onDeviceResult)
            }

            // Write fatigue history for persistence across activity re-entry
            if (globalResult != null) {
                val userId = FirebaseAuth.getInstance().currentUser?.uid ?: ""
                val sessionDir = ActivityTrackingViewModel.activeSessionDir ?: ""
                if (userId.isNotEmpty() && sessionDir.isNotEmpty()) {
                    CsvWriter.writeFatigueHistoryRow(
                        CsvWriter.FatigueHistoryRow(
                            timestamp = System.currentTimeMillis(),
                            fatiguePercent = globalResult.percentDisplay.toFloat(),
                            global = globalResult,
                            external = externalResult,
                            onDevice = onDeviceResult,
                            currentHr = currentHr,
                            currentRmssd = currentRmssd,
                            baselineHr = baselineHr,
                            baselineRmssd = baselineRmssd
                        ),
                        userId, sessionDir
                    )
                }
            }
        }
    }

    private fun writeComparisonCsvRow(
        global: FatigueResult?,
        external: FatigueResult?,
        onDevice: FatigueResult?
    ) {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: ""
        val sessionDir = ActivityTrackingViewModel.activeSessionDir ?: ""
        if (userId.isEmpty() || sessionDir.isEmpty()) return
        CsvWriter.writeComparisonRow(
            System.currentTimeMillis(),
            comparisonWindowIndex++,
            global, external, onDevice,
            userId, sessionDir
        )
    }

    private fun restoreFatigueHistory(userId: String, sessionDir: String) {
        val rows = CsvWriter.readFatigueHistory(userId, sessionDir)
        if (rows.isEmpty()) return

        Log.d(TAG, "Restoring ${rows.size} fatigue history rows")

        // Restore trend points
        for (row in rows) {
            sessionTrendPoints.add(FatigueTrendPoint(row.timestamp, row.fatiguePercent))
        }
        _sessionTrend.postValue(sessionTrendPoints.toList())

        // Restore latest results
        val last = rows.last()
        if (last.global != null) {
            _currentResult.postValue(last.global)
        }
        _comparisonResult.postValue(
            ScalerComparisonResult(
                global = last.global,
                external = last.external,
                onDevice = last.onDevice,
                globalReady = globalDetector.isReady,
                externalReady = externalDetector.isReady,
                onDeviceReady = onDeviceDetector.isReady
            )
        )

        // Restore baseline
        if (last.baselineHr > 0.0 && last.baselineRmssd > 0.0) {
            baselineHr = last.baselineHr
            baselineRmssd = last.baselineRmssd
            baselineEstablished = true
            _baselineWindowsCollected.postValue(BASELINE_WINDOW_COUNT)
            val hrDiff = ((last.currentHr - baselineHr) / baselineHr) * 100.0
            val rmssdDiff = ((last.currentRmssd - baselineRmssd) / baselineRmssd) * 100.0
            _baselineComparison.postValue(
                BaselineComparison(
                    currentHr = last.currentHr,
                    baselineHr = baselineHr,
                    hrDiffPercent = hrDiff,
                    currentRmssd = last.currentRmssd,
                    baselineRmssd = baselineRmssd,
                    rmssdDiffPercent = rmssdDiff
                )
            )
        }

        comparisonWindowIndex = rows.size
        computePrediction()
    }

    private fun computePrediction() {
        if (sessionTrendPoints.size < MIN_POINTS_FOR_PREDICTION) {
            _predictionTrend.postValue(emptyList())
            return
        }

        val recentPoints = sessionTrendPoints.takeLast(MAX_REGRESSION_POINTS)

        // Linear regression: y = slope * x + intercept
        val n = recentPoints.size
        val t0 = recentPoints.first().timeMs
        val xs = recentPoints.map { (it.timeMs - t0).toDouble() }
        val ys = recentPoints.map { it.fatiguePercent.toDouble() }

        val sumX = xs.sum()
        val sumY = ys.sum()
        val sumXY = xs.zip(ys) { x, y -> x * y }.sum()
        val sumX2 = xs.sumOf { it * it }

        val denom = n * sumX2 - sumX * sumX
        if (denom == 0.0) {
            _predictionTrend.postValue(emptyList())
            return
        }

        val slope = (n * sumXY - sumX * sumY) / denom
        val intercept = (sumY - slope * sumX) / n

        // Project forward from the last point
        val lastTimeMs = recentPoints.last().timeMs
        val lastX = (lastTimeMs - t0).toDouble()
        val predictionSteps = (PREDICTION_MINUTES * 60_000L) / PREDICTION_STEP_MS

        val predictions = mutableListOf<FatigueTrendPoint>()
        for (i in 1..predictionSteps) {
            val futureX = lastX + i * PREDICTION_STEP_MS
            val futureY = (slope * futureX + intercept).toFloat().coerceIn(0f, 100f)
            val futureTimeMs = lastTimeMs + i * PREDICTION_STEP_MS
            predictions.add(FatigueTrendPoint(futureTimeMs, futureY))
        }

        _predictionTrend.postValue(predictions)
    }

    private fun loadWeeklyHistory(userId: String) {
        if (userId.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val baseDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "$DIR_NAME/$userId"
                )
                if (!baseDir.exists()) return@launch

                val cal = Calendar.getInstance()
                val today = clearTime(cal)
                cal.add(Calendar.DAY_OF_YEAR, -6)
                val weekStart = clearTime(cal)

                // Collect all JSONL feature windows from session dirs in the last 7 days
                val dailyScores = mutableMapOf<Long, MutableList<Float>>()
                val sessionDirs = baseDir.listFiles { f -> f.isDirectory } ?: return@launch
                for (dir in sessionDirs) {
                    val jsonlFile = File(dir, "features.jsonl")
                    if (!jsonlFile.exists()) continue

                    val windows = mutableListOf<FloatArray>()
                    var sessionDay: Long? = null

                    jsonlFile.forEachLine { line ->
                        if (line.isBlank()) return@forEachLine
                        try {
                            val obj = JSONObject(line)
                            val timestamp = obj.optLong("timestamp", 0L)
                            if (timestamp == 0L) return@forEachLine

                            val dayCal = Calendar.getInstance().apply { timeInMillis = timestamp }
                            val dayMs = clearTime(dayCal)
                            if (dayMs < weekStart || dayMs > today) return@forEachLine

                            if (sessionDay == null) sessionDay = dayMs

                            val features = extractFeaturesFromJson(obj)
                            if (features != null) windows.add(features)
                        } catch (e: Exception) {
                            // Skip malformed lines
                        }
                    }

                    // Run batch inference on this session's windows
                    if (windows.size >= 5 && sessionDay != null) {
                        val result = globalDetector.predictBatch(windows)
                        if (result != null) {
                            dailyScores.getOrPut(sessionDay!!) { mutableListOf() }
                                .add(result.pHigh * 100f)
                        }
                    }
                }

                // Build 7-day list (Mon..Sun or last 7 days)
                val weeklyList = mutableListOf<Float>()
                val dayCal = Calendar.getInstance()
                dayCal.timeInMillis = weekStart
                for (i in 0..6) {
                    val dayMs = clearTime(dayCal)
                    val scores = dailyScores[dayMs]
                    weeklyList.add(scores?.average()?.toFloat() ?: 0f)
                    dayCal.add(Calendar.DAY_OF_YEAR, 1)
                }

                _weeklyData.postValue(weeklyList)
                Log.d(TAG, "Loaded weekly history: $weeklyList")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load weekly history: ${e.message}", e)
            }
        }
    }

    private fun extractFeaturesFromJson(obj: JSONObject): FloatArray? {
        val featureKeys = listOf(
            "mean_hr_bpm", "hr_std_bpm", "hr_min_bpm", "hr_max_bpm", "hr_range_bpm",
            "hr_slope_bpm_per_s", "nn_quality_ratio",
            "sdnn_ms", "rmssd_ms", "pnn50_pct", "mean_nn_ms", "cv_nn",
            "lf_power_ms2", "hf_power_ms2", "lf_hf_ratio", "total_power_ms2",
            "spo2_mean_pct", "spo2_min_pct", "spo2_std_pct",
            "accel_x_mean", "accel_y_mean", "accel_z_mean",
            "accel_x_var", "accel_y_var", "accel_z_var",
            "accel_mag_mean", "accel_mag_var", "accel_peak",
            "total_steps", "cadence_spm"
        )
        return try {
            FloatArray(featureKeys.size) { i ->
                obj.optDouble(featureKeys[i], 0.0).toFloat()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun clearTime(cal: Calendar): Long {
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    override fun onCleared() {
        super.onCleared()
        globalDetector.close()
        externalDetector.close()
        onDeviceDetector.close()
    }
}
