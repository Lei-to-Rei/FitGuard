package com.example.fitguard.features.fatigue

import com.example.fitguard.databinding.ActivityFeaturePlaceholderBinding
import com.example.fitguard.ui.base.BaseFeatureActivity

class FatiguePredictionActivity : BaseFeatureActivity<ActivityFeaturePlaceholderBinding>() {

    override fun getViewBinding() = ActivityFeaturePlaceholderBinding.inflate(layoutInflater)

    override fun getFeatureTitle() = "Fatigue Prediction"

    override fun setupFeature() {
        binding.apply {
            tvFeatureTitle.text = "Fatigue Prediction (CNN–LSTM)"
            tvFeatureDescription.text = """
                This AI-powered feature will:
                • Predict fatigue levels
                • Analyze workout patterns
                • Use CNN-LSTM neural networks
                • Provide early warnings
                • Suggest optimal training times
                • Prevent overtraining
            """.trimIndent()
        }
    }
}