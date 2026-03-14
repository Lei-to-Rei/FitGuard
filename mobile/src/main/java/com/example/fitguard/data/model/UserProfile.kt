package com.example.fitguard.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_profiles")
data class UserProfile(
    @PrimaryKey val uid: String,
    val displayName: String = "",
    val email: String = "",
    val caloriesGoal: Int = 2000,
    val proteinGoal: Float = 50f,
    val carbsGoal: Float = 300f,
    val fatGoal: Float = 65f,
    val sodiumGoal: Float = 2300f,
    val gender: String = "",
    val dateOfBirth: String = "",
    val heightCm: Float = 0f,
    val currentWeightKg: Float = 0f,
    val targetWeightKg: Float = 0f,
    val fitnessGoal: String = "",
    val fitnessLevel: String = "",
    val restingHeartRateBpm: Int = 0,
    val profileComplete: Boolean = false
)
