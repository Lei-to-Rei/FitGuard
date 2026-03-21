package com.example.fitguard.features.profile

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.fitguard.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        setupToggles()
        setupPreferences()
        setupDataManagement()
    }

    private fun setupToggles() {
        binding.switchActivity.setOnCheckedChangeListener { _, isChecked ->
            Toast.makeText(this, "Activity reminders ${if (isChecked) "enabled" else "disabled"}", Toast.LENGTH_SHORT).show()
        }
        binding.switchGoals.setOnCheckedChangeListener { _, isChecked ->
            Toast.makeText(this, "Goal achievements ${if (isChecked) "enabled" else "disabled"}", Toast.LENGTH_SHORT).show()
        }
        binding.switchRecovery.setOnCheckedChangeListener { _, isChecked ->
            Toast.makeText(this, "Recovery alerts ${if (isChecked) "enabled" else "disabled"}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupPreferences() {
        val prefs = getSharedPreferences("health_tracker_prefs", MODE_PRIVATE)

        binding.rgHeartRate.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                binding.rbHrManual.id -> "Manual"
                binding.rbHr10Min.id -> "Every 10 minutes"
                binding.rbHrContinuous.id -> "Continuous"
                else -> return@setOnCheckedChangeListener
            }
            prefs.edit().putString("hr_mode", mode).apply()
            Toast.makeText(this, "Heart Rate: $mode", Toast.LENGTH_SHORT).show()
        }

        binding.rgSpO2.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                binding.rbSpO2Manual.id -> "Manual"
                binding.rbSpO210Min.id -> "Every 10 minutes"
                else -> return@setOnCheckedChangeListener
            }
            prefs.edit().putString("spo2_mode", mode).apply()
            Toast.makeText(this, "SpO2: $mode", Toast.LENGTH_SHORT).show()
        }

        binding.rgSkinTemp.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                binding.rbSkinTempManual.id -> "Manual"
                binding.rbSkinTemp10Min.id -> "Every 10 minutes"
                else -> return@setOnCheckedChangeListener
            }
            prefs.edit().putString("skin_temp_mode", mode).apply()
            Toast.makeText(this, "Skin Temperature: $mode", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupDataManagement() {
        binding.menuExportData.setOnClickListener {
            Toast.makeText(this, "Export data coming soon", Toast.LENGTH_SHORT).show()
        }
        binding.menuDeleteAccount.setOnClickListener {
            Toast.makeText(this, "Delete account coming soon", Toast.LENGTH_SHORT).show()
        }
    }
}
