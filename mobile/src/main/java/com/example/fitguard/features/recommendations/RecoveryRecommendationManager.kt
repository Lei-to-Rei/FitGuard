package com.example.fitguard.features.recommendations

import android.util.Log

object RecoveryRecommendationManager {
    private const val TAG = "RecoveryRecManager"
    private const val SESSION_START_WINDOW_COUNT = 5
    private const val ALERT_COOLDOWN_MS = 120_000L // 2 minutes

    // Session-start reference (average of first 5 predictions)
    private val earlyHRValues = mutableListOf<Double>()
    private val earlyRMSSDValues = mutableListOf<Double>()
    private var sessionStartHR: Double = 0.0
    private var sessionStartRMSSD: Double = 0.0
    private var hasSessionStart: Boolean = false

    // Running session stats
    private var sessionStartTime: Long = 0L
    private var peakSmoothedPHigh: Double = 0.0
    private var totalPredictions: Int = 0
    private var predictionsInHigh: Int = 0
    private var predictionsInCritical: Int = 0
    private val allHRValues = mutableListOf<Double>()

    // Active recovery alert tracking
    private var lastAlertedLevel: Int = -1
    private var lastAlertTimestamp: Long = 0L

    // History of active recovery alerts during this session
    val activeRecoveryHistory: List<ActiveRecovery> get() = _activeRecoveryHistory.toList()
    private val _activeRecoveryHistory = mutableListOf<ActiveRecovery>()

    fun startSession() {
        earlyHRValues.clear()
        earlyRMSSDValues.clear()
        sessionStartHR = 0.0
        sessionStartRMSSD = 0.0
        hasSessionStart = false
        sessionStartTime = System.currentTimeMillis()
        peakSmoothedPHigh = 0.0
        totalPredictions = 0
        predictionsInHigh = 0
        predictionsInCritical = 0
        allHRValues.clear()
        lastAlertedLevel = -1
        lastAlertTimestamp = 0L
        _activeRecoveryHistory.clear()
        Log.d(TAG, "Session started")
    }

    fun onPrediction(smoothedPHigh: Double, currentHR: Double, currentRMSSD: Double) {
        // Build session-start reference from first 5 predictions
        if (!hasSessionStart) {
            earlyHRValues.add(currentHR)
            earlyRMSSDValues.add(currentRMSSD)
            if (earlyHRValues.size >= SESSION_START_WINDOW_COUNT) {
                sessionStartHR = earlyHRValues.average()
                sessionStartRMSSD = earlyRMSSDValues.average()
                hasSessionStart = true
                Log.d(TAG, "Session-start reference: HR=${String.format("%.0f", sessionStartHR)}, " +
                        "RMSSD=${String.format("%.1f", sessionStartRMSSD)}")
            }
        }

        // Update running stats
        totalPredictions++
        allHRValues.add(currentHR)
        if (smoothedPHigh >= 0.50) predictionsInHigh++
        if (smoothedPHigh >= 0.75) predictionsInCritical++
        if (smoothedPHigh > peakSmoothedPHigh) peakSmoothedPHigh = smoothedPHigh
    }

    fun checkActiveRecovery(
        smoothedPHigh: Double,
        currentHR: Double,
        currentRMSSD: Double
    ): ActiveRecovery? {
        val currentLevel = when {
            smoothedPHigh < 0.25 -> 0
            smoothedPHigh < 0.50 -> 1
            smoothedPHigh < 0.75 -> 2
            else -> 3
        }

        // Level 0 (Mild) never triggers
        if (currentLevel == 0) return null

        // Only trigger on level INCREASE
        if (currentLevel <= lastAlertedLevel) return null

        // Respect cooldown
        val now = System.currentTimeMillis()
        if (now - lastAlertTimestamp < ALERT_COOLDOWN_MS) return null

        lastAlertedLevel = currentLevel
        lastAlertTimestamp = now

        val sessionMinutes = ((now - sessionStartTime) / 60_000).toInt()
        val recovery = generateActiveRecovery(currentLevel, smoothedPHigh, currentHR, currentRMSSD, sessionMinutes)
        _activeRecoveryHistory.add(recovery)
        Log.d(TAG, "Active recovery triggered: level=$currentLevel, watchText='${recovery.watchText}'")
        return recovery
    }

