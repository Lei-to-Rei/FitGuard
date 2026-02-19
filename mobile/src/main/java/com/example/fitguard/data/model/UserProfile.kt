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
    val sodiumGoal: Float = 2300f
)
