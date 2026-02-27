package com.example.fitguard.features.nutrition

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import com.example.fitguard.data.db.AppDatabase
import com.example.fitguard.data.processing.CsvWriter
import com.example.fitguard.data.model.DailyNutritionSummary
import com.example.fitguard.data.model.FoodEntry
import com.example.fitguard.data.model.NutritionGoals
import com.example.fitguard.data.repository.NutritionRepository
import com.example.fitguard.data.repository.UserProfileRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class NutritionTrackingViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val firestore = FirebaseFirestore.getInstance()
    private val nutritionRepo = NutritionRepository(db.foodEntryDao(), firestore)
    private val profileRepo = UserProfileRepository(db.userProfileDao(), firestore)

    private val userId: String
        get() = FirebaseAuth.getInstance().currentUser?.uid ?: ""

    private val _goalsLiveData = MutableLiveData(NutritionGoals())
    val goals: LiveData<NutritionGoals> = _goalsLiveData

    private val _currentDateMillis = MutableLiveData(todayMidnightMillis())
    val currentDateMillis: LiveData<Long> = _currentDateMillis

    val entries: LiveData<List<FoodEntry>> = _currentDateMillis.switchMap { date ->
        nutritionRepo.getEntriesByDate(date, userId)
    }

    val dailyTotals: LiveData<DailyNutritionSummary> = _currentDateMillis.switchMap { date ->
        nutritionRepo.getDailyTotals(date, userId)
    }

    val savedFoods: LiveData<List<FoodEntry>> = nutritionRepo.getSavedFoods(userId)

    private val _exportStatus = MutableLiveData<String?>()
    val exportStatus: LiveData<String?> = _exportStatus

    init {
        viewModelScope.launch {
            val firebaseUser = FirebaseAuth.getInstance().currentUser
            if (firebaseUser != null) {
                val profile = profileRepo.loadOrCreateProfile(firebaseUser)
                _goalsLiveData.value = NutritionGoals(
                    calories = profile.caloriesGoal,
                    protein = profile.proteinGoal,
                    carbs = profile.carbsGoal,
                    fat = profile.fatGoal,
                    sodium = profile.sodiumGoal
                )
                nutritionRepo.syncFromFirestore(firebaseUser.uid)
            }
        }
    }

    fun addFoodEntry(
        name: String,
        servingSize: String,
        calories: Int,
        protein: Float,
        carbs: Float,
        fat: Float,
        sodium: Float,
        fiber: Float,
        sugar: Float,
        cholesterol: Float,
        mealType: String,
        isSaved: Boolean
    ) {
        viewModelScope.launch {
            val entry = FoodEntry(
                name = name,
                servingSize = servingSize,
                calories = calories,
                protein = protein,
                carbs = carbs,
                fat = fat,
                sodium = sodium,
                fiber = fiber,
                sugar = sugar,
                cholesterol = cholesterol,
                mealType = mealType,
                dateMillis = _currentDateMillis.value ?: todayMidnightMillis(),
                isSaved = isSaved,
                userId = userId
            )
            nutritionRepo.insert(entry)
        }
    }

    fun deleteFoodEntry(entry: FoodEntry) {
        viewModelScope.launch {
            nutritionRepo.delete(entry)
        }
    }

    fun exportTodayToCsv() {
        viewModelScope.launch {
            try {
                val dateMillis = _currentDateMillis.value ?: todayMidnightMillis()
                val entries = nutritionRepo.getEntriesByDateSync(dateMillis, userId)

                if (entries.isEmpty()) {
                    _exportStatus.value = "No entries to export"
                    _exportStatus.value = null
                    return@launch
                }

                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(dateMillis))
                val dir = CsvWriter.getOutputDir(userId)

                withContext(Dispatchers.IO) {
                    if (!dir.exists()) dir.mkdirs()
                    val file = File(dir, "nutrition_$dateStr.csv")
                    file.bufferedWriter().use { writer ->
                        writer.write("meal_type,food_name,serving_size,calories,protein_g,carbs_g,fat_g,sodium_mg,fiber_g,sugar_g,cholesterol_mg")
                        writer.newLine()
                        for (entry in entries) {
                            val servingSafe = entry.servingSize.replace(",", ";")
                            val nameSafe = entry.name.replace(",", ";")
                            writer.write("${entry.mealType},$nameSafe,$servingSafe,${entry.calories},${entry.protein},${entry.carbs},${entry.fat},${entry.sodium},${entry.fiber},${entry.sugar},${entry.cholesterol}")
                            writer.newLine()
                        }
                    }
                }

                _exportStatus.value = "Exported to Downloads/FitGuard_Data/nutrition_$dateStr.csv"
                _exportStatus.value = null
            } catch (e: Exception) {
                _exportStatus.value = "Export failed: ${e.message}"
                _exportStatus.value = null
            }
        }
    }

    private fun todayMidnightMillis(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
