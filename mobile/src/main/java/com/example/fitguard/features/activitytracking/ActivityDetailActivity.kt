package com.example.fitguard.features.activitytracking

import android.graphics.Color
import android.view.MotionEvent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.fitguard.R
import com.example.fitguard.data.repository.SplitData
import com.example.fitguard.databinding.ActivityActivityDetailBinding
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ActivityDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityActivityDetailBinding
    private val viewModel: ActivityDetailViewModel by viewModels()

    companion object {
        const val EXTRA_SESSION_DIR = "session_dir"
        const val EXTRA_USER_ID = "user_id"
        const val EXTRA_ACTIVITY_TYPE = "activity_type"
        const val EXTRA_START_TIME = "start_time"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        binding = ActivityActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val sessionDir = intent.getStringExtra(EXTRA_SESSION_DIR) ?: ""
        val userId = intent.getStringExtra(EXTRA_USER_ID) ?: ""
        val activityType = intent.getStringExtra(EXTRA_ACTIVITY_TYPE) ?: ""
        val startTime = intent.getLongExtra(EXTRA_START_TIME, 0L)

        binding.btnBack.setOnClickListener { finish() }

        setupMap()
        setupHeader(startTime)
        setupActivityInfo(activityType)
        observeViewModel()

        viewModel.loadDetail(userId, sessionDir, activityType, startTime)
    }

    private fun setupHeader(startTime: Long) {
        if (startTime > 0) {
            val fmt = SimpleDateFormat("MMMM dd, yyyy - h:mm a", Locale.US)
            binding.tvHeaderSubtitle.text = fmt.format(Date(startTime))
        }
    }

    private fun setupActivityInfo(activityType: String) {
        binding.tvActivityType.text = activityType
        val iconRes = when (activityType.lowercase(Locale.US)) {
            "treadmill" -> R.drawable.ic_treadmill
            "stationary bike" -> R.drawable.ic_stationary_bike
            "running" -> R.drawable.ic_user_fast_running
            "walking" -> R.drawable.ic_activity
            "cycling" -> R.drawable.ic_bike
            else -> R.drawable.ic_workout
        }
        binding.ivActivityIcon.setImageResource(iconRes)
    }

    private fun setupMap() {
        binding.mapView.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(16.0)
        }

        binding.mapView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> v.parent.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }

    private fun observeViewModel() {
        viewModel.routePoints.observe(this) { points ->
            if (points.isEmpty()) return@observe

            val geoPoints = points.map { GeoPoint(it.lat, it.lng) }

            val polyline = Polyline().apply {
                setPoints(geoPoints)
                outlinePaint.color = Color.parseColor("#1565C0")
                outlinePaint.strokeWidth = 10f
                outlinePaint.isAntiAlias = true
            }
            binding.mapView.overlays.add(polyline)

            // Start marker (green)
            val startMarker = Marker(binding.mapView).apply {
                position = geoPoints.first()
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "Start"
                icon = createCircleDrawable(Color.parseColor("#4CAF50"))
            }
            binding.mapView.overlays.add(startMarker)

            // End marker (red)
            val endMarker = Marker(binding.mapView).apply {
                position = geoPoints.last()
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "End"
                icon = createCircleDrawable(Color.parseColor("#D32F2F"))
            }
            binding.mapView.overlays.add(endMarker)

            // Zoom to fit route
            if (geoPoints.size >= 2) {
                val box = BoundingBox.fromGeoPoints(geoPoints)
                binding.mapView.post {
                    binding.mapView.zoomToBoundingBox(box.increaseByScale(1.3f), true)
                }
            } else {
                binding.mapView.controller.animateTo(geoPoints.first())
            }
            binding.mapView.invalidate()
        }

        viewModel.summary.observe(this) { summary ->
            if (summary.totalDistanceM > 0f) {
                val km = summary.totalDistanceM / 1000.0
                binding.tvDetailDistance.text = if (km < 1.0) {
                    String.format(Locale.US, "%.0f m", summary.totalDistanceM)
                } else {
                    String.format(Locale.US, "%.2f km", km)
                }
            }

            if (summary.durationMs > 0) {
                val totalSec = summary.durationMs / 1000
                val h = totalSec / 3600
                val m = (totalSec % 3600) / 60
                val s = totalSec % 60
                binding.tvDetailTime.text = if (h > 0) {
                    String.format(Locale.US, "%d:%02d:%02d", h, m, s)
                } else {
                    String.format(Locale.US, "%d:%02d", m, s)
                }
            }

            if (summary.avgPaceMinPerKm > 0 && summary.avgPaceMinPerKm <= 99) {
                val min = summary.avgPaceMinPerKm.toInt()
                val sec = ((summary.avgPaceMinPerKm - min) * 60).toInt()
                binding.tvDetailPace.text = String.format(Locale.US, "%d:%02d /km", min, sec)
            }
        }

        viewModel.avgHr.observe(this) { avg ->
            if (avg > 0f) {
                binding.tvDetailAvgHr.text = String.format(Locale.US, "%.0f bpm", avg)
            }
        }

        viewModel.fatigueLevel.observe(this) { level ->
            binding.tvDetailFatigueLevel.text = level
        }

        viewModel.splits.observe(this) { splits ->
            if (splits.isNotEmpty()) {
                binding.cardSplits.visibility = View.VISIBLE
                populateSplitsTable(splits)
                binding.splitsChart.setData(splits)
            }
        }

        viewModel.hrData.observe(this) { hrPoints ->
            if (hrPoints.size >= 2) {
                binding.cardHeartRate.visibility = View.VISIBLE
                binding.hrChart.setData(hrPoints, viewModel.avgHr.value ?: 0f)
            }
        }

        viewModel.fatigueData.observe(this) { fatiguePoints ->
            if (fatiguePoints.size >= 2) {
                binding.cardFatigue.visibility = View.VISIBLE
                binding.fatigueChart.setData(fatiguePoints)
            }
        }
    }

    private fun populateSplitsTable(splits: List<SplitData>) {
        binding.llSplitsTable.removeAllViews()
        for (split in splits) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
            }

            val label = TextView(this).apply {
                text = "Km ${split.kmIndex}"
                textSize = 14f
                setTextColor(resources.getColor(R.color.text_dark, theme))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val min = split.paceMinPerKm.toInt()
            val sec = ((split.paceMinPerKm - min) * 60).toInt()
            val value = TextView(this).apply {
                text = String.format(Locale.US, "%d:%02d /km", min, sec)
                textSize = 14f
                setTextColor(resources.getColor(R.color.text_secondary, theme))
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            row.addView(label)
            row.addView(value)
            binding.llSplitsTable.addView(row)
        }
    }

    private fun createCircleDrawable(color: Int): android.graphics.drawable.Drawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(4, Color.WHITE)
            setSize(32, 32)
        }
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onDestroy() {
        binding.mapView.onDetach()
        super.onDestroy()
    }
}
