package com.example.fitguard.features.profile

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.fitguard.MainActivity
import com.example.fitguard.auth.LoginActivity
import com.example.fitguard.data.repository.AuthRepository
import com.example.fitguard.databinding.ActivityUserHomeBinding
import com.example.fitguard.R
import com.example.fitguard.features.fatigue.FatiguePredictionActivity
import com.example.fitguard.features.activitytracking.ActivityTrackingActivity
import java.util.Locale

class UserHomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityUserHomeBinding
    private val viewModel: ProfileViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUserInfo()
        setupMenuItems()
        setupBottomNavigation()
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            binding.bottomNavigation.selectedItemId = R.id.nav_profile
        }

        val uid = AuthRepository.currentUser?.uid ?: return
        viewModel.observeProfile(uid).observe(this) { profile ->
            profile ?: return@observe

            // Heart Rate
            if (profile.restingHeartRateBpm > 0) {
                binding.tvHeartRateValue.text = "${profile.restingHeartRateBpm} bpm"
            } else {
                binding.tvHeartRateValue.text = "-- bpm"
            }
            binding.tvHeartRateStatus.text = ProfileViewModel.heartRateStatus(profile.restingHeartRateBpm)

            // Weight
            if (profile.currentWeightKg > 0) {
                binding.tvWeightValue.text = String.format(Locale.US, "%.1f kg", profile.currentWeightKg)
            } else {
                binding.tvWeightValue.text = "-- kg"
            }

            // BMI-based weight status
            val bmi = ProfileViewModel.calculateBmi(profile.currentWeightKg, profile.heightCm)
            val bmiCat = ProfileViewModel.bmiCategory(bmi)
            binding.tvWeightStatus.text = bmiCat.uppercase(Locale.US)

            // Height
            if (profile.heightCm > 0) {
                binding.tvHeightValue.text = String.format(Locale.US, "%.0f cm", profile.heightCm)
            } else {
                binding.tvHeightValue.text = "-- cm"
            }

            // Fitness Level
            val score = ProfileViewModel.fitnessLevelToScore(profile.fitnessLevel)
            binding.tvFitnessValue.text = "$score/10"
            binding.tvFitnessStatus.text = ProfileViewModel.fitnessLevelToStatus(profile.fitnessLevel)

            // BMI
            if (bmi > 0) {
                binding.tvBmiValue.text = String.format(Locale.US, "%.1f", bmi)
            } else {
                binding.tvBmiValue.text = "--"
            }
            binding.tvBmiStatus.text = bmiCat.uppercase(Locale.US)
        }
    }

    private fun setupUserInfo() {
        val user = AuthRepository.currentUser
        val displayName = user?.displayName ?: user?.email?.substringBefore("@") ?: "User"
        val email = user?.email ?: ""
        val initials = displayName.split(" ")
            .mapNotNull { it.firstOrNull()?.uppercaseChar() }
            .joinToString("")
            .take(2)

        binding.tvUserName.text = displayName
        binding.tvUserEmail.text = email
        binding.tvAvatarInitials.text = initials.ifEmpty { "U" }
    }

    private fun setupMenuItems() {
        binding.menuSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.menuGoals.setOnClickListener {
            Toast.makeText(this, "Goals coming soon", Toast.LENGTH_SHORT).show()
        }

        binding.menuConnectedDevices.setOnClickListener {
            startActivity(Intent(this, ConnectedDevicesActivity::class.java))
        }

        binding.menuSupport.setOnClickListener {
            startActivity(Intent(this, SupportFeedbackActivity::class.java))
        }

        binding.btnEditBaseline.setOnClickListener {
            startActivity(Intent(this, PersonalBaselineActivity::class.java))
        }

        binding.menuLogout.setOnClickListener {
            AuthRepository.signOut()
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.selectedItemId = R.id.nav_profile

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    startActivity(Intent(this, com.example.fitguard.MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    })
                    overridePendingTransition(0, 0)
                    finish()
                    true
                }
                R.id.nav_activity -> {
                    startActivity(Intent(this, ActivityTrackingActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    })
                    overridePendingTransition(0, 0)
                    true
                }
                R.id.nav_health -> {
                    startActivity(Intent(this, FatiguePredictionActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    })
                    overridePendingTransition(0, 0)
                    true
                }
                R.id.nav_profile -> true
                else -> false
            }
        }
    }
}
