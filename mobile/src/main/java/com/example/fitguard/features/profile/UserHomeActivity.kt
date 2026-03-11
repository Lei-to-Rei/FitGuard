package com.example.fitguard.features.profile

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.fitguard.MainActivity
import com.example.fitguard.auth.LoginActivity
import com.example.fitguard.data.repository.AuthRepository
import com.example.fitguard.databinding.ActivityUserHomeBinding
import com.example.fitguard.R
import com.example.fitguard.features.fatigue.FatiguePredictionActivity
import com.example.fitguard.features.workout.WorkoutHistoryActivity

class UserHomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityUserHomeBinding

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
                    startActivity(Intent(this, WorkoutHistoryActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    })
                    overridePendingTransition(0, 0)
                    true
                }
                R.id.nav_stats -> {
                    Toast.makeText(this, "Stats coming soon", Toast.LENGTH_SHORT).show()
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
