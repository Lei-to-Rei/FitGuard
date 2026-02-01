package com.example.fitguard.data.analysis

import kotlin.math.*

/**
 * PPG (Photoplethysmography) Signal Analyzer
 *
 * Implements algorithms similar to HeartPy library for extracting:
 * - Heart Rate (BPM)
 * - Heart Rate Variability (HRV) metrics
 * - Signal quality assessment
 * - Respiratory rate estimation
 *
 * References:
 * - HeartPy: https://github.com/paulvangentcom/heartrate_analysis_python
 * - Pan-Tompkins algorithm for peak detection
 * - Time-domain HRV analysis (SDNN, RMSSD, pNN50)
 * - Frequency-domain HRV analysis (LF, HF, LF/HF ratio)
 */
class PPGAnalyzer {

    companion object {
        private const val TAG = "PPGAnalyzer"

        // Default sampling rate for Samsung watches (typically 25-100 Hz)
        const val DEFAULT_SAMPLE_RATE = 25.0 // Hz

        // Heart rate physiological limits
        const val MIN_HR = 30.0 // BPM
        const val MAX_HR = 220.0 // BPM

        // Corresponding RR interval limits (in ms)
        val MIN_RR_MS = (60000.0 / MAX_HR) // ~273 ms
        val MAX_RR_MS = (60000.0 / MIN_HR) // 2000 ms

        // HRV frequency bands (Hz)
        const val VLF_LOW = 0.003
        const val VLF_HIGH = 0.04
        const val LF_LOW = 0.04
        const val LF_HIGH = 0.15
        const val HF_LOW = 0.15
        const val HF_HIGH = 0.4
    }

    /**
     * Complete analysis result from PPG signal
     */
    data class AnalysisResult(
        // Basic metrics
        val heartRate: Double,              // BPM
        val heartRateVariability: Double,   // SDNN in ms

        // Time-domain HRV metrics
        val sdnn: Double,                   // Standard deviation of NN intervals
        val rmssd: Double,                  // Root mean square of successive differences
        val pnn50: Double,                  // Percentage of NN50
        val sdsd: Double,                   // Standard deviation of successive differences
        val meanRR: Double,                 // Mean RR interval in ms
        val medianRR: Double,               // Median RR interval in ms

        // Frequency-domain HRV metrics (if available)
        val lfPower: Double?,               // Low frequency power (ms²)
        val hfPower: Double?,               // High frequency power (ms²)
        val lfHfRatio: Double?,             // LF/HF ratio
        val totalPower: Double?,            // Total spectral power

        // Additional metrics
        val respiratoryRate: Double?,       // Estimated respiratory rate (breaths/min)
        val signalQuality: Double,          // 0-100 quality score
        val peakCount: Int,                 // Number of detected peaks
        val rrIntervals: List<Double>,      // All RR intervals in ms

        // Timestamps
        val analysisTimestamp: Long,
        val dataStartTime: Long,
        val dataEndTime: Long
    )

    /**
     * Detected peak information
     */
    data class Peak(
        val index: Int,
        val timestamp: Long,
        val value: Double,
        val isValid: Boolean = true
    )

