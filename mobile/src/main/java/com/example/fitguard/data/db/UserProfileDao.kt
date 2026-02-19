package com.example.fitguard.data.db

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.fitguard.data.model.UserProfile

@Dao
interface UserProfileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: UserProfile)

    @Query("SELECT * FROM user_profiles WHERE uid = :uid")
    suspend fun getByUid(uid: String): UserProfile?

    @Query("SELECT * FROM user_profiles WHERE uid = :uid")
    fun observeByUid(uid: String): LiveData<UserProfile?>
}
