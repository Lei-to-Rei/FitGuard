package com.example.fitguard.auth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.fitguard.MainActivity
import com.example.fitguard.R
import com.example.fitguard.data.db.AppDatabase
import com.example.fitguard.data.repository.AuthRepository
import com.example.fitguard.data.repository.UserProfileRepository
import com.example.fitguard.databinding.ActivityLoginBinding
import com.example.fitguard.onboarding.ProfileSetupActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private val viewModel: AuthViewModel by viewModels()

    // Google Sign-In launcher
    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                viewModel.signInWithGoogle(account)
            } catch (e: ApiException) {
                Toast.makeText(this, "Google Sign-In failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Google Sign-In with your Web Client ID
        viewModel.initGoogleSignIn(this, getString(R.string.default_web_client_id))

        setupObservers()
        setupClickListeners()
    }

    private fun setupObservers() {
        viewModel.authState.observe(this) { state ->
            when (state) {
                is AuthState.Loading -> {
                    showLoading(true)
                }
                is AuthState.Success -> {
                    showLoading(false)
                    Toast.makeText(this, state.message, Toast.LENGTH_SHORT).show()
                    navigateAfterAuth()
                }
                is AuthState.VerificationRequired -> {
                    showLoading(false)
                    showVerificationDialog(state.message)
                }
                is AuthState.Error -> {
                    showLoading(false)
                    Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
                }
                is AuthState.Info -> {
                    showLoading(false)
                    Toast.makeText(this, state.message, Toast.LENGTH_SHORT).show()
                }
                is AuthState.GoogleAccountDetected -> {
                    showLoading(false)
                    showGoogleAccountDialog(state.message)
                }
                is AuthState.SignInFailed -> {
                    showLoading(false)
                    showSignInFailedDialog(state.message)
                }
            }
        }
    }

    private fun setupClickListeners() {
        binding.btnLogin.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()
            viewModel.signIn(email, password)
        }

        binding.btnGoogleSignIn.setOnClickListener {
            startGoogleSignIn()
        }

        binding.tvSignUp.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        binding.tvForgotPassword.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            if (email.isNotEmpty()) {
                viewModel.sendPasswordReset(email)
            } else {
                Toast.makeText(this, "Please enter your email", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startGoogleSignIn() {
        val signInIntent = AuthRepository.getGoogleSignInClient()?.signInIntent
        if (signInIntent != null) {
            googleSignInLauncher.launch(signInIntent)
        } else {
            Toast.makeText(this, "Google Sign-In not initialized", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSignInFailedDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Sign-In Failed")
            .setMessage(message)
            .setPositiveButton("Try Google Sign-In") { _, _ ->
                startGoogleSignIn()
            }
            .setNegativeButton("OK", null)
            .show()
    }

    private fun showGoogleAccountDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Google Account Detected")
            .setMessage(message)
            .setPositiveButton("Sign in with Google") { _, _ ->
                startGoogleSignIn()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showVerificationDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Email Verification Required")
            .setMessage(message)
            .setPositiveButton("Resend Email") { _, _ ->
                viewModel.resendVerificationEmail()
            }
            .setNegativeButton("I've Verified") { _, _ ->
                viewModel.checkEmailVerification()
            }
            .setNeutralButton("OK", null)
            .show()
    }

    private fun showLoading(isLoading: Boolean) {
        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !isLoading
        binding.btnGoogleSignIn.isEnabled = !isLoading
    }

    private fun navigateAfterAuth() {
        showLoading(true)
        lifecycleScope.launch {
            val firebaseUser = FirebaseAuth.getInstance().currentUser
            val profileComplete = if (firebaseUser != null) {
                withContext(Dispatchers.IO) {
                    val dao = AppDatabase.getInstance(this@LoginActivity).userProfileDao()
                    val repo = UserProfileRepository(dao, FirebaseFirestore.getInstance())
                    val profile = repo.loadOrCreateProfile(firebaseUser)
                    // Sync SharedPreferences with DB
                    getSharedPreferences("fitguard_prefs", MODE_PRIVATE)
                        .edit()
                        .putBoolean("profile_complete", profile.profileComplete)
                        .apply()
                    profile.profileComplete
                }
            } else {
                false
            }
            showLoading(false)
            val destination = if (profileComplete) {
                MainActivity::class.java
            } else {
                ProfileSetupActivity::class.java
            }
            startActivity(Intent(this@LoginActivity, destination))
            finish()
        }
    }
}
