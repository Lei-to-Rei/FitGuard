package com.example.fitguard.features.sleep

/**
 * Computes a composite stress score (0-100) from HRV metrics.
 *
 * Weights: RMSSD 50%, SDNN 25%, Mean HR 25%.
 * Lower HRV -> higher stress; higher resting HR -> higher stress.
 */
object StressScorer {

    // RMSSD normalization: <20ms = max stress, >60ms = min stress
    private const val RMSSD_LOW = 20.0
    private const val RMSSD_HIGH = 60.0

    // SDNN normalization: <30ms = max stress, >80ms = min stress
    private const val SDNN_LOW = 30.0
    private const val SDNN_HIGH = 80.0

    // HR normalization: <60bpm = min stress, >100bpm = max stress
    private const val HR_LOW = 60.0
    private const val HR_HIGH = 100.0

    fun compute(rmssd: Double, sdnn: Double, meanHr: Double): StressResult {
        // Invert RMSSD: lower RMSSD = higher stress
        val rmssdScore = (1.0 - normalize(rmssd, RMSSD_LOW, RMSSD_HIGH)) * 100.0
        // Invert SDNN: lower SDNN = higher stress
        val sdnnScore = (1.0 - normalize(sdnn, SDNN_LOW, SDNN_HIGH)) * 100.0
        // HR: higher HR = higher stress
        val hrScore = normalize(meanHr, HR_LOW, HR_HIGH) * 100.0

        val composite = (rmssdScore * 0.50 + sdnnScore * 0.25 + hrScore * 0.25)
            .toInt().coerceIn(0, 100)

        val level = when {
            composite <= 25 -> StressLevel.LOW
            composite <= 50 -> StressLevel.MODERATE
            composite <= 75 -> StressLevel.HIGH
            else -> StressLevel.VERY_HIGH
        }

        return StressResult(
            score = composite,
            level = level,
            rmssd = rmssd,
            sdnn = sdnn,
            hr = meanHr,
            timestamp = System.currentTimeMillis()
        )
    }

    /** Normalize value to 0.0–1.0 range, clamped. */
    private fun normalize(value: Double, low: Double, high: Double): Double {
        return ((value - low) / (high - low)).coerceIn(0.0, 1.0)
    }
}
