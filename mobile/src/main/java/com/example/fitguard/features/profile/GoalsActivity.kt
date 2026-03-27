package com.example.fitguard.features.profile

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.fitguard.data.repository.AuthRepository
import com.example.fitguard.databinding.ActivityGoalsBinding
import com.example.fitguard.features.activitytracking.ActivityHistoryViewModel
import com.example.fitguard.features.nutrition.NutritionTrackingViewModel
import java.util.Calendar

class GoalsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGoalsBinding
    private val viewModel: ProfileViewModel by viewModels()
    private val nutritionViewModel: NutritionTrackingViewModel by viewModels()
    private val activityHistoryViewModel: ActivityHistoryViewModel by viewModels()

    private var caloriesGoal = 0
    private var waterGoal = 0
    private var activityGoalMs = 0L

    private var caloriesPct = 0
    private var waterPct = 0
    private var sleepPct = 0
    private var activityPct = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGoalsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnEditTargets.setOnClickListener {
            startActivity(Intent(this, EditTargetsActivity::class.java))
        }

        val uid = AuthRepository.currentUser?.uid ?: return
        viewModel.observeProfile(uid).observe(this) { profile ->
            profile ?: return@observe

            binding.tvPrimaryGoal.text = profile.fitnessGoal.ifEmpty { "Not set" }
            binding.tvActivityLevel.text = profile.fitnessLevel.ifEmpty { "Not set" }

            caloriesGoal = profile.caloriesGoal
            waterGoal = profile.waterGoalGlasses
            activityGoalMs = (profile.activityGoalHours * 3_600_000f).toLong()
            refreshCaloriesDisplay()
            refreshWaterDisplay()
            refreshActivityDisplay()

            sleepPct = 0
            binding.tvSleepProgress.text = "0 / ${profile.sleepGoalHours} hours"
            binding.tvSleepPercent.text = "0%"
            binding.progressSleep.progress = 0
            binding.tvSleepRemaining.text = "${profile.sleepGoalHours} hours to go"

            refreshOverallDisplay()
        }

        nutritionViewModel.dailyTotals.observe(this) { refreshCaloriesDisplay() }
        nutritionViewModel.waterIntake.observe(this) { refreshWaterDisplay() }
        activityHistoryViewModel.sessions.observe(this) { refreshActivityDisplay() }
    }

    override fun onResume() {
        super.onResume()
        activityHistoryViewModel.loadSessions()
    }

    private fun refreshCaloriesDisplay() {
        val eaten = nutritionViewModel.dailyTotals.value?.totalCalories ?: 0
        caloriesPct = if (caloriesGoal > 0) ((eaten * 100) / caloriesGoal).coerceAtMost(100) else 0
        val remaining = (caloriesGoal - eaten).coerceAtLeast(0)
        binding.tvCaloriesProgress.text = "$eaten / $caloriesGoal kcal"
        binding.tvCaloriesPercent.text = "$caloriesPct%"
        binding.progressCalories.progress = caloriesPct
        binding.tvCaloriesRemaining.text = "$remaining kcal remaining"
        refreshOverallDisplay()
    }

    private fun refreshWaterDisplay() {
        val drank = nutritionViewModel.waterIntake.value?.glassCount ?: 0
        waterPct = if (waterGoal > 0) ((drank * 100) / waterGoal).coerceAtMost(100) else 0
        val remaining = (waterGoal - drank).coerceAtLeast(0)
        binding.tvWaterProgress.text = "$drank / $waterGoal glasses"
        binding.tvWaterPercent.text = "$waterPct%"
        binding.progressWater.progress = waterPct
        binding.tvWaterRemaining.text = "$remaining glasses to go"
        refreshOverallDisplay()
    }

    private fun refreshActivityDisplay() {
        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val tomorrowStart = todayStart + 86_400_000L

        val totalMs = activityHistoryViewModel.sessions.value
            ?.filter { it.startTimeMillis in todayStart until tomorrowStart }
            ?.sumOf { it.durationMillis } ?: 0L

        activityPct = if (activityGoalMs > 0) ((totalMs * 100) / activityGoalMs).toInt().coerceAtMost(100) else 0
        val remainingMs = (activityGoalMs - totalMs).coerceAtLeast(0L)

        binding.tvActivityProgress.text = "${formatDuration(totalMs)} / ${formatDuration(activityGoalMs)}"
        binding.tvActivityPercent.text = "$activityPct%"
        binding.progressActivity.progress = activityPct
        binding.tvActivityRemaining.text = if (remainingMs > 0) "${formatDuration(remainingMs)} to go" else "Goal reached!"
        refreshOverallDisplay()
    }

    private fun refreshOverallDisplay() {
        val allPcts = listOf(caloriesPct, waterPct, sleepPct, activityPct)
        val overall = allPcts.average().toInt()
        val completed = allPcts.count { it >= 100 }
        binding.tvOverallPercent.text = "$overall%"
        binding.progressOverall.progress = overall
        binding.tvGoalsCompleted.text = "$completed of 4 goals\ncompleted"
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
               else String.format("%d:%02d", m, s)
    }
}
