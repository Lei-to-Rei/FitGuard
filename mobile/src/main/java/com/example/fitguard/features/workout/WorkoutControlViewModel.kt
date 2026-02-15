package com.example.fitguard.features.workout

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
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
    }

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

    private var sessionId: String = ""
    private var timerJob: Job? = null
    private var timeoutJob: Job? = null

    fun setActivityType(type: String) {
        _activityType.value = type
    }

    fun startSession(activityType: String) {
        if (_state.value != SessionState.IDLE) return

        sessionId = "session_${System.currentTimeMillis()}"
        _activityType.value = activityType
        _state.value = SessionState.CONNECTING
        _error.value = null
        _elapsedSeconds.value = 0
        _sequenceCount.value = 0

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
        Log.d(TAG, "Session stopped: reason=$reason sequences=$sequenceCount")
    }

    fun onHeartbeat(hbSessionId: String, sequenceCount: Int, elapsedS: Int) {
        if (hbSessionId != sessionId) return

        _sequenceCount.value = sequenceCount
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
        timerJob = viewModelScope.launch {
            var seconds = 0
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

    override fun onCleared() {
        super.onCleared()
        cancelConnectTimeout()
        stopTimer()
    }
}
