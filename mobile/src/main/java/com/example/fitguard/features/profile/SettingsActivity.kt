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
        binding.rgAppearance.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                binding.rbPhoneDefault.id -> "Phone Default"
                binding.rbLightMode.id -> "Light Mode"
                binding.rbDarkMode.id -> "Dark Mode"
                else -> return@setOnCheckedChangeListener
            }
            Toast.makeText(this, "Appearance: $mode", Toast.LENGTH_SHORT).show()
        }

        binding.rgUnits.setOnCheckedChangeListener { _, checkedId ->
            val unit = when (checkedId) {
                binding.rbKilometers.id -> "Kilometers"
                binding.rbMiles.id -> "Miles"
                else -> return@setOnCheckedChangeListener
            }
            Toast.makeText(this, "Units: $unit", Toast.LENGTH_SHORT).show()
        }

        binding.rgTemperature.setOnCheckedChangeListener { _, checkedId ->
            val temp = when (checkedId) {
                binding.rbCelsius.id -> "Celsius"
                binding.rbFahrenheit.id -> "Fahrenheit"
                else -> return@setOnCheckedChangeListener
            }
            Toast.makeText(this, "Temperature: $temp", Toast.LENGTH_SHORT).show()
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
