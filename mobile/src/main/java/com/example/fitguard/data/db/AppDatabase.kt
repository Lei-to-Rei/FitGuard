package com.example.fitguard.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.fitguard.data.model.FoodEntry
import com.example.fitguard.data.model.UserProfile

@Database(entities = [FoodEntry::class, UserProfile::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun foodEntryDao(): FoodEntryDao
    abstract fun userProfileDao(): UserProfileDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE food_entries ADD COLUMN userId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE food_entries ADD COLUMN firestoreId TEXT NOT NULL DEFAULT ''")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS user_profiles (
                        uid TEXT NOT NULL PRIMARY KEY,
                        displayName TEXT NOT NULL DEFAULT '',
                        email TEXT NOT NULL DEFAULT '',
                        caloriesGoal INTEGER NOT NULL DEFAULT 2000,
                        proteinGoal REAL NOT NULL DEFAULT 50.0,
                        carbsGoal REAL NOT NULL DEFAULT 300.0,
                        fatGoal REAL NOT NULL DEFAULT 65.0,
                        sodiumGoal REAL NOT NULL DEFAULT 2300.0
                    )
                """.trimIndent())
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "fitguard_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build().also { INSTANCE = it }
            }
        }
    }
}