    /**
     * Analyze raw PPG signal and extract all metrics
     *
     * @param greenSignal Array of green LED PPG values
     * @param timestamps Array of corresponding timestamps (ms)
     * @param sampleRate Sampling rate in Hz (if known)
     * @return AnalysisResult with all computed metrics
     */
    fun analyze(
        greenSignal: DoubleArray,
        timestamps: LongArray,
        sampleRate: Double = DEFAULT_SAMPLE_RATE
    ): AnalysisResult {
        require(greenSignal.size >= 10) { "Need at least 10 samples for analysis" }
        require(greenSignal.size == timestamps.size) { "Signal and timestamp arrays must have same size" }

        // 1. Calculate actual sample rate from timestamps
        val actualSampleRate = calculateSampleRate(timestamps)
        val effectiveSampleRate = if (actualSampleRate > 0) actualSampleRate else sampleRate

        // 2. Preprocess signal
        val filtered = preprocessSignal(greenSignal, effectiveSampleRate)

        // 3. Detect peaks
        val peaks = detectPeaks(filtered, timestamps, effectiveSampleRate)

        // 4. Calculate RR intervals
        val rrIntervals = calculateRRIntervals(peaks)

        // 5. Clean RR intervals (remove artifacts)
        val cleanedRR = cleanRRIntervals(rrIntervals)

        // 6. Calculate time-domain HRV metrics
        val timeDomainMetrics = calculateTimeDomainHRV(cleanedRR)

        // 7. Calculate frequency-domain HRV metrics (if enough data)
        val frequencyDomainMetrics = if (cleanedRR.size >= 256) {
            calculateFrequencyDomainHRV(cleanedRR, effectiveSampleRate)
        } else null

        // 8. Estimate respiratory rate from HF peak
        val respiratoryRate = estimateRespiratoryRate(cleanedRR, effectiveSampleRate)

        // 9. Calculate signal quality
        val signalQuality = assessSignalQuality(greenSignal, peaks, rrIntervals)

        return AnalysisResult(
            heartRate = timeDomainMetrics.heartRate,
            heartRateVariability = timeDomainMetrics.sdnn,
            sdnn = timeDomainMetrics.sdnn,
            rmssd = timeDomainMetrics.rmssd,
            pnn50 = timeDomainMetrics.pnn50,
            sdsd = timeDomainMetrics.sdsd,
            meanRR = timeDomainMetrics.meanRR,
            medianRR = timeDomainMetrics.medianRR,
            lfPower = frequencyDomainMetrics?.lfPower,
            hfPower = frequencyDomainMetrics?.hfPower,
            lfHfRatio = frequencyDomainMetrics?.lfHfRatio,
            totalPower = frequencyDomainMetrics?.totalPower,
            respiratoryRate = respiratoryRate,
            signalQuality = signalQuality,
            peakCount = peaks.count { it.isValid },
            rrIntervals = cleanedRR,
            analysisTimestamp = System.currentTimeMillis(),
            dataStartTime = timestamps.first(),
            dataEndTime = timestamps.last()
        )
    }

    /**
     * Calculate actual sample rate from timestamps
     */
    private fun calculateSampleRate(timestamps: LongArray): Double {
        if (timestamps.size < 2) return -1.0

        val intervals = mutableListOf<Long>()
        for (i in 1 until timestamps.size) {
            intervals.add(timestamps[i] - timestamps[i - 1])
        }

        val avgIntervalMs = intervals.average()
        return if (avgIntervalMs > 0) 1000.0 / avgIntervalMs else -1.0
    }

    /**
     * Preprocess PPG signal:
     * 1. Remove DC offset
     * 2. Apply bandpass filter (0.5-4 Hz for heart rate)
     * 3. Normalize signal
     */
    private fun preprocessSignal(signal: DoubleArray, sampleRate: Double): DoubleArray {
        // Remove DC offset (mean)
        val mean = signal.average()
        val centered = signal.map { it - mean }.toDoubleArray()

        // Apply moving average filter for smoothing
        val smoothed = movingAverageFilter(centered, windowSize = 5)

        // Apply bandpass filter using cascaded low-pass and high-pass
        val lowPassCutoff = 4.0 // Hz (remove high frequency noise)
        val highPassCutoff = 0.5 // Hz (remove baseline drift)

        val lowPassed = butterworthLowPass(smoothed, sampleRate, lowPassCutoff)
        val bandPassed = butterworthHighPass(lowPassed, sampleRate, highPassCutoff)

        // Normalize to 0-1 range
        val min = bandPassed.minOrNull() ?: 0.0
        val max = bandPassed.maxOrNull() ?: 1.0
        val range = max - min

        return if (range > 0) {
            bandPassed.map { (it - min) / range }.toDoubleArray()
        } else {
            bandPassed
        }
    }

    /**
     * Simple moving average filter
     */
    private fun movingAverageFilter(signal: DoubleArray, windowSize: Int): DoubleArray {
        val result = DoubleArray(signal.size)
        val halfWindow = windowSize / 2

        for (i in signal.indices) {
            var sum = 0.0
            var count = 0
            for (j in (i - halfWindow)..(i + halfWindow)) {
                if (j >= 0 && j < signal.size) {
                    sum += signal[j]
                    count++
                }
            }
            result[i] = sum / count
        }

        return result
    }

