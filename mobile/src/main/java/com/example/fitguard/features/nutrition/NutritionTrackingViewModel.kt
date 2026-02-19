package com.example.fitguard.features.nutrition

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import android.os.Environment
import com.example.fitguard.data.db.AppDatabase
import com.example.fitguard.data.model.DailyNutritionSummary
import com.example.fitguard.data.model.FoodEntry
import com.example.fitguard.data.model.NutritionGoals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class NutritionTrackingViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = AppDatabase.getInstance(application).foodEntryDao()

    val goals = NutritionGoals()

    private val _currentDateMillis = MutableLiveData(todayMidnightMillis())
    val currentDateMillis: LiveData<Long> = _currentDateMillis

    val entries: LiveData<List<FoodEntry>> = _currentDateMillis.switchMap { date ->
        dao.getEntriesByDate(date)
    }

    val dailyTotals: LiveData<DailyNutritionSummary> = _currentDateMillis.switchMap { date ->
        dao.getDailyTotals(date)
    }

    val savedFoods: LiveData<List<FoodEntry>> = dao.getSavedFoods()

    private val _exportStatus = MutableLiveData<String?>()
    val exportStatus: LiveData<String?> = _exportStatus

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
                isSaved = isSaved
            )
            dao.insert(entry)
        }
    }

    fun deleteFoodEntry(entry: FoodEntry) {
        viewModelScope.launch {
            dao.delete(entry)
        }
    }

    fun exportTodayToCsv() {
        viewModelScope.launch {
            try {
                val dateMillis = _currentDateMillis.value ?: todayMidnightMillis()
                val entries = dao.getEntriesByDateSync(dateMillis)

                if (entries.isEmpty()) {
                    _exportStatus.value = "No entries to export"
                    _exportStatus.value = null
                    return@launch
                }

                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(dateMillis))
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "FitGuard_Data"
                )

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
