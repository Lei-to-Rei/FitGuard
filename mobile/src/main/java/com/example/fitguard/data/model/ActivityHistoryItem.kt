package com.example.fitguard.data.model

data class ActivityHistoryItem(
    val activityType: String,
    val startTimeMillis: Long,
    val durationMillis: Long,
    val sessionDirName: String,
    val totalDistanceMeters: Float = 0f,
    val avgPaceMinPerKm: Double = 0.0
)