    /**
     * Simple first-order Butterworth low-pass filter
     */
    private fun butterworthLowPass(signal: DoubleArray, sampleRate: Double, cutoff: Double): DoubleArray {
        val rc = 1.0 / (2.0 * PI * cutoff)
        val dt = 1.0 / sampleRate
        val alpha = dt / (rc + dt)

        val result = DoubleArray(signal.size)
        result[0] = signal[0]

        for (i in 1 until signal.size) {
            result[i] = result[i - 1] + alpha * (signal[i] - result[i - 1])
        }

        return result
    }

    /**
     * Simple first-order Butterworth high-pass filter
     */
    private fun butterworthHighPass(signal: DoubleArray, sampleRate: Double, cutoff: Double): DoubleArray {
        val rc = 1.0 / (2.0 * PI * cutoff)
        val dt = 1.0 / sampleRate
        val alpha = rc / (rc + dt)

        val result = DoubleArray(signal.size)
        result[0] = signal[0]

        for (i in 1 until signal.size) {
            result[i] = alpha * (result[i - 1] + signal[i] - signal[i - 1])
        }

        return result
    }

    /**
     * Detect peaks using adaptive threshold method
     * Similar to Pan-Tompkins algorithm
     */
    private fun detectPeaks(
        signal: DoubleArray,
        timestamps: LongArray,
        sampleRate: Double
    ): List<Peak> {
        val peaks = mutableListOf<Peak>()

        // Minimum samples between peaks (based on max HR of 220 BPM)
        val minPeakDistance = (sampleRate * 60.0 / MAX_HR).toInt().coerceAtLeast(1)

        // Calculate adaptive threshold using moving window
        val windowSize = (sampleRate * 2).toInt() // 2 second window
        val thresholds = calculateAdaptiveThreshold(signal, windowSize)

        var lastPeakIdx = -minPeakDistance

        for (i in 1 until signal.size - 1) {
            // Check if it's a local maximum
            if (signal[i] > signal[i - 1] && signal[i] > signal[i + 1]) {
                // Check if above threshold
                if (signal[i] > thresholds[i]) {
                    // Check minimum distance from last peak
                    if (i - lastPeakIdx >= minPeakDistance) {
                        peaks.add(Peak(
                            index = i,
                            timestamp = timestamps[i],
                            value = signal[i],
                            isValid = true
                        ))
                        lastPeakIdx = i
                    }
                }
            }
        }

        return peaks
    }

    /**
     * Calculate adaptive threshold for peak detection
     */
    private fun calculateAdaptiveThreshold(signal: DoubleArray, windowSize: Int): DoubleArray {
        val thresholds = DoubleArray(signal.size)
        val halfWindow = windowSize / 2

        for (i in signal.indices) {
            val windowStart = (i - halfWindow).coerceAtLeast(0)
            val windowEnd = (i + halfWindow).coerceAtMost(signal.size - 1)

            val windowValues = signal.slice(windowStart..windowEnd)
            val windowMean = windowValues.average()
            val windowStd = sqrt(windowValues.map { (it - windowMean).pow(2) }.average())

            // Threshold = mean + 0.5 * std (adjustable parameter)
            thresholds[i] = windowMean + 0.5 * windowStd
        }

        return thresholds
    }

    /**
     * Calculate RR intervals from detected peaks
     */
    private fun calculateRRIntervals(peaks: List<Peak>): List<Double> {
        if (peaks.size < 2) return emptyList()

        val rrIntervals = mutableListOf<Double>()

        for (i in 1 until peaks.size) {
            val rr = (peaks[i].timestamp - peaks[i - 1].timestamp).toDouble()
            rrIntervals.add(rr)
        }

        return rrIntervals
    }

