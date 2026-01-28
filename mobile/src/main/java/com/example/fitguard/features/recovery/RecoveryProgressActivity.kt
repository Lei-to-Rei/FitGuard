package com.example.fitguard.features.recovery

import com.example.fitguard.databinding.ActivityFeaturePlaceholderBinding
import com.example.fitguard.ui.base.BaseFeatureActivity

class RecoveryProgressActivity : BaseFeatureActivity<ActivityFeaturePlaceholderBinding>() {

    override fun getViewBinding() = ActivityFeaturePlaceholderBinding.inflate(layoutInflater)

    override fun getFeatureTitle() = "Recovery Progress"

    override fun setupFeature() {
        binding.apply {
            tvFeatureTitle.text = "Recovery Progress Tracking"
            tvFeatureDescription.text = """
                This feature will monitor:
                • Recovery score (1-100)
                • HRV trends
                • Resting heart rate
                • Sleep quality impact
                • Training readiness
                • Recovery timeline
            """.trimIndent()
        }
    }
}