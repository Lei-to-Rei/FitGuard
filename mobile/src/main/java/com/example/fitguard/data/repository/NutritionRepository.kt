package com.example.fitguard.data.repository

import android.util.Log
import androidx.lifecycle.LiveData
import com.example.fitguard.data.db.FoodEntryDao
import com.example.fitguard.data.model.DailyNutritionSummary
import com.example.fitguard.data.model.FoodEntry
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

class NutritionRepository(
    private val dao: FoodEntryDao,
    private val firestore: FirebaseFirestore
) {
    companion object {
        private const val TAG = "NutritionRepository"
    }

    suspend fun insert(entry: FoodEntry) {
        dao.insert(entry)
        if (entry.userId.isNotEmpty()) {
            try {
                firestore.collection("users")
                    .document(entry.userId)
                    .collection("food_entries")
                    .document(entry.firestoreId)
                    .set(entry.toFirestoreMap())
                    .await()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to push entry to Firestore", e)
            }
        }
    }

    suspend fun delete(entry: FoodEntry) {
        dao.delete(entry)
        if (entry.userId.isNotEmpty()) {
            try {
                firestore.collection("users")
                    .document(entry.userId)
                    .collection("food_entries")
                    .document(entry.firestoreId)
                    .delete()
                    .await()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete entry from Firestore", e)
            }
        }
    }

    fun getEntriesByDate(dateMillis: Long, userId: String): LiveData<List<FoodEntry>> {
        return dao.getEntriesByDate(dateMillis, userId)
    }

    fun getDailyTotals(dateMillis: Long, userId: String): LiveData<DailyNutritionSummary> {
        return dao.getDailyTotals(dateMillis, userId)
    }

    fun getSavedFoods(userId: String): LiveData<List<FoodEntry>> {
        return dao.getSavedFoods(userId)
    }

    suspend fun getEntriesByDateSync(dateMillis: Long, userId: String): List<FoodEntry> {
        return dao.getEntriesByDateSync(dateMillis, userId)
    }

    suspend fun syncFromFirestore(userId: String) {
        try {
            val snapshot = firestore.collection("users")
                .document(userId)
                .collection("food_entries")
                .get()
                .await()

            for (doc in snapshot.documents) {
                val firestoreId = doc.id
                val existing = dao.getByFirestoreId(firestoreId)
                if (existing != null) continue

                val entry = FoodEntry(
                    name = doc.getString("name") ?: "",
                    servingSize = doc.getString("servingSize") ?: "",
                    calories = (doc.getLong("calories") ?: 0).toInt(),
                    protein = (doc.getDouble("protein") ?: 0.0).toFloat(),
                    carbs = (doc.getDouble("carbs") ?: 0.0).toFloat(),
                    fat = (doc.getDouble("fat") ?: 0.0).toFloat(),
                    sodium = (doc.getDouble("sodium") ?: 0.0).toFloat(),
                    fiber = (doc.getDouble("fiber") ?: 0.0).toFloat(),
                    sugar = (doc.getDouble("sugar") ?: 0.0).toFloat(),
                    cholesterol = (doc.getDouble("cholesterol") ?: 0.0).toFloat(),
                    mealType = doc.getString("mealType") ?: "",
                    dateMillis = doc.getLong("dateMillis") ?: 0L,
                    createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis(),
                    isSaved = doc.getBoolean("isSaved") ?: false,
                    userId = userId,
                    firestoreId = firestoreId
                )
                dao.upsert(entry)
            }
            Log.d(TAG, "Synced ${snapshot.size()} entries from Firestore")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync from Firestore", e)
        }
    }

    private fun FoodEntry.toFirestoreMap(): Map<String, Any> = mapOf(
        "name" to name,
        "servingSize" to servingSize,
        "calories" to calories,
        "protein" to protein,
        "carbs" to carbs,
        "fat" to fat,
        "sodium" to sodium,
        "fiber" to fiber,
        "sugar" to sugar,
        "cholesterol" to cholesterol,
        "mealType" to mealType,
        "dateMillis" to dateMillis,
        "createdAt" to createdAt,
        "isSaved" to isSaved
    )
}
