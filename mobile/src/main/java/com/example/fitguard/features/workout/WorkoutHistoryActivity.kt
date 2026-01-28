package com.example.fitguard.features.workout

import com.example.fitguard.databinding.ActivityFeaturePlaceholderBinding
import com.example.fitguard.ui.base.BaseFeatureActivity

class WorkoutHistoryActivity : BaseFeatureActivity<ActivityFeaturePlaceholderBinding>() {

    override fun getViewBinding() = ActivityFeaturePlaceholderBinding.inflate(layoutInflater)

    override fun getFeatureTitle() = "Workout History"

    override fun setupFeature() {
        binding.apply {
            tvFeatureTitle.text = "Workout History & Insights"
            tvFeatureDescription.text = """
                This feature will show:
                • Complete workout history
                • Performance trends over time
                • Personal records
                • Weekly/monthly summaries
                • Progress charts
                • Training load analysis
            """.trimIndent()
        }
    }
}
