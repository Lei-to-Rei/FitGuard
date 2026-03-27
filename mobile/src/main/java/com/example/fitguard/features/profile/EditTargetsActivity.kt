package com.example.fitguard.features.profile

import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.fitguard.data.model.UserProfile
import com.example.fitguard.data.repository.AuthRepository
import com.example.fitguard.databinding.ActivityEditTargetsBinding

class EditTargetsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityEditTargetsBinding
    private val viewModel: ProfileViewModel by viewModels()
    private var currentProfile: UserProfile? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditTargetsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        val uid = AuthRepository.currentUser?.uid ?: return
        viewModel.observeProfile(uid).observe(this) { profile ->
            profile ?: return@observe
            currentProfile = profile
            binding.etCaloriesTarget.setText(profile.caloriesGoal.toString())
            binding.etWaterTarget.setText(profile.waterGoalGlasses.toString())
            binding.etSleepTarget.setText(profile.sleepGoalHours.toString())
            binding.etActivityTarget.setText(profile.activityGoalHours.toString())
        }

        binding.btnSave.setOnClickListener {
            val profile = currentProfile ?: return@setOnClickListener
            val calories = binding.etCaloriesTarget.text.toString().toIntOrNull() ?: profile.caloriesGoal
            val water = binding.etWaterTarget.text.toString().toIntOrNull() ?: profile.waterGoalGlasses
            val sleep = binding.etSleepTarget.text.toString().toFloatOrNull() ?: profile.sleepGoalHours
            val activity = binding.etActivityTarget.text.toString().toFloatOrNull() ?: profile.activityGoalHours

            val updated = profile.copy(
                caloriesGoal = calories,
                waterGoalGlasses = water,
                sleepGoalHours = sleep,
                activityGoalHours = activity
            )
            viewModel.saveProfile(updated)
            Toast.makeText(this, "Targets saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
