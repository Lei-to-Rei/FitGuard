package com.example.fitguard.auth

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.fitguard.data.repository.AuthRepository
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.launch

class AuthViewModel : ViewModel() {
    // No need to create instance, use object directly
    private val _authState = MutableLiveData<AuthState>()
    val authState: LiveData<AuthState> = _authState

    private val _currentUser = MutableLiveData<FirebaseUser?>()
    val currentUser: LiveData<FirebaseUser?> = _currentUser

    init {
        checkAuthStatus()
    }

    private fun checkAuthStatus() {
        _currentUser.value = AuthRepository.currentUser
    }

    fun signUp(email: String, password: String, displayName: String) {
        if (!validateInput(email, password, displayName)) {
            return
        }

        _authState.value = AuthState.Loading
        viewModelScope.launch {
            val result = AuthRepository.signUp(email, password, displayName)
            result.fold(
                onSuccess = { user ->
                    _currentUser.value = user
                    _authState.value = AuthState.Success("Account created successfully!")
                },
                onFailure = { exception ->
                    _authState.value = AuthState.Error(exception.message ?: "Unknown error occurred")
                }
            )
        }
    }

    fun signIn(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _authState.value = AuthState.Error("Email and password cannot be empty")
            return
        }

        _authState.value = AuthState.Loading
        viewModelScope.launch {
            val result = AuthRepository.signIn(email, password)
            result.fold(
                onSuccess = { user ->
                    _currentUser.value = user
                    _authState.value = AuthState.Success("Welcome back!")
                },
                onFailure = { exception ->
                    _authState.value = AuthState.Error(exception.message ?: "Login failed")
                }
            )
        }
    }

    fun sendPasswordReset(email: String) {
        if (email.isBlank()) {
            _authState.value = AuthState.Error("Email cannot be empty")
            return
        }

        _authState.value = AuthState.Loading
        viewModelScope.launch {
            val result = AuthRepository.sendPasswordResetEmail(email)
            result.fold(
                onSuccess = {
                    _authState.value = AuthState.Success("Password reset email sent!")
                },
                onFailure = { exception ->
                    _authState.value = AuthState.Error(exception.message ?: "Failed to send reset email")
                }
            )
        }
    }

    fun signOut() {
        AuthRepository.signOut()
        _currentUser.value = null
        _authState.value = AuthState.Success("Signed out successfully")
    }

    private fun validateInput(email: String, password: String, displayName: String): Boolean {
        return when {
            email.isBlank() || password.isBlank() || displayName.isBlank() -> {
                _authState.value = AuthState.Error("All fields are required")
                false
            }
            !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches() -> {
                _authState.value = AuthState.Error("Invalid email format")
                false
            }
            password.length < 6 -> {
                _authState.value = AuthState.Error("Password must be at least 6 characters")
                false
            }
            else -> true
        }
    }
}

sealed class AuthState {
    object Loading : AuthState()
    data class Success(val message: String) : AuthState()
    data class Error(val message: String) : AuthState()
}