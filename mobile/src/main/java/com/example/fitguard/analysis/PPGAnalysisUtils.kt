package com.example.fitguard.analysis

import kotlin.math.*

/**
 * Utility class for analyzing PPG (photoplethysmography) signals
 * Includes heart rate detection, SpO2 estimation, and HRV analysis
 */
object PPGAnalysisUtils {

    // ========== 1. HEART RATE CALCULATION ==========

    /**
     * Calculate heart rate from PPG signal using peak detection
     * @param values PPG signal values
     * @param timestamps Timestamps in milliseconds
     * @param samplingRate Sampling rate in Hz (typically ~100 Hz)
     * @return Heart rate in BPM, or null if cannot be calculated
     */
    fun calculateHeartRate(
        values: IntArray,
        timestamps: LongArray,
        samplingRate: Double = 100.0
    ): HeartRateResult? {
        if (values.size < 200) return null // Need at least 2 seconds of data

        // Apply bandpass filter (0.5 - 4 Hz) to isolate heart rate frequencies
        val filtered = bandpassFilter(values, samplingRate, 0.5, 4.0)

        // Detect peaks
        val peaks = detectPeaks(filtered, minDistance = (samplingRate * 0.4).toInt())

        if (peaks.size < 2) return null

        // Calculate inter-beat intervals (IBI) in milliseconds
        val ibis = mutableListOf<Double>()
        for (i in 0 until peaks.size - 1) {
            val ibi = (timestamps[peaks[i + 1]] - timestamps[peaks[i]]).toDouble()
            if (ibi in 300.0..2000.0) { // Valid range: 30-200 BPM
                ibis.add(ibi)
            }
        }

        if (ibis.isEmpty()) return null

        // Calculate average heart rate
        val avgIBI = ibis.average()
        val heartRate = 60000.0 / avgIBI // Convert to BPM

        // Calculate confidence based on IBI consistency
        val ibiStdDev = standardDeviation(ibis)
        val confidence = max(0.0, min(100.0, 100.0 - (ibiStdDev / avgIBI * 100)))

        return HeartRateResult(
            bpm = heartRate,
            confidence = confidence,
            peakCount = peaks.size,
            avgIBI = avgIBI,
            ibiStdDev = ibiStdDev
        )
    }

    // ========== 2. SPO2 ESTIMATION ==========

    /**
     * Estimate blood oxygen saturation (SpO2) using Red and IR PPG signals
     * @param redValues Red LED PPG values
     * @param irValues IR LED PPG values
     * @return SpO2 percentage (0-100), or null if cannot be calculated
     */
    fun estimateSpO2(
        redValues: IntArray,
        irValues: IntArray,
        redTimestamps: LongArray,
        irTimestamps: LongArray
    ): SpO2Result? {
        if (redValues.size < 100 || irValues.size < 100) return null

        // Align signals by timestamp if needed
        val (alignedRed, alignedIR) = alignSignals(redValues, irValues, redTimestamps, irTimestamps)

        // Calculate AC and DC components for both wavelengths
        val redAC = calculateACComponent(alignedRed)
        val redDC = alignedRed.average()
        val irAC = calculateACComponent(alignedIR)
        val irDC = alignedIR.average()

        if (redDC == 0.0 || irDC == 0.0) return null

        // Calculate the ratio of ratios (R)
        val R = (redAC / redDC) / (irAC / irDC)

        // Empirical SpO2 formula (typical calibration curve)
        // SpO2 = 110 - 25 * R
        // Note: This needs device-specific calibration for accuracy
        val spO2 = 110.0 - (25.0 * R)
        val clampedSpO2 = max(70.0, min(100.0, spO2))

        // Calculate perfusion index
        val perfusionIndex = (irAC / irDC) * 100.0

        // Quality indicator
        val quality = when {
            perfusionIndex < 0.3 -> "Poor"
            perfusionIndex < 1.0 -> "Fair"
            perfusionIndex < 5.0 -> "Good"
            else -> "Excellent"
        }

        return SpO2Result(
            spO2 = clampedSpO2,
            perfusionIndex = perfusionIndex,
            ratioOfRatios = R,
            quality = quality,
            needsCalibration = true
        )
    }

    // ========== 3. HEART RATE VARIABILITY (HRV) ==========

