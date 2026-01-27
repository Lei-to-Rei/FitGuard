package com.example.fitguard.features.activity

import com.example.fitguard.databinding.ActivityFeaturePlaceholderBinding
import com.example.fitguard.ui.base.BaseFeatureActivity

class ActivityTrackingActivity : BaseFeatureActivity<ActivityFeaturePlaceholderBinding>() {

    override fun getViewBinding() = ActivityFeaturePlaceholderBinding.inflate(layoutInflater)

    override fun getFeatureTitle() = "Activity Tracking"

    override fun setupFeature() {
        binding.apply {
            tvFeatureTitle.text = "GPS-Based Activity Tracking"
            tvFeatureDescription.text = """
                This feature will include:
                • Real-time GPS tracking
                • Route mapping on Google Maps
                • Distance, pace, and speed tracking
                • Elevation changes
                • Live statistics
                • Workout summaries
            """.trimIndent()
        }
    }
}