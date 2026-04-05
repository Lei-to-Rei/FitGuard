package com.example.fitguard.features.activitytracking

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.fitguard.data.model.ActivityHistoryItem
import com.example.fitguard.data.repository.ActivityHistoryRepository
import com.example.fitguard.data.repository.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ActivityHistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val _sessions = MutableLiveData<List<ActivityHistoryItem>>(emptyList())
    val sessions: LiveData<List<ActivityHistoryItem>> = _sessions

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _isEmpty = MutableLiveData(false)
    val isEmpty: LiveData<Boolean> = _isEmpty

    fun loadSessions() {
        val userId = AuthRepository.currentUser?.uid ?: return
        _isLoading.value = true

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // Upload local-only sessions to Firestore first
                ActivityHistoryRepository.syncToFirestore(userId)
                // Then download any sessions missing locally (e.g. new device)
                ActivityHistoryRepository.syncFromFirestore(userId)
            }
            val result = withContext(Dispatchers.IO) {
                ActivityHistoryRepository.loadSessions(userId)
            }
            _sessions.value = result
            _isEmpty.value = result.isEmpty()
            _isLoading.value = false
        }
    }

    fun deleteSession(sessionDirName: String) {
        val userId = AuthRepository.currentUser?.uid ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                ActivityHistoryRepository.deleteSession(userId, sessionDirName)
            }
            // Remove from current list immediately
            val updated = _sessions.value?.filter { it.sessionDirName != sessionDirName } ?: emptyList()
            _sessions.value = updated
            _isEmpty.value = updated.isEmpty()
        }
    }
}
