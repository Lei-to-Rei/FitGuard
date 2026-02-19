package com.example.fitguard.data.db

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.fitguard.data.model.DailyNutritionSummary
import com.example.fitguard.data.model.FoodEntry

@Dao
interface FoodEntryDao {

    @Insert
    suspend fun insert(entry: FoodEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: FoodEntry)

    @Delete
    suspend fun delete(entry: FoodEntry)

    @Query("SELECT * FROM food_entries WHERE dateMillis = :dateMillis AND userId = :userId ORDER BY createdAt ASC")
    fun getEntriesByDate(dateMillis: Long, userId: String): LiveData<List<FoodEntry>>

    @Query("""
        SELECT
            COALESCE(SUM(calories), 0) AS totalCalories,
            COALESCE(SUM(protein), 0) AS totalProtein,
            COALESCE(SUM(carbs), 0) AS totalCarbs,
            COALESCE(SUM(fat), 0) AS totalFat,
            COALESCE(SUM(sodium), 0) AS totalSodium
        FROM food_entries
        WHERE dateMillis = :dateMillis AND userId = :userId
    """)
    fun getDailyTotals(dateMillis: Long, userId: String): LiveData<DailyNutritionSummary>

    @Query("SELECT * FROM food_entries WHERE isSaved = 1 AND userId = :userId GROUP BY name ORDER BY name ASC")
    fun getSavedFoods(userId: String): LiveData<List<FoodEntry>>

    @Query("SELECT * FROM food_entries WHERE dateMillis = :dateMillis AND userId = :userId ORDER BY createdAt ASC")
    suspend fun getEntriesByDateSync(dateMillis: Long, userId: String): List<FoodEntry>

    @Query("SELECT * FROM food_entries WHERE firestoreId = :firestoreId LIMIT 1")
    suspend fun getByFirestoreId(firestoreId: String): FoodEntry?
}
