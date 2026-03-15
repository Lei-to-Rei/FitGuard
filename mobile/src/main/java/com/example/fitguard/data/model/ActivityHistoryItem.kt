package com.example.fitguard.data.model

data class ActivityHistoryItem(
    val activityType: String,
    val startTimeMillis: Long,
    val durationMillis: Long,
    val sessionDirName: String
)
