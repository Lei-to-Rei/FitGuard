package com.example.fitguard.features.nutrition

import com.example.fitguard.databinding.ActivityFeaturePlaceholderBinding
import com.example.fitguard.ui.base.BaseFeatureActivity

class NutritionTrackingActivity : BaseFeatureActivity<ActivityFeaturePlaceholderBinding>() {

    override fun getViewBinding() = ActivityFeaturePlaceholderBinding.inflate(layoutInflater)

    override fun getFeatureTitle() = "Nutrition Tracking"

    override fun setupFeature() {
        binding.apply {
            tvFeatureTitle.text = "Nutrition Tracking"
            tvFeatureDescription.text = """
                This feature will provide:
                • Meal logging
                • Calorie tracking
                • Macronutrient breakdown
                • Water intake monitoring
                • Daily nutrition goals
                • Food database integration
            """.trimIndent()
        }
    }
}