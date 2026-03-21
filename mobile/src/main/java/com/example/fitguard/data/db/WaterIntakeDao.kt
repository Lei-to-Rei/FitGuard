package com.example.fitguard.data.db

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.fitguard.data.model.WaterIntakeEntry

@Dao
interface WaterIntakeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: WaterIntakeEntry)

    @Query("SELECT * FROM water_intake WHERE dateMillis = :dateMillis AND userId = :userId LIMIT 1")
    fun getByDate(dateMillis: Long, userId: String): LiveData<WaterIntakeEntry?>

    @Query("SELECT * FROM water_intake WHERE dateMillis = :dateMillis AND userId = :userId LIMIT 1")
    suspend fun getByDateSync(dateMillis: Long, userId: String): WaterIntakeEntry?
}
