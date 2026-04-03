package com.example.fitguard.features.profile

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.fitguard.data.repository.AuthRepository
import com.example.fitguard.databinding.ActivityPersonalBaselineBinding
import java.util.Locale

class PersonalBaselineActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPersonalBaselineBinding
    private val viewModel: ProfileViewModel by viewModels()
    private var profileLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPersonalBaselineBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        val uid = AuthRepository.currentUser?.uid ?: return

        viewModel.observeProfile(uid).observe(this) { profile ->
            profile ?: return@observe
            if (profileLoaded) return@observe
            profileLoaded = true

            // Populate fields
            if (profile.currentWeightKg > 0) {
                binding.etWeight.setText(String.format(Locale.US, "%.1f", profile.currentWeightKg))
            }
            if (profile.heightCm > 0) {
                binding.etHeight.setText(String.format(Locale.US, "%.0f", profile.heightCm))
            }

            val score = ProfileViewModel.fitnessLevelToScore(profile.fitnessLevel)
            if (score > 0) {
                binding.etFitnessLevel.setText(score.toString())
            }

            updateBmi()
        }

        // Real-time BMI calculation
        val bmiWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { updateBmi() }
        }
        binding.etWeight.addTextChangedListener(bmiWatcher)
        binding.etHeight.addTextChangedListener(bmiWatcher)

        // Save button
        binding.btnSave.setOnClickListener {
            val currentProfile = viewModel.profile.value ?: return@setOnClickListener

            val weight = binding.etWeight.text.toString().trim().toFloatOrNull() ?: currentProfile.currentWeightKg
            val height = binding.etHeight.text.toString().trim().toFloatOrNull() ?: currentProfile.heightCm
            val fitnessScore = binding.etFitnessLevel.text.toString().trim().toIntOrNull() ?: 0
            val fitnessLevel = ProfileViewModel.scoreToFitnessLevel(fitnessScore)

            val updated = currentProfile.copy(
                currentWeightKg = weight,
                heightCm = height,
                fitnessLevel = fitnessLevel
            )

            viewModel.saveProfile(updated)
            Toast.makeText(this, "Baseline saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun updateBmi() {
        val weight = binding.etWeight.text.toString().trim().toFloatOrNull() ?: 0f
        val height = binding.etHeight.text.toString().trim().toFloatOrNull() ?: 0f
        val bmi = ProfileViewModel.calculateBmi(weight, height)
        if (bmi > 0) {
            binding.tvBmiValue.text = String.format(Locale.US, "%.1f", bmi)
        } else {
            binding.tvBmiValue.text = "--"
        }
    }
}
