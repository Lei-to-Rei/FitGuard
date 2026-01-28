package com.example.fitguard.features.metrics

import com.example.fitguard.databinding.ActivityFeaturePlaceholderBinding
import com.example.fitguard.ui.base.BaseFeatureActivity

class MetricsMonitoringActivity : BaseFeatureActivity<ActivityFeaturePlaceholderBinding>() {

    override fun getViewBinding() = ActivityFeaturePlaceholderBinding.inflate(layoutInflater)

    override fun getFeatureTitle() = "Metrics Monitoring"

    override fun setupFeature() {
        binding.apply {
            tvFeatureTitle.text = "Metrics Monitoring"
            tvFeatureDescription.text = """
                This feature will display:
                • Real-time heart rate from PPG sensor
                • SpO2 (Blood Oxygen) levels
                • Heart Rate Variability (HRV)
                • Stress levels
                • Live graphs and charts
                • Historical trends
            """.trimIndent()
        }
    }
}