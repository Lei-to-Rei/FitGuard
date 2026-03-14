package com.example.fitguard.features.profile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.fitguard.data.db.AppDatabase
import com.example.fitguard.data.model.UserProfile
import com.example.fitguard.data.processing.CsvWriter
import com.example.fitguard.data.repository.UserProfileRepository
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

class ProfileViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val repo = UserProfileRepository(db.userProfileDao(), FirebaseFirestore.getInstance())

    private var _profile: LiveData<UserProfile?>? = null
    val profile: LiveData<UserProfile?> get() = _profile ?: MutableLiveData(null)

    fun observeProfile(uid: String): LiveData<UserProfile?> {
        val liveData = repo.observeProfile(uid)
        _profile = liveData
        return liveData
    }

    fun saveProfile(profile: UserProfile) {
        viewModelScope.launch {
            repo.saveGoals(profile)
            CsvWriter.writeProfileCsv(profile)
        }
    }

    companion object {
        fun calculateBmi(weightKg: Float, heightCm: Float): Double {
            val heightM = heightCm / 100.0
            return if (heightM > 0) weightKg / (heightM * heightM) else 0.0
        }

        fun bmiCategory(bmi: Double): String = when {
            bmi < 18.5 -> "Underweight"
            bmi < 25.0 -> "Normal"
            bmi < 30.0 -> "Overweight"
            else -> "Obese"
        }

        fun fitnessLevelToScore(level: String): Int = when (level) {
            "Sedentary" -> 2
            "Lightly Active" -> 4
            "Moderately Active" -> 6
            "Active" -> 8
            "Very Active" -> 10
            else -> 0
        }

        fun scoreToFitnessLevel(score: Int): String = when {
            score <= 2 -> "Sedentary"
            score <= 4 -> "Lightly Active"
            score <= 6 -> "Moderately Active"
            score <= 8 -> "Active"
            else -> "Very Active"
        }

        fun fitnessLevelToStatus(level: String): String = when (level) {
            "Sedentary" -> "BEGINNER"
            "Lightly Active" -> "BEGINNER"
            "Moderately Active" -> "INTERMEDIATE"
            "Active" -> "ADVANCED"
            "Very Active" -> "ELITE"
            else -> "BEGINNER"
        }

        fun heartRateStatus(bpm: Int): String = when {
            bpm <= 0 -> "N/A"
            bpm < 60 -> "EXCELLENT"
            bpm < 70 -> "GOOD"
            bpm < 80 -> "AVERAGE"
            bpm < 90 -> "ABOVE AVG"
            else -> "HIGH"
        }
    }
}
