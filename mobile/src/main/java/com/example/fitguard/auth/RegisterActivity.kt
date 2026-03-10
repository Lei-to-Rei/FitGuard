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
import com.example.fitguard.R
import com.example.fitguard.data.repository.AuthRepository
import com.example.fitguard.databinding.ActivityRegisterBinding
import com.example.fitguard.onboarding.ProfileSetupActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException

class RegisterActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRegisterBinding
    private val viewModel: AuthViewModel by viewModels()

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
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
                    // Google sign-up goes straight to profile setup
                    Toast.makeText(this, state.message, Toast.LENGTH_SHORT).show()
                    navigateToProfileSetup()
                }
                is AuthState.VerificationRequired -> {
                    showLoading(false)
                    showVerificationSuccessDialog(state.message)
                }
                is AuthState.Error -> {
                    showLoading(false)
                    Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
                }
                is AuthState.Info -> {
                    showLoading(false)
                    Toast.makeText(this, state.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupClickListeners() {
        binding.btnRegister.setOnClickListener {
            val name = binding.etName.text.toString().trim()
            val email = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()
            val confirmPassword = binding.etConfirmPassword.text.toString().trim()

            if (password != confirmPassword) {
                Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            viewModel.signUp(email, password, name)
        }

        binding.btnGoogleSignUp.setOnClickListener {
            val signInIntent = AuthRepository.getGoogleSignInClient()?.signInIntent
            if (signInIntent != null) {
                googleSignInLauncher.launch(signInIntent)
            } else {
                Toast.makeText(this, "Google Sign-In not initialized", Toast.LENGTH_SHORT).show()
            }
        }

        binding.tvLogin.setOnClickListener {
            finish()
        }

        binding.btnBack.setOnClickListener {
            finish()
        }
    }

    private fun showVerificationSuccessDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Verify Your Email")
            .setMessage("$message\n\nPlease check your inbox and verify your email before logging in.")
            .setPositiveButton("Go to Login") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun navigateToProfileSetup() {
        startActivity(Intent(this, ProfileSetupActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    private fun showLoading(isLoading: Boolean) {
        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.btnRegister.isEnabled = !isLoading
        binding.btnGoogleSignUp.isEnabled = !isLoading
    }
}
