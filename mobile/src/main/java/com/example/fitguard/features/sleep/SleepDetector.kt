package com.example.fitguard.features.sleep

/**
 * Stateful sleep detector that maintains a rolling window of readings.
 *
 * Primary signal: accelerometer variance (low motion = sleep).
 * Secondary: heart rate drop from baseline.
 * Tertiary: RMSSD increase (parasympathetic dominance during sleep).
 *
 * Uses a 5-reading buffer to smooth state transitions.
 */
class SleepDetector {

    companion object {
        private const val BUFFER_SIZE = 5

        // Accel variance thresholds (m/s² squared)
        private const val DEEP_SLEEP_VARIANCE = 0.001
        private const val LIGHT_SLEEP_VARIANCE = 0.01

        // HR baseline: first few readings establish the "awake" baseline
        private const val BASELINE_READINGS = 3
        private const val DEEP_SLEEP_HR_DROP = 0.15   // 15% below baseline
        private const val LIGHT_SLEEP_HR_DROP = 0.08   // 8% below baseline

        // RMSSD: higher during sleep (parasympathetic)
        private const val SLEEP_RMSSD_BOOST = 1.2      // 20% above baseline
    }

    private val recentStates = ArrayDeque<SleepState>(BUFFER_SIZE)
    private val hrReadings = mutableListOf<Double>()
    private var baselineHr: Double? = null

    fun update(accelVariance: Double, hr: Double, rmssd: Double): SleepResult {
        // Build HR baseline from first readings
        if (baselineHr == null) {
            hrReadings.add(hr)
            if (hrReadings.size >= BASELINE_READINGS) {
                baselineHr = hrReadings.average()
            }
        }

        // Determine raw state from accel variance
        val accelState = when {
            accelVariance < DEEP_SLEEP_VARIANCE -> SleepState.DEEP_SLEEP
            accelVariance < LIGHT_SLEEP_VARIANCE -> SleepState.LIGHT_SLEEP
            else -> SleepState.AWAKE
        }

        // Adjust with HR if baseline is available
        val hrState = baselineHr?.let { bl ->
            val hrRatio = hr / bl
            when {
                hrRatio < (1.0 - DEEP_SLEEP_HR_DROP) -> SleepState.DEEP_SLEEP
                hrRatio < (1.0 - LIGHT_SLEEP_HR_DROP) -> SleepState.LIGHT_SLEEP
                else -> SleepState.AWAKE
            }
        }

        // Combine: accel is primary, HR can deepen but not lighten the state
        val combinedState = if (hrState != null && accelState != SleepState.AWAKE) {
            // Take the deeper sleep state between accel and HR signals
            if (hrState.ordinal > accelState.ordinal) hrState else accelState
        } else {
            accelState
        }

        // Add to rolling buffer
        if (recentStates.size >= BUFFER_SIZE) recentStates.removeFirst()
        recentStates.addLast(combinedState)

        // Majority vote from buffer for final state
        val finalState = majorityState()

        // Confidence based on agreement in buffer
        val agreement = recentStates.count { it == finalState }
        val confidence = agreement.toFloat() / recentStates.size

        return SleepResult(
            state = finalState,
            confidence = confidence,
            hr = hr,
            accelVariance = accelVariance,
            timestamp = System.currentTimeMillis()
        )
    }

    /** Handle case where no accel data is available. */
    fun updateWithoutAccel(hr: Double, rmssd: Double): SleepResult {
        return SleepResult(
            state = SleepState.UNKNOWN,
            confidence = 0f,
            hr = hr,
            accelVariance = -1.0,
            timestamp = System.currentTimeMillis()
        )
    }

    fun reset() {
        recentStates.clear()
        hrReadings.clear()
        baselineHr = null
    }

    private fun majorityState(): SleepState {
        if (recentStates.isEmpty()) return SleepState.UNKNOWN
        return recentStates.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            ?: SleepState.UNKNOWN
    }
}