    fun generatePassiveRecovery(): PassiveRecovery {
        val stats = getSessionStats()
        val load = stats.trainingLoad

        val (watchText, phoneTitle, phoneBody, restHours) = when {
            load < 0.25 -> PassiveContent(
                Strings.passiveWatchLight(),
                Strings.passivePhoneTitleLight(),
                Strings.passivePhoneBodyLight(stats),
                5 // midpoint of 4-6
            )
            load < 0.50 -> PassiveContent(
                Strings.passiveWatchModerate(),
                Strings.passivePhoneTitleModerate(),
                Strings.passivePhoneBodyModerate(stats),
                7 // midpoint of 6-8
            )
            load < 0.75 -> PassiveContent(
                Strings.passiveWatchHard(),
                Strings.passivePhoneTitleHard(),
                Strings.passivePhoneBodyHard(stats),
                15 // midpoint of 12-18
            )
            else -> PassiveContent(
                Strings.passiveWatchExtreme(),
                Strings.passivePhoneTitleExtreme(),
                Strings.passivePhoneBodyExtreme(stats),
                24
            )
        }

        Log.d(TAG, "Passive recovery: load=${String.format("%.2f", load)}, restHours=$restHours")
        return PassiveRecovery(watchText, phoneTitle, phoneBody, restHours, load, stats)
    }

    private fun getSessionStats(): SessionStats {
        val durationMinutes = ((System.currentTimeMillis() - sessionStartTime) / 60_000).toInt()
        val avgHR = if (allHRValues.isNotEmpty()) allHRValues.average() else 0.0
        val peakHR = if (allHRValues.isNotEmpty()) allHRValues.max() else 0.0
        val load = calculateTrainingLoad()
        // Each prediction is ~45 seconds apart
        val minutesInHigh = (predictionsInHigh * 0.75).toInt()
        val minutesInCritical = (predictionsInCritical * 0.75).toInt()

        return SessionStats(
            durationMinutes = durationMinutes,
            avgHR = avgHR,
            peakHR = peakHR,
            peakFatiguePercent = peakSmoothedPHigh * 100.0,
            minutesInHighFatigue = minutesInHigh,
            minutesInCriticalFatigue = minutesInCritical,
            trainingLoad = load
        )
    }

    private fun calculateTrainingLoad(): Double {
        if (totalPredictions == 0) return 0.0
        return predictionsInHigh.toDouble() / totalPredictions.toDouble()
    }

    private fun generateActiveRecovery(
        level: Int,
        smoothedPHigh: Double,
        currentHR: Double,
        currentRMSSD: Double,
        sessionMinutes: Int
    ): ActiveRecovery {
        val hrInt = currentHR.toInt()
        val startHRInt = sessionStartHR.toInt()

        val (watchText, phoneTitle, phoneBody) = when (level) {
            1 -> Triple(
                Strings.activeWatchModerate(),
                Strings.activePhoneTitleModerate(),
                Strings.activePhoneBodyModerate(sessionMinutes, hrInt)
            )
            2 -> Triple(
                Strings.activeWatchHigh(),
                Strings.activePhoneTitleHigh(),
                if (hasSessionStart)
                    Strings.activePhoneBodyHighWithRef(startHRInt, hrInt, sessionMinutes)
                else
                    Strings.activePhoneBodyHighNoRef(hrInt, sessionMinutes)
            )
            3 -> Triple(
                Strings.activeWatchCritical(),
                Strings.activePhoneTitleCritical(),
                if (hasSessionStart)
                    Strings.activePhoneBodyCriticalWithRef(startHRInt, hrInt, sessionMinutes)
                else
                    Strings.activePhoneBodyCriticalNoRef(hrInt)
            )
            else -> Triple("", "", "")
        }

        return ActiveRecovery(
            timestamp = System.currentTimeMillis(),
            fatigueLevel = level,
            smoothedPHigh = smoothedPHigh,
            watchText = watchText,
            phoneTitle = phoneTitle,
            phoneBody = phoneBody
        )
    }

    // --- Data classes ---

    data class ActiveRecovery(
        val timestamp: Long,
        val fatigueLevel: Int,
        val smoothedPHigh: Double,
        val watchText: String,
        val phoneTitle: String,
        val phoneBody: String
    )

    data class PassiveRecovery(
        val watchText: String,
        val phoneTitle: String,
        val phoneBody: String,
        val estimatedRestHours: Int,
        val trainingLoad: Double,
        val sessionStats: SessionStats
    )

    data class SessionStats(
        val durationMinutes: Int,
        val avgHR: Double,
        val peakHR: Double,
        val peakFatiguePercent: Double,
        val minutesInHighFatigue: Int,
        val minutesInCriticalFatigue: Int,
        val trainingLoad: Double
    )

    private data class PassiveContent(
        val watchText: String,
        val phoneTitle: String,
        val phoneBody: String,
        val restHours: Int
    )

    // --- Recommendation strings (for future localization) ---

    object Strings {
        // Active recovery - Watch text (5-8 words max)
        fun activeWatchModerate() = "Ease up slightly"
        fun activeWatchHigh() = "Slow down \u00B7 5 min"
        fun activeWatchCritical() = "STOP \u00B7 Rest now"