    /**
     * Clean RR intervals by removing physiologically impossible values
     * and outliers (ectopic beats, artifacts)
     */
    private fun cleanRRIntervals(rrIntervals: List<Double>): List<Double> {
        if (rrIntervals.isEmpty()) return emptyList()

        // First pass: remove physiologically impossible values
        val physiologicallyValid = rrIntervals.filter { rr ->
            rr >= MIN_RR_MS && rr <= MAX_RR_MS
        }

        if (physiologicallyValid.size < 3) return physiologicallyValid

        // Second pass: remove statistical outliers using IQR method
        val sorted = physiologicallyValid.sorted()
        val q1 = sorted[(sorted.size * 0.25).toInt()]
        val q3 = sorted[(sorted.size * 0.75).toInt()]
        val iqr = q3 - q1
        val lowerBound = q1 - 1.5 * iqr
        val upperBound = q3 + 1.5 * iqr

        val cleaned = physiologicallyValid.filter { rr ->
            rr >= lowerBound && rr <= upperBound
        }

        // Third pass: remove intervals that differ too much from neighbors
        // (ectopic beat detection)
        return removeEctopicBeats(cleaned)
    }

    /**
     * Remove ectopic beats (premature beats) using successive difference method
     */
    private fun removeEctopicBeats(rrIntervals: List<Double>, threshold: Double = 0.2): List<Double> {
        if (rrIntervals.size < 3) return rrIntervals

        val result = mutableListOf<Double>()
        result.add(rrIntervals[0])

        for (i in 1 until rrIntervals.size) {
            val diff = abs(rrIntervals[i] - rrIntervals[i - 1])
            val relativeDiff = diff / rrIntervals[i - 1]

            // Only include if change is less than threshold (20%)
            if (relativeDiff < threshold) {
                result.add(rrIntervals[i])
            }
        }

        return result
    }

    /**
     * Time-domain HRV metrics
     */
    data class TimeDomainMetrics(
        val heartRate: Double,
        val sdnn: Double,
        val rmssd: Double,
        val pnn50: Double,
        val sdsd: Double,
        val meanRR: Double,
        val medianRR: Double
    )

