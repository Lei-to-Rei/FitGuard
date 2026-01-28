package com.example.fitguard.features.recommendations

import com.example.fitguard.databinding.ActivityFeaturePlaceholderBinding
import com.example.fitguard.ui.base.BaseFeatureActivity

class RecoveryRecommendationsActivity : BaseFeatureActivity<ActivityFeaturePlaceholderBinding>() {

    override fun getViewBinding() = ActivityFeaturePlaceholderBinding.inflate(layoutInflater)

    override fun getFeatureTitle() = "Recovery Recommendations"

    override fun setupFeature() {
        binding.apply {
            tvFeatureTitle.text = "Recovery Recommendations"
            tvFeatureDescription.text = """
                This feature will offer:
                • Personalized recovery tips
                • Rest day suggestions
                • Active recovery workouts
                • Nutrition recommendations
                • Sleep optimization advice
                • Hydration reminders
            """.trimIndent()
        }
    }
}