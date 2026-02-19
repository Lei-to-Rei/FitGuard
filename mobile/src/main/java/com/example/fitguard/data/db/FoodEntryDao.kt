package com.example.fitguard.data.db

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.example.fitguard.data.model.DailyNutritionSummary
import com.example.fitguard.data.model.FoodEntry

@Dao
interface FoodEntryDao {

    @Insert
    suspend fun insert(entry: FoodEntry)

    @Delete
    suspend fun delete(entry: FoodEntry)

    @Query("SELECT * FROM food_entries WHERE dateMillis = :dateMillis ORDER BY createdAt ASC")
    fun getEntriesByDate(dateMillis: Long): LiveData<List<FoodEntry>>

    @Query("""
        SELECT
            COALESCE(SUM(calories), 0) AS totalCalories,
            COALESCE(SUM(protein), 0) AS totalProtein,
            COALESCE(SUM(carbs), 0) AS totalCarbs,
            COALESCE(SUM(fat), 0) AS totalFat,
            COALESCE(SUM(sodium), 0) AS totalSodium
        FROM food_entries
        WHERE dateMillis = :dateMillis
    """)
    fun getDailyTotals(dateMillis: Long): LiveData<DailyNutritionSummary>

    @Query("SELECT * FROM food_entries WHERE isSaved = 1 GROUP BY name ORDER BY name ASC")
    fun getSavedFoods(): LiveData<List<FoodEntry>>

    @Query("SELECT * FROM food_entries WHERE dateMillis = :dateMillis ORDER BY createdAt ASC")
    suspend fun getEntriesByDateSync(dateMillis: Long): List<FoodEntry>
}