    /**
     * Calculate time-domain HRV metrics
     */
    private fun calculateTimeDomainHRV(rrIntervals: List<Double>): TimeDomainMetrics {
        if (rrIntervals.isEmpty()) {
            return TimeDomainMetrics(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }

        // Mean RR
        val meanRR = rrIntervals.average()

        // Median RR
        val sorted = rrIntervals.sorted()
        val medianRR = if (sorted.size % 2 == 0) {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        } else {
            sorted[sorted.size / 2]
        }

        // Heart Rate from mean RR
        val heartRate = if (meanRR > 0) 60000.0 / meanRR else 0.0

        // SDNN - Standard Deviation of NN intervals
        val sdnn = sqrt(rrIntervals.map { (it - meanRR).pow(2) }.average())

        // Successive differences
        val successiveDiffs = mutableListOf<Double>()
        for (i in 1 until rrIntervals.size) {
            successiveDiffs.add(rrIntervals[i] - rrIntervals[i - 1])
        }

        // RMSSD - Root Mean Square of Successive Differences
        val rmssd = if (successiveDiffs.isNotEmpty()) {
            sqrt(successiveDiffs.map { it.pow(2) }.average())
        } else 0.0

        // SDSD - Standard Deviation of Successive Differences
        val meanDiff = successiveDiffs.average()
        val sdsd = if (successiveDiffs.isNotEmpty()) {
            sqrt(successiveDiffs.map { (it - meanDiff).pow(2) }.average())
        } else 0.0

        // pNN50 - Percentage of successive differences > 50ms
        val nn50Count = successiveDiffs.count { abs(it) > 50 }
        val pnn50 = if (successiveDiffs.isNotEmpty()) {
            (nn50Count.toDouble() / successiveDiffs.size) * 100
        } else 0.0

        return TimeDomainMetrics(
            heartRate = heartRate,
            sdnn = sdnn,
            rmssd = rmssd,
            pnn50 = pnn50,
            sdsd = sdsd,
            meanRR = meanRR,
            medianRR = medianRR
        )
    }

    /**
     * Frequency-domain HRV metrics
     */
    data class FrequencyDomainMetrics(
        val lfPower: Double,
        val hfPower: Double,
        val lfHfRatio: Double,
        val totalPower: Double,
        val vlfPower: Double
    )

    /**
     * Calculate frequency-domain HRV metrics using Lomb-Scargle periodogram
     * (better for unevenly sampled data like RR intervals)
     */
    private fun calculateFrequencyDomainHRV(
        rrIntervals: List<Double>,
        sampleRate: Double
    ): FrequencyDomainMetrics? {
        if (rrIntervals.size < 64) return null

        // Create time series from RR intervals
        var cumTime = 0.0
        val times = mutableListOf<Double>()
        val values = mutableListOf<Double>()

        for (rr in rrIntervals) {
            times.add(cumTime)
            values.add(rr)
            cumTime += rr / 1000.0 // Convert to seconds
        }

        // Interpolate to even sampling for FFT (4 Hz)
        val interpolatedFs = 4.0
        val duration = times.last()
        val nSamples = (duration * interpolatedFs).toInt()

        if (nSamples < 64) return null

        val interpolated = linearInterpolate(
            times.toDoubleArray(),
            values.toDoubleArray(),
            nSamples,
            interpolatedFs
        )

        // Remove mean and apply Hanning window
        val mean = interpolated.average()
        val windowed = interpolated.mapIndexed { i, v ->
            val window = 0.5 * (1 - cos(2 * PI * i / (interpolated.size - 1)))
            (v - mean) * window
        }.toDoubleArray()

        // Simple DFT for power spectrum (for small arrays)
        val powerSpectrum = calculatePowerSpectrum(windowed, interpolatedFs)

        // Integrate power in frequency bands
        val vlfPower = integratePower(powerSpectrum, VLF_LOW, VLF_HIGH, interpolatedFs)
        val lfPower = integratePower(powerSpectrum, LF_LOW, LF_HIGH, interpolatedFs)
        val hfPower = integratePower(powerSpectrum, HF_LOW, HF_HIGH, interpolatedFs)
        val totalPower = vlfPower + lfPower + hfPower

        val lfHfRatio = if (hfPower > 0) lfPower / hfPower else 0.0

        return FrequencyDomainMetrics(
            lfPower = lfPower,
            hfPower = hfPower,
            lfHfRatio = lfHfRatio,
            totalPower = totalPower,
            vlfPower = vlfPower
        )
    }

    /**
     * Linear interpolation for resampling
     */
    private fun linearInterpolate(
        times: DoubleArray,
        values: DoubleArray,
        nSamples: Int,
        fs: Double
    ): DoubleArray {
        val result = DoubleArray(nSamples)
        val duration = times.last()

        for (i in 0 until nSamples) {
            val t = i / fs

            // Find surrounding points
            var j = 0
            while (j < times.size - 1 && times[j + 1] < t) j++

            if (j >= times.size - 1) {
                result[i] = values.last()
            } else {
                // Linear interpolation
                val t0 = times[j]
                val t1 = times[j + 1]
                val v0 = values[j]
                val v1 = values[j + 1]

                val alpha = if (t1 > t0) (t - t0) / (t1 - t0) else 0.0
                result[i] = v0 + alpha * (v1 - v0)
            }
        }

        return result
    }

    /**
     * Calculate power spectrum using DFT
     */
    private fun calculatePowerSpectrum(signal: DoubleArray, fs: Double): DoubleArray {
        val n = signal.size
        val spectrum = DoubleArray(n / 2)

        for (k in 0 until n / 2) {
            var realSum = 0.0
            var imagSum = 0.0

            for (t in 0 until n) {
                val angle = 2 * PI * k * t / n
                realSum += signal[t] * cos(angle)
                imagSum -= signal[t] * sin(angle)
            }

            // Power = magnitude squared
            spectrum[k] = (realSum * realSum + imagSum * imagSum) / n
        }

        return spectrum
    }

    /**
     * Integrate power in frequency band
     */
    private fun integratePower(
        spectrum: DoubleArray,
        freqLow: Double,
        freqHigh: Double,
        fs: Double
    ): Double {
        val freqResolution = fs / (spectrum.size * 2)

        val binLow = (freqLow / freqResolution).toInt().coerceIn(0, spectrum.size - 1)
        val binHigh = (freqHigh / freqResolution).toInt().coerceIn(0, spectrum.size - 1)

        var power = 0.0
        for (i in binLow..binHigh) {
            power += spectrum[i]
        }

        return power * freqResolution
    }

    /**
     * Estimate respiratory rate from HRV
     * Respiratory sinus arrhythmia (RSA) causes HRV in HF band
     */
    private fun estimateRespiratoryRate(rrIntervals: List<Double>, sampleRate: Double): Double? {
        if (rrIntervals.size < 64) return null

        // Similar to frequency analysis but find peak in respiratory range (0.15-0.4 Hz)
        // This corresponds to 9-24 breaths per minute

        val freqDomain = calculateFrequencyDomainHRV(rrIntervals, sampleRate) ?: return null

        // If HF power is significant, estimate respiratory rate
        // Typically respiratory rate corresponds to HF peak frequency
        // For simplicity, assume peak is in middle of HF band
        val estimatedFreq = (HF_LOW + HF_HIGH) / 2 // ~0.275 Hz

        return estimatedFreq * 60 // Convert to breaths per minute
    }

    /**
     * Assess signal quality (0-100)
     */
    private fun assessSignalQuality(
        signal: DoubleArray,
        peaks: List<Peak>,
        rrIntervals: List<Double>
    ): Double {
        var score = 100.0

        // Check 1: Signal variance (should have some but not too much)
        val signalStd = sqrt(signal.map { (it - signal.average()).pow(2) }.average())
        if (signalStd < 0.01) score -= 30 // Too flat
        if (signalStd > 0.5) score -= 20 // Too noisy

        // Check 2: Peak count (should have reasonable number)
        val duration = (signal.size / DEFAULT_SAMPLE_RATE) // seconds
        val expectedPeaks = duration * 1.0 // ~60 BPM
        val peakRatio = peaks.size / expectedPeaks
        if (peakRatio < 0.5) score -= 20 // Too few peaks
        if (peakRatio > 3) score -= 20 // Too many peaks

        // Check 3: RR interval consistency
        if (rrIntervals.size >= 2) {
            val rrStd = sqrt(rrIntervals.map { (it - rrIntervals.average()).pow(2) }.average())
            val rrCV = rrStd / rrIntervals.average() // Coefficient of variation
            if (rrCV > 0.3) score -= 20 // Too variable (likely artifacts)
        }

        // Check 4: Percentage of valid peaks
        val validRatio = rrIntervals.count { it in MIN_RR_MS..MAX_RR_MS }.toDouble() /
                rrIntervals.size.coerceAtLeast(1)
        score -= (1 - validRatio) * 20

        return score.coerceIn(0.0, 100.0)
    }

    /**
     * Quick analysis for real-time display (less metrics, faster)
     */
    fun quickAnalyze(
        greenSignal: DoubleArray,
        timestamps: LongArray,
        sampleRate: Double = DEFAULT_SAMPLE_RATE
    ): QuickAnalysisResult {
        val filtered = preprocessSignal(greenSignal, sampleRate)
        val peaks = detectPeaks(filtered, timestamps, sampleRate)
        val rrIntervals = calculateRRIntervals(peaks)
        val cleanedRR = cleanRRIntervals(rrIntervals)

        val meanRR = if (cleanedRR.isNotEmpty()) cleanedRR.average() else 0.0
        val heartRate = if (meanRR > 0) 60000.0 / meanRR else 0.0

        val sdnn = if (cleanedRR.size > 1) {
            sqrt(cleanedRR.map { (it - meanRR).pow(2) }.average())
        } else 0.0

        return QuickAnalysisResult(
            heartRate = heartRate,
            sdnn = sdnn,
            signalQuality = assessSignalQuality(greenSignal, peaks, rrIntervals),
            peakCount = peaks.size
        )
    }

    data class QuickAnalysisResult(
        val heartRate: Double,
        val sdnn: Double,
        val signalQuality: Double,
        val peakCount: Int
    )
}