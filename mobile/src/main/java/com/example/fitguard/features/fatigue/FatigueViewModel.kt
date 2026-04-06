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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class FatigueTrendPoint(val timeMs: Long, val fatiguePercent: Float)

enum class Trend { STABLE, INCREASING, DECREASING }

data class NextStepPrediction(
    val futureLevel: Int,
    val futurePHigh: Float,
    val trend: Trend,
    val trendMessage: String
)

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
        private const val WINDOW_STEP_MS = 15_000L
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
    private var trendWindowCount = 0
    private var trendAnchorTimeMs = 0L
    private val _sessionTrend = MutableLiveData<List<FatigueTrendPoint>>()
    val sessionTrend: LiveData<List<FatigueTrendPoint>> = _sessionTrend

    // 5-minute prediction (linear regression, unchanged)
    private val _predictionTrend = MutableLiveData<List<FatigueTrendPoint>>()
    val predictionTrend: LiveData<List<FatigueTrendPoint>> = _predictionTrend

    // ~60-second next-step prediction from model's second output tensor
    private val _nextStepPrediction = MutableStateFlow<NextStepPrediction?>(null)
    val nextStepPrediction: StateFlow<NextStepPrediction?> = _nextStepPrediction.asStateFlow()

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

                // Restore window count from features.csv so warmup counter survives navigation
                val featureCount = CsvWriter.countFeatureRows(userId, sessionDir)
                if (featureCount > 0) {
                    _windowCount.postValue(featureCount.coerceAtMost(5))
                }
            }
        }

        checkSessionActive()
    }

    fun refreshSessionState() {
        val wasActive = _isSessionActive.value ?: false
        checkSessionActive()
        // If transitioning to active and no restored data, state is already clean
        // If transitioning to inactive→active with a new session, reset is handled by onSessionStarted()
    }

    /**
     * Call when a brand-new session begins (not when returning to an existing one).
     * Clears stale prediction state so the UI starts clean.
     */
    fun resetForNewSession() {
        globalDetector.clearBuffer()
        externalDetector.clearBuffer()
        onDeviceDetector.clearBuffer()
        FatigueAlertManager.reset()
        _currentResult.postValue(null)
        _windowCount.postValue(0)
        _comparisonResult.postValue(null)
        sessionTrendPoints.clear()
        trendWindowCount = 0
        trendAnchorTimeMs = 0L
        _sessionTrend.postValue(emptyList())
        _predictionTrend.postValue(emptyList())
        _nextStepPrediction.value = null
        baselineHrValues.clear()
        baselineRmssdValues.clear()
        baselineEstablished = false
        _baselineWindowsCollected.postValue(0)
        _baselineComparison.postValue(null)
        comparisonWindowIndex = 0
        Log.d(TAG, "Session reset: all buffers cleared")
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

            // Use global as primary for existing UI, with EMA smoothing
            if (globalResult != null) {
                val sPHigh = FatigueAlertManager.smoothedPHigh
                if (sPHigh >= 0f) {
                    val smoothedLevelIndex = when {
                        sPHigh < 0.25f -> 0
                        sPHigh < 0.50f -> 1
                        sPHigh < 0.75f -> 2
                        else -> 3
                    }
                    val smoothedLevel = when (smoothedLevelIndex) {
                        0 -> "Mild"
                        1 -> "Moderate"
                        2 -> "High"
                        3 -> "Critical"
                        else -> "Unknown"
                    }
                    val smoothedResult = FatigueResult(
                        pLow = 1f - sPHigh,
                        pHigh = sPHigh,
                        level = smoothedLevel,
                        levelIndex = smoothedLevelIndex,
                        percentDisplay = (sPHigh * 100).toInt().coerceIn(0, 100)
                    )
                    _currentResult.postValue(smoothedResult)

                    // Next-step prediction from model's future output tensor
                    val sFuturePHigh = FatigueAlertManager.futureSmoothedPHigh
                    if (sFuturePHigh > 0f) {
                        val futureLvl = FatigueAlertManager.futureLevel
                        val trend = when (FatigueAlertManager.currentTrend) {
                            "INCREASING" -> Trend.INCREASING
                            "DECREASING" -> Trend.DECREASING
                            else         -> Trend.STABLE
                        }
                        _nextStepPrediction.value = NextStepPrediction(
                            futureLevel = futureLvl,
                            futurePHigh = sFuturePHigh,
                            trend = trend,
                            trendMessage = buildTrendMessage(smoothedLevelIndex, futureLvl)
                        )
                    }
                } else {
                    _currentResult.postValue(globalResult)
                }
                Log.d(TAG, "Prediction: raw=${String.format("%.2f", globalResult.pHigh)}, " +
                        "smoothed=${String.format("%.2f", sPHigh)}, ${globalResult.percentDisplay}%")

                // Add smoothed percent to session trend, spaced by sliding window interval
                val smoothedPercent = if (sPHigh >= 0f)
                    (sPHigh * 100f).coerceIn(0f, 100f)
                else globalResult.percentDisplay.toFloat()
                if (trendAnchorTimeMs == 0L) {
                    trendAnchorTimeMs = System.currentTimeMillis()
                }
                val pointTimeMs = trendAnchorTimeMs + trendWindowCount * WINDOW_STEP_MS
                trendWindowCount++
                val point = FatigueTrendPoint(pointTimeMs, smoothedPercent)
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
                if (userId.isNotEmpty()) {
                    val csvSmoothedPercent = if (FatigueAlertManager.smoothedPHigh >= 0f)
                        (FatigueAlertManager.smoothedPHigh * 100f).coerceIn(0f, 100f)
                    else globalResult.percentDisplay.toFloat()
                    val smoothedCurrentLevel = if (FatigueAlertManager.smoothedPHigh >= 0f) when {
                        FatigueAlertManager.smoothedPHigh < 0.25f -> 0
                        FatigueAlertManager.smoothedPHigh < 0.50f -> 1
                        FatigueAlertManager.smoothedPHigh < 0.75f -> 2
                        else -> 3
                    } else globalResult.levelIndex
                    CsvWriter.writeFatigueHistoryRow(
                        CsvWriter.FatigueHistoryRow(
                            timestamp = System.currentTimeMillis(),
                            fatiguePercent = csvSmoothedPercent,
                            global = globalResult,
                            external = externalResult,
                            currentHr = currentHr,
                            currentRmssd = currentRmssd,
                            alertRawPHigh = FatigueAlertManager.lastRawPHigh,
                            alertSmoothedPHigh = if (FatigueAlertManager.smoothedPHigh >= 0f)
                                FatigueAlertManager.smoothedPHigh else 0f,
                            futurePHigh = FatigueAlertManager.futureSmoothedPHigh,
                            futureLevel = FatigueAlertManager.futureLevel,
                            trend = FatigueAlertManager.currentTrend
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

        // Restore trend points (prefer smoothed values for backward compat)
        for (row in rows) {
            val percent = if (row.alertSmoothedPHigh > 0f)
                (row.alertSmoothedPHigh * 100f).coerceIn(0f, 100f)
            else row.fatiguePercent
            sessionTrendPoints.add(FatigueTrendPoint(row.timestamp, percent))
        }
        _sessionTrend.postValue(sessionTrendPoints.toList())

        // Resume trend counter from restored points
        trendWindowCount = sessionTrendPoints.size
        if (sessionTrendPoints.isNotEmpty()) {
            val first = sessionTrendPoints.first().timeMs
            trendAnchorTimeMs = first
        }

        // Restore latest results
        val last = rows.last()
        if (last.global != null) {
            _currentResult.postValue(last.global)
        }
        _comparisonResult.postValue(
            ScalerComparisonResult(
                global = last.global,
                external = last.external,
                onDevice = null,
                globalReady = globalDetector.isReady,
                externalReady = externalDetector.isReady,
                onDeviceReady = onDeviceDetector.isReady
            )
        )

        // Restore baseline from existing member variables (set from user profile at session start)
        if (baselineHr > 0.0 && baselineRmssd > 0.0) {
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
            "spo2_mean_pct", "spo2_min_pct", "spo2_std_pct"
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

    private fun buildTrendMessage(currentLevel: Int, futureLevel: Int): String = when {
        futureLevel <= currentLevel - 2             -> "Strong recovery — fatigue falling"
        currentLevel == 0 && futureLevel == 0       -> "Fatigue stable — feeling good"
        currentLevel == 0 && futureLevel == 1       -> "Fatigue starting to build"
        currentLevel == 1 && futureLevel == 0       -> "Fatigue dropping — good recovery"
        currentLevel == 1 && futureLevel == 1       -> "Fatigue stable — monitor pace"
        currentLevel == 1 && futureLevel == 2       -> "Fatigue increasing — ease up"
        currentLevel == 2 && futureLevel == 1       -> "Fatigue recovering"
        currentLevel == 2 && futureLevel == 2       -> "Fatigue sustained — consider a break"
        currentLevel == 2 && futureLevel == 3       -> "Fatigue worsening — rest recommended"
        currentLevel == 3 && futureLevel == 2       -> "Fatigue easing, still elevated"
        currentLevel == 3 && futureLevel == 3       -> "High fatigue — rest now"
        else                                        -> "Fatigue stable"
    }

    override fun onCleared() {
        super.onCleared()
        globalDetector.close()
        externalDetector.close()
        onDeviceDetector.close()
    }
}
