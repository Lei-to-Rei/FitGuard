package com.example.fitguard.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.fitguard.data.model.FoodEntry
import com.example.fitguard.data.model.UserProfile
import com.example.fitguard.data.model.WaterIntakeEntry

@Database(entities = [FoodEntry::class, UserProfile::class, WaterIntakeEntry::class], version = 6, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun foodEntryDao(): FoodEntryDao
    abstract fun userProfileDao(): UserProfileDao
    abstract fun waterIntakeDao(): WaterIntakeDao

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

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE user_profiles ADD COLUMN gender TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE user_profiles ADD COLUMN dateOfBirth TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE user_profiles ADD COLUMN heightCm REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE user_profiles ADD COLUMN currentWeightKg REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE user_profiles ADD COLUMN targetWeightKg REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE user_profiles ADD COLUMN fitnessGoal TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE user_profiles ADD COLUMN profileComplete INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE user_profiles ADD COLUMN fitnessLevel TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE user_profiles ADD COLUMN restingHeartRateBpm INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS water_intake (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        userId TEXT NOT NULL DEFAULT '',
                        dateMillis INTEGER NOT NULL,
                        glassCount INTEGER NOT NULL DEFAULT 0,
                        goalGlasses INTEGER NOT NULL DEFAULT 8
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build().also { INSTANCE = it }
            }
        }
    }
}
