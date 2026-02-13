package com.example.fitguard.features.sleep

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.fitguard.services.WearMessageClient
import kotlinx.coroutines.launch

class SleepStressViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "SleepStressVM"
        private const val PREFS_NAME = "sleep_stress_prefs"
        private const val KEY_SLEEP_ENABLED = "sleep_enabled"
        private const val KEY_STRESS_ENABLED = "stress_enabled"
        private const val KEY_WATCH_RUNNING = "watch_running"
    }

    enum class WatchStatus {
        IDLE, STARTING, STARTED, STOPPED, REJECTED_ACTIVITY, REJECTED_OPEN_WATCH, NO_WATCH, ERROR
    }

    private val stressScorer = StressScorer
    private val sleepDetector = SleepDetector()
    private val messageClient = WearMessageClient(application)
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _sleepMonitoringEnabled = MutableLiveData(prefs.getBoolean(KEY_SLEEP_ENABLED, false))
    val sleepMonitoringEnabled: LiveData<Boolean> = _sleepMonitoringEnabled

    private val _stressMonitoringEnabled = MutableLiveData(prefs.getBoolean(KEY_STRESS_ENABLED, false))
    val stressMonitoringEnabled: LiveData<Boolean> = _stressMonitoringEnabled

    private val _stressResult = MutableLiveData<StressResult>()
    val stressResult: LiveData<StressResult> = _stressResult

    private val _sleepResult = MutableLiveData<SleepResult>()
    val sleepResult: LiveData<SleepResult> = _sleepResult

    private val _watchStatus = MutableLiveData<WatchStatus>()
    val watchStatus: LiveData<WatchStatus> = _watchStatus

    private var watchSequenceRunning = prefs.getBoolean(KEY_WATCH_RUNNING, false)

    init {
        // Restore UI status based on persisted state
        if (watchSequenceRunning) {
            _watchStatus.value = WatchStatus.STARTED
        }
    }

    fun toggleSleepMonitoring(enabled: Boolean) {
        _sleepMonitoringEnabled.value = enabled
        prefs.edit().putBoolean(KEY_SLEEP_ENABLED, enabled).apply()
        if (!enabled) {
            sleepDetector.reset()
        }
        updateWatchSequence()
    }

    fun toggleStressMonitoring(enabled: Boolean) {
        _stressMonitoringEnabled.value = enabled
        prefs.edit().putBoolean(KEY_STRESS_ENABLED, enabled).apply()
        updateWatchSequence()
    }

    private fun updateWatchSequence() {
        val anyEnabled = _sleepMonitoringEnabled.value == true || _stressMonitoringEnabled.value == true

        if (anyEnabled && !watchSequenceRunning) {
            sendStartToWatch()
        } else if (!anyEnabled && watchSequenceRunning) {
            sendStopToWatch()
        }
    }

    private fun sendStartToWatch() {
        _watchStatus.value = WatchStatus.STARTING
        viewModelScope.launch {
            when (val result = messageClient.sendStart()) {
                is WearMessageClient.Result.Success -> {
                    Log.d(TAG, "Start message sent to watch")
                    // Actual status will come from watch reply
                }
                is WearMessageClient.Result.NoWatch -> {
                    Log.w(TAG, "No watch connected")
                    _watchStatus.value = WatchStatus.NO_WATCH
                }
                is WearMessageClient.Result.Error -> {
                    Log.e(TAG, "Error sending start: ${result.msg}")
                    _watchStatus.value = WatchStatus.ERROR
                }
            }
        }
    }

    private fun sendStopToWatch() {
        viewModelScope.launch {
            when (val result = messageClient.sendStop()) {
                is WearMessageClient.Result.Success -> {
                    Log.d(TAG, "Stop message sent to watch")
                    setWatchRunning(false)
                    _watchStatus.value = WatchStatus.STOPPED
                }
                is WearMessageClient.Result.NoWatch -> {
                    setWatchRunning(false)
                    _watchStatus.value = WatchStatus.IDLE
                }
                is WearMessageClient.Result.Error -> {
                    Log.e(TAG, "Error sending stop: ${result.msg}")
                    setWatchRunning(false)
                    _watchStatus.value = WatchStatus.IDLE
                }
            }
        }
    }

    fun onWatchStatusReceived(status: String, reason: String) {
        Log.d(TAG, "Watch status: $status, reason: $reason")
        when (status) {
            "started" -> {
                setWatchRunning(true)
                _watchStatus.postValue(WatchStatus.STARTED)
            }
            "stopped" -> {
                setWatchRunning(false)
                _watchStatus.postValue(WatchStatus.STOPPED)
            }
            "rejected" -> {
                setWatchRunning(false)
                when (reason) {
                    "activity_running" -> _watchStatus.postValue(WatchStatus.REJECTED_ACTIVITY)
                    "open_watch_app" -> _watchStatus.postValue(WatchStatus.REJECTED_OPEN_WATCH)
                    else -> _watchStatus.postValue(WatchStatus.ERROR)
                }
                // Auto-disable toggles on rejection
                _sleepMonitoringEnabled.postValue(false)
                _stressMonitoringEnabled.postValue(false)
                prefs.edit()
                    .putBoolean(KEY_SLEEP_ENABLED, false)
                    .putBoolean(KEY_STRESS_ENABLED, false)
                    .apply()
            }
        }
    }

    fun onSequenceProcessed(
        rmssd: Double,
        sdnn: Double,
        meanHr: Double,
        accelVariance: Double,
        hasAccelData: Boolean
    ) {
        if (_stressMonitoringEnabled.value == true) {
            _stressResult.postValue(stressScorer.compute(rmssd, sdnn, meanHr))
        }

        if (_sleepMonitoringEnabled.value == true) {
            val result = if (hasAccelData) {
                sleepDetector.update(accelVariance, meanHr, rmssd)
            } else {
                sleepDetector.updateWithoutAccel(meanHr, rmssd)
            }
            _sleepResult.postValue(result)
        }
    }

    private fun setWatchRunning(running: Boolean) {
        watchSequenceRunning = running
        prefs.edit().putBoolean(KEY_WATCH_RUNNING, running).apply()
    }
}