    /**
     * Calculate HRV metrics from PPG signal
     * @param values PPG signal values
     * @param timestamps Timestamps in milliseconds
     * @param samplingRate Sampling rate in Hz
     * @return HRV metrics including SDNN, RMSSD, pNN50
     */
    fun calculateHRV(
        values: IntArray,
        timestamps: LongArray,
        samplingRate: Double = 100.0
    ): HRVResult? {
        if (values.size < 300) return null // Need at least 3 seconds

        // Detect peaks
        val filtered = bandpassFilter(values, samplingRate, 0.5, 4.0)
        val peaks = detectPeaks(filtered, minDistance = (samplingRate * 0.4).toInt())

        if (peaks.size < 3) return null

        // Calculate R-R intervals (IBI in ms)
        val rrIntervals = mutableListOf<Double>()
        for (i in 0 until peaks.size - 1) {
            val rr = (timestamps[peaks[i + 1]] - timestamps[peaks[i]]).toDouble()
            if (rr in 300.0..2000.0) {
                rrIntervals.add(rr)
            }
        }

        if (rrIntervals.size < 2) return null

        // Time domain metrics
        val meanRR = rrIntervals.average()
        val sdnn = standardDeviation(rrIntervals) // Standard deviation of NN intervals

        // Calculate successive differences
        val successiveDiffs = mutableListOf<Double>()
        for (i in 0 until rrIntervals.size - 1) {
            successiveDiffs.add(abs(rrIntervals[i + 1] - rrIntervals[i]))
        }

        // RMSSD: Root mean square of successive differences
        val rmssd = if (successiveDiffs.isNotEmpty()) {
            sqrt(successiveDiffs.map { it * it }.average())
        } else 0.0

        // pNN50: Percentage of successive RR intervals that differ by more than 50ms
        val nn50Count = successiveDiffs.count { it > 50.0 }
        val pnn50 = if (successiveDiffs.isNotEmpty()) {
            (nn50Count.toDouble() / successiveDiffs.size) * 100.0
        } else 0.0

        // Stress index (simplified)
        val stressIndex = when {
            sdnn > 50 -> "Low stress / Good recovery"
            sdnn > 30 -> "Moderate stress"
            else -> "High stress / Poor recovery"
        }

        return HRVResult(
            meanRR = meanRR,
            sdnn = sdnn,
            rmssd = rmssd,
            pnn50 = pnn50,
            rrIntervals = rrIntervals,
            stressIndex = stressIndex
        )
    }

    // ========== HELPER FUNCTIONS ==========

    /**
     * Simple bandpass filter using moving average
     */
    private fun bandpassFilter(
        values: IntArray,
        samplingRate: Double,
        lowCut: Double,
        highCut: Double
    ): DoubleArray {
        // High-pass filter (remove DC and low frequency drift)
        val highPassed = highPassFilter(values, samplingRate, lowCut)

        // Low-pass filter (remove high frequency noise)
        return lowPassFilter(highPassed, samplingRate, highCut)
    }

    private fun highPassFilter(values: IntArray, samplingRate: Double, cutoff: Double): DoubleArray {
        val result = DoubleArray(values.size)
        val alpha = 1.0 / (1.0 + (samplingRate / (2 * PI * cutoff)))

        result[0] = values[0].toDouble()
        for (i in 1 until values.size) {
            result[i] = alpha * (result[i - 1] + values[i] - values[i - 1])
        }
        return result
    }

    private fun lowPassFilter(values: DoubleArray, samplingRate: Double, cutoff: Double): DoubleArray {
        val result = DoubleArray(values.size)
        val alpha = (2 * PI * cutoff) / (samplingRate + 2 * PI * cutoff)

        result[0] = values[0]
        for (i in 1 until values.size) {
            result[i] = alpha * values[i] + (1 - alpha) * result[i - 1]
        }
        return result
    }

    /**
     * Detect peaks in signal
     */
    private fun detectPeaks(values: DoubleArray, minDistance: Int): List<Int> {
        val peaks = mutableListOf<Int>()
        val threshold = values.average() + standardDeviation(values.toList()) * 0.5

        var i = 1
        while (i < values.size - 1) {
            if (values[i] > threshold &&
                values[i] > values[i - 1] &&
                values[i] > values[i + 1]) {
                peaks.add(i)
                i += minDistance // Skip ahead to avoid detecting same peak
            } else {
                i++
            }
        }
        return peaks
    }

    /**
     * Calculate AC component (variation around mean)
     */
    private fun calculateACComponent(values: IntArray): Double {
        return standardDeviation(values.map { it.toDouble() })
    }

    /**
     * Align two signals by their timestamps
     */
    private fun alignSignals(
        signal1: IntArray,
        signal2: IntArray,
        timestamps1: LongArray,
        timestamps2: LongArray
    ): Pair<IntArray, IntArray> {
        // For simplicity, just ensure same length
        val minSize = min(signal1.size, signal2.size)
        return Pair(
            signal1.sliceArray(0 until minSize),
            signal2.sliceArray(0 until minSize)
        )
    }

    private fun standardDeviation(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.average()
        val variance = values.map { (it - mean).pow(2) }.average()
        return sqrt(variance)
    }

    // ========== DATA CLASSES ==========

    data class HeartRateResult(
        val bpm: Double,
        val confidence: Double,
        val peakCount: Int,
        val avgIBI: Double,
        val ibiStdDev: Double
    )

    data class SpO2Result(
        val spO2: Double,
        val perfusionIndex: Double,
        val ratioOfRatios: Double,
        val quality: String,
        val needsCalibration: Boolean
    )

    data class HRVResult(
        val meanRR: Double,
        val sdnn: Double,
        val rmssd: Double,
        val pnn50: Double,
        val rrIntervals: List<Double>,
        val stressIndex: String
    )
}