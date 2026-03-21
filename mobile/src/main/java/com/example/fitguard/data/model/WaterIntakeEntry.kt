package com.example.fitguard.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "water_intake")
data class WaterIntakeEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val userId: String = "",
    val dateMillis: Long,
    val glassCount: Int = 0,
    val goalGlasses: Int = 8
)
