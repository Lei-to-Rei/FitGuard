package com.example.fitguard.features.sleep

import com.example.fitguard.databinding.ActivityFeaturePlaceholderBinding
import com.example.fitguard.ui.base.BaseFeatureActivity

class SleepStressActivity : BaseFeatureActivity<ActivityFeaturePlaceholderBinding>() {

    override fun getViewBinding() = ActivityFeaturePlaceholderBinding.inflate(layoutInflater)

    override fun getFeatureTitle() = "Sleep & Stress"

    override fun setupFeature() {
        binding.apply {
            tvFeatureTitle.text = "Sleep & Stress Tracking"
            tvFeatureDescription.text = """
                This feature will track:
                • Sleep duration and quality
                • Sleep stages (deep, light, REM)
                • Sleep schedule consistency
                • Stress level monitoring
                • HRV-based stress analysis
                • Sleep recommendations
            """.trimIndent()
        }
    }
}