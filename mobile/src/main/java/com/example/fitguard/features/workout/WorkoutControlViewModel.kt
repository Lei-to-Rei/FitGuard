package com.example.fitguard.features.workout

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.fitguard.data.processing.RpeState
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

class WorkoutControlViewModel(application: Application) : AndroidViewModel(application) {

    enum class SessionState { IDLE, CONNECTING, ACTIVE, STOPPING }

    companion object {
        private const val TAG = "WorkoutControlVM"
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val PREFS_NAME = "workout_session"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_ACTIVITY_TYPE = "activity_type"
        private const val KEY_START_TIME = "start_time"
        private const val KEY_IS_ACTIVE = "is_active"
        private const val KEY_SEQUENCE_COUNT = "sequence_count"
        private const val KEY_RPE_INTERVAL = "rpe_interval"
        private const val KEY_LAST_RPE = "last_rpe"
    }

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableLiveData(SessionState.IDLE)
    val state: LiveData<SessionState> = _state

    private val _activityType = MutableLiveData("Walking")
    val activityType: LiveData<String> = _activityType

    private val _elapsedSeconds = MutableLiveData(0)
    val elapsedSeconds: LiveData<Int> = _elapsedSeconds

    private val _sequenceCount = MutableLiveData(0)
    val sequenceCount: LiveData<Int> = _sequenceCount

    private val _error = MutableLiveData<String?>(null)
    val error: LiveData<String?> = _error

    private val _rpeIntervalMinutes = MutableLiveData(10)
    val rpeIntervalMinutes: LiveData<Int> = _rpeIntervalMinutes

    private val _lastRpe = MutableLiveData(-1)
    val lastRpe: LiveData<Int> = _lastRpe

    private var sessionId: String = ""
    private var sessionStartTime: Long = 0L
    private var timerJob: Job? = null
    private var timeoutJob: Job? = null

    init {
        restoreSession()
    }

    fun setActivityType(type: String) {
        _activityType.value = type
    }

    fun setRpeInterval(minutes: Int) {
        _rpeIntervalMinutes.value = minutes.coerceIn(1, 15)
    }

    fun onRpeReceived(sessionId: String, rpeValue: Int) {
        if (sessionId != this.sessionId) return
        _lastRpe.value = rpeValue
        if (rpeValue >= 0) {
            RpeState.update(rpeValue)
        }
        saveSession()
        Log.d(TAG, "RPE received: $rpeValue for session $sessionId")
    }

    fun startSession(activityType: String) {
        if (_state.value != SessionState.IDLE) return

        sessionId = "session_${System.currentTimeMillis()}"
        sessionStartTime = System.currentTimeMillis()
        _activityType.value = activityType
        _state.value = SessionState.CONNECTING
        _error.value = null
        _elapsedSeconds.value = 0
        _sequenceCount.value = 0
        _lastRpe.value = -1
        RpeState.reset()

        viewModelScope.launch {
            try {
                val nodes = Wearable.getNodeClient(getApplication<Application>()).connectedNodes.await()
                if (nodes.isEmpty()) {
                    _error.value = "No watch connected"
                    _state.value = SessionState.IDLE
                    return@launch
                }

                val payload = JSONObject().apply {
                    put("activity_type", activityType)
                    put("session_id", sessionId)
                    put("rpe_interval_minutes", _rpeIntervalMinutes.value ?: 10)
                }
                val data = payload.toString().toByteArray(Charsets.UTF_8)

                for (node in nodes) {
                    Wearable.getMessageClient(getApplication<Application>())
                        .sendMessage(node.id, "/fitguard/activity/start", data).await()
                    Log.d(TAG, "Start message sent to ${node.displayName}")
                }

                startConnectTimeout()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send start: ${e.message}", e)
                _error.value = "Failed to connect: ${e.message}"
                _state.value = SessionState.IDLE
            }
        }
    }

