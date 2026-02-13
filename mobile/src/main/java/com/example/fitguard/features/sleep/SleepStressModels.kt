package com.example.fitguard.features.sleep

enum class SleepState { AWAKE, LIGHT_SLEEP, DEEP_SLEEP, UNKNOWN }

enum class StressLevel { LOW, MODERATE, HIGH, VERY_HIGH }

data class StressResult(
    val score: Int,           // 0-100 (0=relaxed, 100=high stress)
    val level: StressLevel,
    val rmssd: Double,
    val sdnn: Double,
    val hr: Double,
    val timestamp: Long
)

data class SleepResult(
    val state: SleepState,
    val confidence: Float,    // 0.0-1.0
    val hr: Double,
    val accelVariance: Double,
    val timestamp: Long
)
