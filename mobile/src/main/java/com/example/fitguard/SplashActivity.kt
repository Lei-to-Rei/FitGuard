package com.example.fitguard

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.example.fitguard.auth.LoginActivity
import com.example.fitguard.data.repository.AuthRepository
import com.example.fitguard.onboarding.OnboardingActivity
import com.example.fitguard.onboarding.ProfileSetupActivity

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        Handler(Looper.getMainLooper()).postDelayed({
            navigateNext()
        }, 1500)
    }

    private fun navigateNext() {
        val prefs = getSharedPreferences("fitguard_prefs", MODE_PRIVATE)
        val onboardingComplete = prefs.getBoolean("onboarding_complete", false)

        val destination = when {
            !onboardingComplete -> OnboardingActivity::class.java
            !AuthRepository.isUserLoggedIn() -> LoginActivity::class.java
            !prefs.getBoolean("profile_complete", false) -> ProfileSetupActivity::class.java
            else -> MainActivity::class.java
        }

        startActivity(Intent(this, destination))
        finish()
    }
}