    fun stopSession() {
        if (_state.value != SessionState.ACTIVE) return

        _state.value = SessionState.STOPPING
        cancelConnectTimeout()

        viewModelScope.launch {
            try {
                val nodes = Wearable.getNodeClient(getApplication<Application>()).connectedNodes.await()
                val payload = JSONObject().apply {
                    put("session_id", sessionId)
                }
                val data = payload.toString().toByteArray(Charsets.UTF_8)

                for (node in nodes) {
                    Wearable.getMessageClient(getApplication<Application>())
                        .sendMessage(node.id, "/fitguard/activity/stop", data).await()
                    Log.d(TAG, "Stop message sent to ${node.displayName}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send stop: ${e.message}", e)
                _error.value = "Failed to stop: ${e.message}"
                _state.value = SessionState.ACTIVE
            }
        }
    }

    fun onWatchAck(ackSessionId: String, status: String) {
        if (ackSessionId != sessionId) return

        cancelConnectTimeout()

        if (status == "started") {
            _state.value = SessionState.ACTIVE
            _error.value = null
            startTimer()
            saveSession()
        } else {
            _error.value = "Watch busy, try again later"
            _state.value = SessionState.IDLE
        }
    }

    fun onWatchStopped(stoppedSessionId: String, reason: String, sequenceCount: Int) {
        if (stoppedSessionId != sessionId) return

        cancelConnectTimeout()
        stopTimer()
        _sequenceCount.value = sequenceCount
        _state.value = SessionState.IDLE
        clearSavedSession()
        Log.d(TAG, "Session stopped: reason=$reason sequences=$sequenceCount")
    }

    fun onHeartbeat(hbSessionId: String, sequenceCount: Int, elapsedS: Int) {
        if (hbSessionId != sessionId) return

        _sequenceCount.value = sequenceCount
        saveSession()
    }

    private fun startConnectTimeout() {
        cancelConnectTimeout()
        timeoutJob = viewModelScope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (_state.value == SessionState.CONNECTING) {
                Log.w(TAG, "Connect timeout after ${CONNECT_TIMEOUT_MS}ms")
                _error.value = "Watch not responding"
                _state.value = SessionState.IDLE
            }
        }
    }

    private fun cancelConnectTimeout() {
        timeoutJob?.cancel()
        timeoutJob = null
    }

    private fun startTimer() {
        timerJob?.cancel()
        val startOffset = _elapsedSeconds.value ?: 0
        timerJob = viewModelScope.launch {
            var seconds = startOffset
            while (isActive) {
                _elapsedSeconds.value = seconds
                delay(1000)
                seconds++
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    private fun saveSession() {
        prefs.edit()
            .putString(KEY_SESSION_ID, sessionId)
            .putString(KEY_ACTIVITY_TYPE, _activityType.value ?: "Walking")
            .putLong(KEY_START_TIME, sessionStartTime)
            .putBoolean(KEY_IS_ACTIVE, true)
            .putInt(KEY_SEQUENCE_COUNT, _sequenceCount.value ?: 0)
            .putInt(KEY_RPE_INTERVAL, _rpeIntervalMinutes.value ?: 10)
            .putInt(KEY_LAST_RPE, _lastRpe.value ?: -1)
            .apply()
    }

    private fun clearSavedSession() {
        prefs.edit().clear().apply()
    }

    private fun restoreSession() {
        if (!prefs.getBoolean(KEY_IS_ACTIVE, false)) return

        val savedSessionId = prefs.getString(KEY_SESSION_ID, null) ?: return
        val savedStartTime = prefs.getLong(KEY_START_TIME, 0L)
        if (savedStartTime == 0L) return

        sessionId = savedSessionId
        sessionStartTime = savedStartTime
        _activityType.value = prefs.getString(KEY_ACTIVITY_TYPE, "Walking")
        _sequenceCount.value = prefs.getInt(KEY_SEQUENCE_COUNT, 0)
        _rpeIntervalMinutes.value = prefs.getInt(KEY_RPE_INTERVAL, 10)
        _lastRpe.value = prefs.getInt(KEY_LAST_RPE, -1)

        // Calculate elapsed from saved start time
        val elapsedMs = System.currentTimeMillis() - savedStartTime
        _elapsedSeconds.value = (elapsedMs / 1000).toInt()

        _state.value = SessionState.ACTIVE
        startTimer()

        Log.d(TAG, "Restored session $sessionId, elapsed=${_elapsedSeconds.value}s")
    }

    override fun onCleared() {
        super.onCleared()
        cancelConnectTimeout()
        stopTimer()
    }
}