        // Active recovery - Phone title
        fun activePhoneTitleModerate() = "Fatigue Rising"
        fun activePhoneTitleHigh() = "High Fatigue \u2014 Slow Down"
        fun activePhoneTitleCritical() = "Critical Fatigue \u2014 Stop Now"

        // Active recovery - Phone body
        fun activePhoneBodyModerate(sessionMinutes: Int, hrInt: Int) =
            "You've been exercising for $sessionMinutes minutes and your body is starting " +
                    "to work harder. Your heart rate is $hrInt bpm. Consider easing your pace " +
                    "slightly for the next 2-3 minutes."

        fun activePhoneBodyHighWithRef(startHR: Int, currentHR: Int, sessionMinutes: Int) =
            "Your heart rate has climbed from $startHR to $currentHR bpm over $sessionMinutes " +
                    "minutes. Reduce to an easy pace for 5 minutes to let your body recover " +
                    "before continuing."

        fun activePhoneBodyHighNoRef(hrInt: Int, sessionMinutes: Int) =
            "Your heart rate is $hrInt bpm and fatigue is building quickly after $sessionMinutes " +
                    "minutes. Slow down to an easy pace for 5 minutes."

        fun activePhoneBodyCriticalWithRef(startHR: Int, currentHR: Int, sessionMinutes: Int) =
            "Your heart rate is $currentHR bpm (started the session at $startHR) after " +
                    "$sessionMinutes minutes of exercise. Your body needs rest. Stop exercising, " +
                    "hydrate, and rest for at least 10 minutes before considering resuming."

        fun activePhoneBodyCriticalNoRef(hrInt: Int) =
            "You've reached high fatigue very quickly. Heart rate is $hrInt bpm. Stop and " +
                    "rest for at least 10 minutes. Hydrate immediately."

        // Passive recovery - Watch text
        fun passiveWatchLight() = "Good session"
        fun passiveWatchModerate() = "Rest 6-8 hours"
        fun passiveWatchHard() = "Rest 12-18 hours"
        fun passiveWatchExtreme() = "Full rest day"

        // Passive recovery - Phone title
        fun passivePhoneTitleLight() = "Light Session Complete"
        fun passivePhoneTitleModerate() = "Moderate Session \u2014 Allow Recovery"
        fun passivePhoneTitleHard() = "Hard Session \u2014 Extended Recovery Needed"
        fun passivePhoneTitleExtreme() = "Extreme Session \u2014 Full Rest Day Required"

        // Passive recovery - Phone body
        fun passivePhoneBodyLight(stats: SessionStats) =
            "You exercised for ${stats.durationMinutes} minutes with an average heart rate of " +
                    "${stats.avgHR.toInt()} bpm. This was a light session \u2014 you can train again in " +
                    "4-6 hours. Stay hydrated and have a balanced meal within the next hour."

        fun passivePhoneBodyModerate(stats: SessionStats) =
            "Session: ${stats.durationMinutes} minutes, Avg HR: ${stats.avgHR.toInt()} bpm, " +
                    "Peak HR: ${stats.peakHR.toInt()} bpm. You spent about ${stats.minutesInHighFatigue} " +
                    "minutes in elevated fatigue. Allow 6-8 hours before your next intense session. " +
                    "Prioritize protein intake within 30 minutes. Light walking or stretching is fine."

        fun passivePhoneBodyHard(stats: SessionStats) =
            "Session: ${stats.durationMinutes} minutes, Avg HR: ${stats.avgHR.toInt()} bpm, " +
                    "Peak HR: ${stats.peakHR.toInt()} bpm. You spent approximately " +
                    "${stats.minutesInHighFatigue} minutes in high fatigue, with " +
                    "${stats.minutesInCriticalFatigue} minutes in the critical zone. Your body needs " +
                    "12-18 hours of recovery. Have a meal with carbohydrates and protein within " +
                    "30 minutes. Get at least 7-8 hours of sleep tonight and limit yourself to " +
                    "light activity tomorrow."

        fun passivePhoneBodyExtreme(stats: SessionStats) =
            "Session: ${stats.durationMinutes} minutes, Avg HR: ${stats.avgHR.toInt()} bpm, " +
                    "Peak HR: ${stats.peakHR.toInt()} bpm. You spent approximately " +
                    "${stats.minutesInHighFatigue} minutes under high fatigue. This was a very " +
                    "demanding session. Take a complete rest day \u2014 no intense exercise tomorrow. " +
                    "Focus on hydration, high-protein meals, and at least 8 hours of sleep. " +
                    "Light stretching or a short walk is fine. Listen to your body before your " +
                    "next session."
    }
}
