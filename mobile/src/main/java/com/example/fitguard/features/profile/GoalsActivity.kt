package com.example.fitguard.features.profile

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.fitguard.data.repository.AuthRepository
import com.example.fitguard.databinding.ActivityGoalsBinding

class GoalsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGoalsBinding
    private val viewModel: ProfileViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGoalsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnEditTargets.setOnClickListener {
            startActivity(Intent(this, EditTargetsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        val uid = AuthRepository.currentUser?.uid ?: return
        viewModel.observeProfile(uid).observe(this) { profile ->
            profile ?: return@observe

            // Primary Goal card
            binding.tvPrimaryGoal.text = profile.fitnessGoal.ifEmpty { "Not set" }
            binding.tvActivityLevel.text = profile.fitnessLevel.ifEmpty { "Not set" }

            // Target values
            binding.tvCaloriesProgress.text = "0 / ${profile.caloriesGoal} kcal"
            binding.tvCaloriesPercent.text = "0%"
            binding.progressCalories.progress = 0
            binding.tvCaloriesRemaining.text = "${profile.caloriesGoal} kcal remaining"

            binding.tvWaterProgress.text = "0 / ${profile.waterGoalGlasses} glasses"
            binding.tvWaterPercent.text = "0%"
            binding.progressWater.progress = 0
            binding.tvWaterRemaining.text = "${profile.waterGoalGlasses} glasses to go"

            binding.tvSleepProgress.text = "0 / ${profile.sleepGoalHours} hours"
            binding.tvSleepPercent.text = "0%"
            binding.progressSleep.progress = 0
            binding.tvSleepRemaining.text = "${profile.sleepGoalHours} hours to go"

            binding.tvActivityProgress.text = "0 / ${profile.activityGoalHours} hour"
            binding.tvActivityPercent.text = "0%"
            binding.progressActivity.progress = 0
            binding.tvActivityRemaining.text = "${profile.activityGoalHours} hours to go"
        }
    }
}
