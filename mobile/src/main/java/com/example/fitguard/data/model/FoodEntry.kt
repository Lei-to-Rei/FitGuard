package com.example.fitguard.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "food_entries")
data class FoodEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val servingSize: String,
    val calories: Int,
    val protein: Float,
    val carbs: Float,
    val fat: Float,
    val sodium: Float,
    val fiber: Float = 0f,
    val sugar: Float = 0f,
    val cholesterol: Float = 0f,
    val mealType: String,
    val dateMillis: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val isSaved: Boolean = false,
    val userId: String = "",
    val firestoreId: String = UUID.randomUUID().toString()
)
