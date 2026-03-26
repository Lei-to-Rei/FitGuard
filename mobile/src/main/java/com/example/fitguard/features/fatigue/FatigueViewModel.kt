package com.example.fitguard.features.fatigue

import android.app.Application
import android.os.Environment
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.fitguard.data.processing.FatigueDetector
import com.example.fitguard.data.processing.FatigueResult
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class FatigueViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "FatigueViewModel"
        private const val DIR_NAME = "FitGuard_Data"
    }

    private val detector = FatigueDetector(application)

    private val _currentResult = MutableLiveData<FatigueResult?>()
    val currentResult: LiveData<FatigueResult?> = _currentResult

    private val _weeklyData = MutableLiveData<List<Float>>()
    val weeklyData: LiveData<List<Float>> = _weeklyData

    private val _isModelReady = MutableLiveData(false)
    val isModelReady: LiveData<Boolean> = _isModelReady

    private val _windowCount = MutableLiveData(0)
    val windowCount: LiveData<Int> = _windowCount

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val userId = FirebaseAuth.getInstance().currentUser?.uid ?: ""
            // Try personalized model first, fall back to base model
            val personalized = if (userId.isNotEmpty()) detector.initializePersonalized(userId) else false
            if (!personalized) {
                detector.initialize()
            }
            _isModelReady.postValue(detector.isReady)
            if (detector.isReady) {
                Log.d(TAG, "Model ready (personalized=$personalized)")
                loadWeeklyHistory(userId)
            } else {
                Log.e(TAG, "Failed to initialize fatigue detector")
            }
        }
    }

    fun onNewFeatureWindow(features: FloatArray) {
        if (!detector.isReady) return
        viewModelScope.launch(Dispatchers.Default) {
            val result = detector.addWindowAndPredict(features)
            _windowCount.postValue(detector.bufferedWindows)
            if (result != null) {
                _currentResult.postValue(result)
                Log.d(TAG, "Prediction: ${result.level} (P(High)=${String.format("%.2f", result.pHigh)}, ${result.percentDisplay}%)")
            }
        }
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
                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

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
                        val result = detector.predictBatch(windows)
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
        detector.close()
    }
}
