package com.example.fitguard

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.fitguard.auth.LoginActivity
import com.example.fitguard.data.db.AppDatabase
import com.example.fitguard.data.repository.AuthRepository
import com.example.fitguard.data.repository.UserProfileRepository
import com.example.fitguard.onboarding.OnboardingActivity
import com.example.fitguard.onboarding.ProfileSetupActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        lifecycleScope.launch {
            delay(1500)
            navigateNext()
        }
    }

    private suspend fun navigateNext() {
        val prefs = getSharedPreferences("fitguard_prefs", MODE_PRIVATE)
        val onboardingComplete = prefs.getBoolean("onboarding_complete", false)

        when {
            !onboardingComplete -> goTo(OnboardingActivity::class.java)
            !AuthRepository.isUserLoggedIn() -> goTo(LoginActivity::class.java)
            else -> {
                val firebaseUser = FirebaseAuth.getInstance().currentUser
                if (firebaseUser != null) {
                    val profileComplete = withContext(Dispatchers.IO) {
                        val dao = AppDatabase.getInstance(this@SplashActivity).userProfileDao()
                        val repo = UserProfileRepository(dao, FirebaseFirestore.getInstance())
                        val profile = repo.loadOrCreateProfile(firebaseUser)
                        prefs.edit()
                            .putBoolean("profile_complete", profile.profileComplete)
                            .apply()
                        profile.profileComplete
                    }
                    if (profileComplete) goTo(MainActivity::class.java)
                    else goTo(ProfileSetupActivity::class.java)
                } else {
                    goTo(LoginActivity::class.java)
                }
            }
        }
    }

    private fun goTo(activity: Class<*>) {
        startActivity(Intent(this, activity))
        finish()
    }
}
