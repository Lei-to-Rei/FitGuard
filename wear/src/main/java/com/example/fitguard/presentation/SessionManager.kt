package com.example.fitguard.presentation

object SessionManager {
    enum class SessionType { NONE, ACTIVITY, SLEEP_STRESS }

    @Volatile
    var currentSession: SessionType = SessionType.NONE
        private set

    var healthTrackerManager: HealthTrackerManager? = null
    var sleepStressSequenceManager: SleepStressSequenceManager? = null

    val isHealthServiceReady: Boolean
        get() = healthTrackerManager != null

    @Synchronized
    fun tryStartActivity(): Boolean {
        if (currentSession != SessionType.NONE) return false
        currentSession = SessionType.ACTIVITY
        return true
    }

    @Synchronized
    fun tryStartSleepStress(): Boolean {
        if (currentSession != SessionType.NONE) return false
        currentSession = SessionType.SLEEP_STRESS
        return true
    }

    @Synchronized
    fun endSession() {
        currentSession = SessionType.NONE
    }
}
