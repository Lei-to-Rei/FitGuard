package com.example.fitguard.features.activitytracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.fitguard.data.processing.CsvWriter
import com.example.fitguard.data.processing.FatigueDetector
import com.example.fitguard.data.processing.RpeState
import com.example.fitguard.data.processing.SequenceProcessor
import com.google.firebase.auth.FirebaseAuth
import com.example.fitguard.services.SessionForegroundService
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

data class LocationPoint(val lat: Double, val lng: Double, val timeMs: Long)

class ActivityTrackingViewModel(application: Application) : AndroidViewModel(application) {

    enum class SessionState { IDLE, CONNECTING, ACTIVE, STOPPING }

    companion object {
        private const val TAG = "ActivityTrackingVM"
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val PREFS_NAME = "workout_session"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_ACTIVITY_TYPE = "activity_type"
        private const val KEY_START_TIME = "start_time"
        private const val KEY_IS_ACTIVE = "is_active"
        private const val KEY_SEQUENCE_COUNT = "sequence_count"
        private const val KEY_RPE_INTERVAL = "rpe_interval"
        private const val KEY_LAST_RPE = "last_rpe"
        private const val KEY_SESSION_DIR = "session_dir"

        /** Current active session ID, readable by WearableDataListenerService to filter stale batches. */
        @Volatile
        var activeSessionId: String? = null
            internal set

        /** Current session subfolder name, readable by SequenceProcessor & WearableDataListenerService. */
        @Volatile
        var activeSessionDir: String? = null
            internal set
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

    // Location tracking
    private val _routePoints = MutableLiveData<List<LocationPoint>>(emptyList())
    val routePoints: LiveData<List<LocationPoint>> = _routePoints

    private val _distanceMeters = MutableLiveData(0f)
    val distanceMeters: LiveData<Float> = _distanceMeters

    private val _paceMinPerKm = MutableLiveData(0.0)
    val paceMinPerKm: LiveData<Double> = _paceMinPerKm

    // Current location (for initial map centering before session starts)
    private val _currentLocation = MutableLiveData<LocationPoint?>()
    val currentLocation: LiveData<LocationPoint?> = _currentLocation

    private val routePointsList = mutableListOf<LocationPoint>()
    private var totalDistanceMeters = 0f
    private var isTrackingLocation = false
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(application)

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            val point = LocationPoint(loc.latitude, loc.longitude, System.currentTimeMillis())

            if (routePointsList.isNotEmpty()) {
                val last = routePointsList.last()
                val results = FloatArray(1)
                Location.distanceBetween(last.lat, last.lng, point.lat, point.lng, results)
                totalDistanceMeters += results[0]
                _distanceMeters.value = totalDistanceMeters

                val elapsedMinutes = (point.timeMs - sessionStartTime) / 60000.0
                val distanceKm = totalDistanceMeters / 1000.0
                if (distanceKm > 0.01) {
                    _paceMinPerKm.value = elapsedMinutes / distanceKm
                }
            }

            routePointsList.add(point)
            _routePoints.value = routePointsList.toList()
        }
    }

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

    fun onPhoneRpeAnswered(sessionId: String, rpeValue: Int) {
        if (sessionId != this.sessionId) return
        _lastRpe.value = rpeValue
        // RpeState and SequenceProcessor already updated by RpePromptActivity
        saveSession()
        Log.d(TAG, "Phone RPE answered: $rpeValue for session $sessionId")
    }

    private fun buildSessionDirName(activityType: String, startTime: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US)
        return "${sdf.format(Date(startTime))}_${activityType.replace(' ', '_')}"
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
        SequenceProcessor.clearBuffer()
        activeSessionId = sessionId
        activeSessionDir = buildSessionDirName(activityType, sessionStartTime)

        // Clear previous route data
        routePointsList.clear()
        totalDistanceMeters = 0f
        _routePoints.value = emptyList()
        _distanceMeters.value = 0f
        _paceMinPerKm.value = 0.0

        // Persist session dir immediately so WearableDataListenerService can recover it
        prefs.edit()
            .putString(KEY_SESSION_DIR, activeSessionDir)
            .putString(KEY_SESSION_ID, sessionId)
            .putString(KEY_ACTIVITY_TYPE, activityType)
            .putLong(KEY_START_TIME, sessionStartTime)
            .apply()
        val hasLocationPerm = ActivityCompat.checkSelfPermission(
            getApplication(), Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        SessionForegroundService.start(getApplication(), activityType, withLocation = hasLocationPerm)

        viewModelScope.launch {
            try {
                // Delete stale DataClient items so old batches can't be re-delivered
                Wearable.getDataClient(getApplication<Application>())
                    .deleteDataItems(
                        android.net.Uri.Builder().scheme("wear").path("/health_tracker_batch/").build(),
                        DataClient.FILTER_PREFIX
                    ).await()
                Log.d(TAG, "Deleted stale /health_tracker_batch/ items")

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

    private fun saveRouteData() {
        val dir = activeSessionDir ?: return
        val points = routePointsList.toList()
        if (points.isEmpty()) return
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: ""
        val distance = totalDistanceMeters
        val pace = _paceMinPerKm.value ?: 0.0
        val duration = if (sessionStartTime > 0) System.currentTimeMillis() - sessionStartTime else 0L

        viewModelScope.launch(Dispatchers.IO) {
            CsvWriter.writeRouteCsv(points, userId, dir)
            CsvWriter.writeRouteSummary(distance, pace, duration, points.size, userId, dir)
            // Backup route to Firestore
            if (userId.isNotEmpty()) {
                CsvWriter.pushRouteToFirestore(points, userId, dir)
            }
        }
    }

    private fun pushSessionToFirestore() {
        val dir = activeSessionDir ?: return
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val activity = _activityType.value ?: "Unknown"
        val duration = if (sessionStartTime > 0) System.currentTimeMillis() - sessionStartTime else 0L

        CsvWriter.pushSessionMetadata(
            userId, dir, activity, sessionStartTime, duration,
            totalDistanceMeters, _paceMinPerKm.value ?: 0.0, routePointsList.size
        )
    }

    fun stopSession() {
        if (_state.value != SessionState.ACTIVE) return

        _state.value = SessionState.STOPPING
        cancelConnectTimeout()
        saveRouteData()
        stopLocationTracking()
        SequenceProcessor.flushRemainingAndClear()
        pushSessionToFirestore()
        activeSessionId = null
        activeSessionDir = null
        SessionForegroundService.stop(getApplication())

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
            startLocationTracking()
            saveSession()
        } else {
            _error.value = "Watch busy, try again later"
            _state.value = SessionState.IDLE
        }
    }

    fun onWatchStopped(stoppedSessionId: String, reason: String, sequenceCount: Int) {
        if (stoppedSessionId != sessionId) return

        cancelConnectTimeout()
        saveRouteData()
        stopTimer()
        stopLocationTracking()
        SequenceProcessor.flushRemainingAndClear()
        pushSessionToFirestore()
        RpeState.reset()
        activeSessionId = null
        activeSessionDir = null
        SessionForegroundService.stop(getApplication())
        _sequenceCount.value = sequenceCount
        _state.value = SessionState.IDLE
        clearSavedSession()
        Log.d(TAG, "Session stopped: reason=$reason sequences=$sequenceCount")

        // Generate personalized scaler from accumulated session data
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: ""
        if (userId.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                val detector = FatigueDetector(getApplication())
                val generated = detector.generatePersonalizedScaler(userId)
                if (generated) {
                    Log.d(TAG, "Personalized scaler updated for user $userId")
                }
            }
        }
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
        timerJob = viewModelScope.launch {
            while (isActive) {
                val elapsedMs = System.currentTimeMillis() - sessionStartTime
                _elapsedSeconds.value = (elapsedMs / 1000).toInt()
                delay(1000)
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
            .putString(KEY_SESSION_DIR, activeSessionDir ?: "")
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
        activeSessionId = sessionId
        val savedDir = prefs.getString(KEY_SESSION_DIR, null)
        activeSessionDir = if (!savedDir.isNullOrEmpty()) savedDir else buildSessionDirName(
            _activityType.value ?: "Walking", sessionStartTime
        )
        SessionForegroundService.start(getApplication(), _activityType.value ?: "Walking")
        startTimer()

        startLocationTracking()
        Log.d(TAG, "Restored session $sessionId, dir=$activeSessionDir, elapsed=${_elapsedSeconds.value}s")
    }

    @SuppressLint("MissingPermission")
    fun startLocationTracking() {
        if (isTrackingLocation) return
        if (ActivityCompat.checkSelfPermission(
                getApplication(), Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
            .setMinUpdateDistanceMeters(2f)
            .setMinUpdateIntervalMillis(2000)
            .build()

        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        isTrackingLocation = true
        Log.d(TAG, "Location tracking started")
    }

    fun stopLocationTracking() {
        if (!isTrackingLocation) return
        fusedLocationClient.removeLocationUpdates(locationCallback)
        isTrackingLocation = false
        Log.d(TAG, "Location tracking stopped")
    }

    @SuppressLint("MissingPermission")
    fun fetchCurrentLocation() {
        if (ActivityCompat.checkSelfPermission(
                getApplication(), Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                _currentLocation.value = LocationPoint(location.latitude, location.longitude, System.currentTimeMillis())
            } else {
                requestSingleLocationUpdate()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestSingleLocationUpdate() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
            .setMaxUpdates(1)
            .build()

        fusedLocationClient.requestLocationUpdates(request, object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                _currentLocation.value = LocationPoint(loc.latitude, loc.longitude, System.currentTimeMillis())
                fusedLocationClient.removeLocationUpdates(this)
            }
        }, Looper.getMainLooper())
    }

    override fun onCleared() {
        super.onCleared()
        cancelConnectTimeout()
        stopTimer()
        stopLocationTracking()
        // Do NOT clear activeSessionId here — session outlives the ViewModel.
        // Only explicit stop/watchStopped should clear it.
    }
}
