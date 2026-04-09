package com.example.fitguard.features.activitytracking

import android.Manifest
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.fitguard.MainActivity
import com.example.fitguard.R
import com.example.fitguard.databinding.ActivityActivityTrackingBinding
import com.example.fitguard.features.fatigue.FatiguePredictionActivity
import com.example.fitguard.features.profile.UserHomeActivity
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

class ActivityTrackingActivity : AppCompatActivity(), MessageClient.OnMessageReceivedListener {
    private lateinit var binding: ActivityActivityTrackingBinding
    private val viewModel: ActivityTrackingViewModel by viewModels()
    private var routePolyline: Polyline? = null
    private lateinit var myLocationOverlay: MyLocationNewOverlay

    companion object {
        private const val TAG = "ActivityTrackingActivity"
    }

    private val mapLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.any { it.value }) {
            checkLocationSettings()
        }
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Proceed regardless — location is optional for route tracking
        checkNotificationAndStart()
        // Also check GPS settings so map can center on user
        if (hasLocationPermission()) {
            checkLocationSettings()
        }
    }

    private val locationSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { _ -> /* GPS enabled — overlay will pick up location automatically */ }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d(TAG, "POST_NOTIFICATIONS permission granted=$granted")
        checkRecoveryAndStart()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        binding = ActivityActivityTrackingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupMap()
        requestMapLocationPermission()
        setupActivityTypeSelection()
        setupStartStopButton()
        setupBottomNavigation()
        observeViewModel()
        observeRoute()

        Wearable.getMessageClient(this).addListener(this)
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            binding.bottomNavigation.selectedItemId = R.id.nav_activity
            binding.mapView.onResume()
        }
        if (::myLocationOverlay.isInitialized) {
            myLocationOverlay.enableMyLocation()
        }
    }

    private fun checkLocationSettings() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000).build()
        val settingsRequest = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .build()

        LocationServices.getSettingsClient(this)
            .checkLocationSettings(settingsRequest)
            .addOnFailureListener { exception ->
                if (exception is ResolvableApiException) {
                    try {
                        val intentSenderRequest = IntentSenderRequest.Builder(exception.resolution.intentSender).build()
                        locationSettingsLauncher.launch(intentSenderRequest)
                    } catch (sendEx: IntentSender.SendIntentException) {
                        Log.e(TAG, "Error showing GPS enable dialog", sendEx)
                    }
                }
            }
    }

    override fun onPause() {
        super.onPause()
        if (::myLocationOverlay.isInitialized) {
            myLocationOverlay.disableMyLocation()
        }
        if (::binding.isInitialized) {
            binding.mapView.onPause()
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        val json = try {
            JSONObject(String(event.data, Charsets.UTF_8))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse message on ${event.path}: ${e.message}")
            return
        }

        when (event.path) {
            "/fitguard/activity/ack" -> runOnUiThread {
                viewModel.onWatchAck(
                    ackSessionId = json.optString("session_id", ""),
                    status = json.optString("status", ""),
                    activityType = json.optString("activity_type", "")
                        .ifEmpty { null }
                )
            }
            "/fitguard/activity/stopped" -> runOnUiThread {
                viewModel.onWatchStopped(
                    stoppedSessionId = json.optString("session_id", ""),
                    reason = json.optString("reason", ""),
                    sequenceCount = json.optInt("sequence_count", 0)
                )
            }
            "/fitguard/activity/heartbeat" -> runOnUiThread {
                viewModel.onHeartbeat(
                    hbSessionId = json.optString("session_id", ""),
                    sequenceCount = json.optInt("sequence_count", 0),
                    elapsedS = json.optInt("elapsed_s", 0)
                )
            }
        }
    }

    private fun setupActivityTypeSelection() {
        binding.rgActivityType.setOnCheckedChangeListener { _, checkedId ->
            val isOther = checkedId == R.id.rbOther
            binding.tilCustomActivity.visibility = if (isOther) View.VISIBLE else View.GONE

            val type = when (checkedId) {
                R.id.rbTreadmill -> "Treadmill"
                R.id.rbStationary -> "Stationary Bike"
                R.id.rbWalking -> "Walking"
                R.id.rbRunning -> "Running"
                R.id.rbCycling -> "Cycling"
                R.id.rbOther -> binding.etCustomActivity.text?.toString()?.ifBlank { "Other" } ?: "Other"
                else -> "Walking"
            }
            viewModel.setActivityType(type)
        }
    }

    private fun setupStartStopButton() {
        binding.btnStartStop.setOnClickListener {
            when (viewModel.state.value) {
                ActivityTrackingViewModel.SessionState.IDLE -> {
                    startSessionWithPermissionCheck()
                }
                ActivityTrackingViewModel.SessionState.ACTIVE -> {
                    viewModel.stopSession()
                }
                else -> {} // CONNECTING or STOPPING - ignore
            }
        }
    }

    private fun startSessionWithPermissionCheck() {
        // Check location permission first (optional but enables route tracking)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }
        checkNotificationAndStart()
    }

    private fun checkNotificationAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            checkRecoveryAndStart()
        }
    }

    private fun checkRecoveryAndStart() {
        val recoveryPrefs = getSharedPreferences("recovery_state", MODE_PRIVATE)
        val sessionEndTime = recoveryPrefs.getLong("session_end_time", 0L)
        val restHours = recoveryPrefs.getInt("rest_hours", 0)

        if (sessionEndTime > 0L && restHours > 0) {
            val elapsedMs = System.currentTimeMillis() - sessionEndTime
            val restMs = restHours * 3_600_000L
            val percent = ((elapsedMs.toFloat() / restMs) * 100f).coerceIn(0f, 100f).toInt()

            if (percent < 100) {
                val hoursLeft = ((restMs - elapsedMs) / 3_600_000.0)
                val timeLeft = if (hoursLeft >= 1.0) {
                    String.format("%.1f hours", hoursLeft)
                } else {
                    String.format("%.0f minutes", hoursLeft * 60)
                }

                AlertDialog.Builder(this)
                    .setTitle("Recovery Incomplete")
                    .setMessage(
                        "You are only $percent% recovered from your last session. " +
                        "Full recovery is recommended in about $timeLeft.\n\n" +
                        "Starting a new session before full recovery may increase injury risk."
                    )
                    .setPositiveButton("Continue") { _, _ ->
                        // Carry over remaining recovery hours to next session
                        val remainingMs = restMs - elapsedMs
                        val remainingHours = (remainingMs / 3_600_000.0).toFloat()
                        recoveryPrefs.edit()
                            .putFloat("carryover_hours", remainingHours.coerceAtLeast(0f))
                            .apply()

                        val type = getSelectedActivityType()
                        viewModel.startSession(type)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                return
            }
        }

        val type = getSelectedActivityType()
        viewModel.startSession(type)
    }

    private fun getSelectedActivityType(): String {
        return when (binding.rgActivityType.checkedRadioButtonId) {
            R.id.rbTreadmill -> "Treadmill"
            R.id.rbStationary -> "Stationary Bike"
            R.id.rbWalking -> "Walking"
            R.id.rbRunning -> "Running"
            R.id.rbCycling -> "Cycling"
            R.id.rbOther -> binding.etCustomActivity.text?.toString()?.ifBlank { "Other" } ?: "Other"
            else -> "Walking"
        }
    }

    private fun observeViewModel() {
        viewModel.state.observe(this) { state ->
            when (state) {
                ActivityTrackingViewModel.SessionState.IDLE -> {
                    binding.btnStartStop.text = "Start Session"
                    binding.btnStartStop.isEnabled = true
                    binding.btnStartStop.setBackgroundColor(getColor(R.color.blue_primary))
                    binding.tvSessionStatus.text = "Idle"
                    binding.rgActivityType.isEnabled = true
                    setRadioGroupEnabled(true)
                    if (::myLocationOverlay.isInitialized) {
                        myLocationOverlay.enableFollowLocation()
                    }
                }
                ActivityTrackingViewModel.SessionState.CONNECTING -> {
                    binding.btnStartStop.text = "Connecting..."
                    binding.btnStartStop.isEnabled = false
                    binding.tvSessionStatus.text = "Connecting to watch..."
                    setRadioGroupEnabled(false)
                }
                ActivityTrackingViewModel.SessionState.ACTIVE -> {
                    binding.btnStartStop.text = "Stop Session"
                    binding.btnStartStop.isEnabled = true
                    binding.btnStartStop.setBackgroundColor(getColor(R.color.red_stop))
                    binding.tvSessionStatus.text = "Active - ${viewModel.activityType.value}"
                    setRadioGroupEnabled(false)
                    if (::myLocationOverlay.isInitialized) {
                        myLocationOverlay.disableFollowLocation()
                    }
                }
                ActivityTrackingViewModel.SessionState.STOPPING -> {
                    binding.btnStartStop.text = "Stopping..."
                    binding.btnStartStop.isEnabled = false
                    binding.tvSessionStatus.text = "Stopping session..."
                }
                null -> {}
            }
        }

        viewModel.elapsedSeconds.observe(this) { seconds ->
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            val s = seconds % 60
            binding.tvElapsedTime.text = String.format("%02d:%02d:%02d", h, m, s)
        }

        viewModel.error.observe(this) { errorMsg ->
            if (errorMsg != null) {
                binding.tvError.text = errorMsg
                binding.tvError.visibility = View.VISIBLE
            } else {
                binding.tvError.visibility = View.GONE
            }
        }

        viewModel.activityType.observe(this) { type ->
            val radioId = when (type) {
                "Treadmill" -> R.id.rbTreadmill
                "Stationary Bike" -> R.id.rbStationary
                "Walking" -> R.id.rbWalking
                "Running" -> R.id.rbRunning
                "Cycling" -> R.id.rbCycling
                else -> R.id.rbOther
            }
            if (binding.rgActivityType.checkedRadioButtonId != radioId) {
                binding.rgActivityType.check(radioId)
            }
            if (radioId == R.id.rbOther && type != "Other") {
                binding.etCustomActivity.setText(type)
            }
        }
    }

    private fun setRadioGroupEnabled(enabled: Boolean) {
        for (i in 0 until binding.rgActivityType.childCount) {
            binding.rgActivityType.getChildAt(i).isEnabled = enabled
        }
        binding.etCustomActivity.isEnabled = enabled
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun setupMap() {
        binding.mapView.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(16.0)
        }

        val locationProvider = GpsMyLocationProvider(this)
        myLocationOverlay = MyLocationNewOverlay(locationProvider, binding.mapView)
        myLocationOverlay.enableMyLocation()
        myLocationOverlay.enableFollowLocation()
        binding.mapView.overlays.add(myLocationOverlay)
    }

    private fun requestMapLocationPermission() {
        if (hasLocationPermission()) {
            checkLocationSettings()
        } else {
            mapLocationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun observeRoute() {
        viewModel.routePoints.observe(this) { points ->
            if (points.isEmpty()) {
                routePolyline?.let {
                    binding.mapView.overlays.remove(it)
                    routePolyline = null
                }
                binding.mapView.invalidate()
                return@observe
            }

            val geoPoints = points.map { GeoPoint(it.lat, it.lng) }

            routePolyline?.let { binding.mapView.overlays.remove(it) }

            routePolyline = Polyline().apply {
                setPoints(geoPoints)
                outlinePaint.color = Color.parseColor("#1565C0")
                outlinePaint.strokeWidth = 10f
                outlinePaint.isAntiAlias = true
            }
            binding.mapView.overlays.add(routePolyline)
            binding.mapView.controller.animateTo(geoPoints.last())
            binding.mapView.invalidate()
        }

        viewModel.distanceMeters.observe(this) { meters ->
            val km = meters / 1000.0
            binding.tvDistance.text = if (km < 1.0) {
                String.format("%.0f m", meters)
            } else {
                String.format("%.2f km", km)
            }
        }

        viewModel.paceMinPerKm.observe(this) { pace ->
            if (pace <= 0 || pace > 99) {
                binding.tvPace.text = "--:--"
            } else {
                val minutes = pace.toInt()
                val seconds = ((pace - minutes) * 60).toInt()
                binding.tvPace.text = String.format("%d:%02d", minutes, seconds)
            }
        }
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.selectedItemId = R.id.nav_activity

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    startActivity(Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    })
                    overridePendingTransition(0, 0)
                    finish()
                    true
                }
                R.id.nav_activity -> true
                R.id.nav_health -> {
                    startActivity(Intent(this, FatiguePredictionActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    })
                    overridePendingTransition(0, 0)
                    true
                }
                R.id.nav_profile -> {
                    startActivity(Intent(this, UserHomeActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    })
                    overridePendingTransition(0, 0)
                    true
                }
                else -> false
            }
        }
    }

    override fun onDestroy() {
        Wearable.getMessageClient(this).removeListener(this)
        if (::myLocationOverlay.isInitialized) {
            myLocationOverlay.disableMyLocation()
        }
        binding.mapView.onDetach()
        super.onDestroy()
    }
}
