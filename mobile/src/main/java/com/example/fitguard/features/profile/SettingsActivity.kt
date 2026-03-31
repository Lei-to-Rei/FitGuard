package com.example.fitguard.features.profile

import android.graphics.Color
import android.os.Bundle
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.fitguard.R
import com.example.fitguard.databinding.ActivitySettingsBinding
import com.example.fitguard.services.WearableDataListenerService
import org.json.JSONObject

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        setupToggles()
        restorePreferences()
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

    private fun restorePreferences() {
        val prefs = getSharedPreferences("health_tracker_prefs", MODE_PRIVATE)

        when (prefs.getString("hr_mode", "Manual")) {
            "Manual" -> binding.rgHeartRate.check(binding.rbHrManual.id)
            "Every 10 minutes" -> binding.rgHeartRate.check(binding.rbHr10Min.id)
            "Continuous" -> binding.rgHeartRate.check(binding.rbHrContinuous.id)
        }

        when (prefs.getString("spo2_mode", "Manual")) {
            "Manual" -> binding.rgSpO2.check(binding.rbSpO2Manual.id)
            "Every 10 minutes" -> binding.rgSpO2.check(binding.rbSpO210Min.id)
        }

        when (prefs.getString("skin_temp_mode", "Manual")) {
            "Manual" -> binding.rgSkinTemp.check(binding.rbSkinTempManual.id)
            "Every 10 minutes" -> binding.rgSkinTemp.check(binding.rbSkinTemp10Min.id)
        }

        // Set initial text colors based on restored selection
        updateRadioGroupColors(binding.rgHeartRate, binding.rgHeartRate.checkedRadioButtonId)
        updateRadioGroupColors(binding.rgSpO2, binding.rgSpO2.checkedRadioButtonId)
        updateRadioGroupColors(binding.rgSkinTemp, binding.rgSkinTemp.checkedRadioButtonId)
    }

    private fun updateRadioGroupColors(group: RadioGroup, checkedId: Int) {
        for (i in 0 until group.childCount) {
            val rb = group.getChildAt(i) as? RadioButton ?: continue
            rb.setTextColor(
                if (rb.id == checkedId) Color.parseColor("#6EDB34")
                else getColor(R.color.text_dark)
            )
        }
    }

    private fun setupPreferences() {
        val prefs = getSharedPreferences("health_tracker_prefs", MODE_PRIVATE)

        binding.rgHeartRate.setOnCheckedChangeListener { group, checkedId ->
            val mode = when (checkedId) {
                binding.rbHrManual.id -> "Manual"
                binding.rbHr10Min.id -> "Every 10 minutes"
                binding.rbHrContinuous.id -> "Continuous"
                else -> return@setOnCheckedChangeListener
            }
            prefs.edit().putString("hr_mode", mode).apply()
            if (mode != "Manual") {
                prefs.edit().putBoolean("switch_hr", true).apply()
            }
            updateRadioGroupColors(group, checkedId)
            updateServiceState()
            Toast.makeText(this, "Heart Rate: $mode", Toast.LENGTH_SHORT).show()
        }

        binding.rgSpO2.setOnCheckedChangeListener { group, checkedId ->
            val mode = when (checkedId) {
                binding.rbSpO2Manual.id -> "Manual"
                binding.rbSpO210Min.id -> "Every 10 minutes"
                else -> return@setOnCheckedChangeListener
            }
            prefs.edit().putString("spo2_mode", mode).apply()
            if (mode != "Manual") {
                prefs.edit().putBoolean("switch_spo2", true).apply()
            }
            updateRadioGroupColors(group, checkedId)
            updateServiceState()
            Toast.makeText(this, "SpO2: $mode", Toast.LENGTH_SHORT).show()
        }

        binding.rgSkinTemp.setOnCheckedChangeListener { group, checkedId ->
            val mode = when (checkedId) {
                binding.rbSkinTempManual.id -> "Manual"
                binding.rbSkinTemp10Min.id -> "Every 10 minutes"
                else -> return@setOnCheckedChangeListener
            }
            prefs.edit().putString("skin_temp_mode", mode).apply()
            if (mode != "Manual") {
                prefs.edit().putBoolean("switch_skin_temp", true).apply()
            }
            updateRadioGroupColors(group, checkedId)
            updateServiceState()
            Toast.makeText(this, "Skin Temperature: $mode", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateServiceState() {
        val schedule = WearableDataListenerService.buildScheduleJson(this)
        val json = JSONObject(schedule)
        val needsSchedule =
            (json.optBoolean("hr_enabled") && json.optString("hr_mode") != "Manual") ||
            (json.optBoolean("spo2_enabled") && json.optString("spo2_mode") != "Manual") ||
            (json.optBoolean("skin_temp_enabled") && json.optString("skin_temp_mode") != "Manual") ||
            (json.optBoolean("accel_enabled") && json.optString("accel_mode") != "Manual")

        if (needsSchedule) {
            WearableDataListenerService.sendScheduleToWatch(this, schedule)
        } else {
            WearableDataListenerService.clearWatchSchedule(this)
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
