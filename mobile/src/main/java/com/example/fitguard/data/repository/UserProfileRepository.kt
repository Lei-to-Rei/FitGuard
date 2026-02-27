package com.example.fitguard.data.repository

import android.util.Log
import androidx.lifecycle.LiveData
import com.example.fitguard.data.db.UserProfileDao
import com.example.fitguard.data.model.UserProfile
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

class UserProfileRepository(
    private val dao: UserProfileDao,
    private val firestore: FirebaseFirestore
) {
    companion object {
        private const val TAG = "UserProfileRepository"
    }

    suspend fun loadOrCreateProfile(firebaseUser: FirebaseUser): UserProfile {
        val uid = firebaseUser.uid

        // Check Room first
        val local = dao.getByUid(uid)
        if (local != null) return local

        // Check Firestore
        try {
            val doc = firestore.collection("users")
                .document(uid)
                .collection("profile")
                .document("data")
                .get()
                .await()

            if (doc.exists()) {
                val profile = UserProfile(
                    uid = uid,
                    displayName = doc.getString("displayName") ?: firebaseUser.displayName ?: "",
                    email = doc.getString("email") ?: firebaseUser.email ?: "",
                    caloriesGoal = (doc.getLong("caloriesGoal") ?: 2000).toInt(),
                    proteinGoal = (doc.getDouble("proteinGoal") ?: 50.0).toFloat(),
                    carbsGoal = (doc.getDouble("carbsGoal") ?: 300.0).toFloat(),
                    fatGoal = (doc.getDouble("fatGoal") ?: 65.0).toFloat(),
                    sodiumGoal = (doc.getDouble("sodiumGoal") ?: 2300.0).toFloat()
                )
                dao.upsert(profile)
                return profile
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch profile from Firestore", e)
        }

        // Create default
        val defaultProfile = UserProfile(
            uid = uid,
            displayName = firebaseUser.displayName ?: "",
            email = firebaseUser.email ?: ""
        )
        dao.upsert(defaultProfile)
        saveToFirestore(defaultProfile)
        return defaultProfile
    }

    fun observeProfile(uid: String): LiveData<UserProfile?> {
        return dao.observeByUid(uid)
    }

    suspend fun saveGoals(profile: UserProfile) {
        dao.upsert(profile)
        saveToFirestore(profile)
    }

    private suspend fun saveToFirestore(profile: UserProfile) {
        try {
            firestore.collection("users")
                .document(profile.uid)
                .collection("profile")
                .document("data")
                .set(profile.toFirestoreMap())
                .await()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save profile to Firestore", e)
        }
    }

    private fun UserProfile.toFirestoreMap(): Map<String, Any> = mapOf(
        "displayName" to displayName,
        "email" to email,
        "caloriesGoal" to caloriesGoal,
        "proteinGoal" to proteinGoal,
        "carbsGoal" to carbsGoal,
        "fatGoal" to fatGoal,
        "sodiumGoal" to sodiumGoal
    )
}
