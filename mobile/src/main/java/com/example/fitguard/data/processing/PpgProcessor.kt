package com.example.fitguard.data.processing

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.tan

data class HrvResult(
    val sequenceId: String,
    val durationSeconds: Double,
    val totalSamples: Int,
    val peaksDetected: Int,
    val nnIntervalsUsed: Int,
    val meanHrBpm: Double,
    val sdnnMs: Double,
    val rmssdMs: Double,
    val pnn20Pct: Double,
    val pnn50Pct: Double,
    val sdsdMs: Double
)

data class PpgProcessedSample(
    val timestamp: Long,
    val sequenceId: String,
    val rawGreen: Int,
    val filteredGreen: Double
)

object PpgProcessor {

    // Filter parameters
    private const val LOW_CUTOFF_HZ = 0.5
    private const val HIGH_CUTOFF_HZ = 5.0

    // Peak detection
    private const val ROLLING_MEAN_WINDOW_SECONDS = 0.75

    // NN interval validation
    private const val NN_MIN_MS = 333.0   // ~180 BPM
    private const val NN_MAX_MS = 1500.0  // ~40 BPM
    private const val NN_DEVIATION_FACTOR = 0.30

    fun process(
        greenValues: IntArray,
        timestamps: LongArray,
        sequenceId: String,
        sampleRateHz: Double = 25.0
    ): Pair<HrvResult, List<PpgProcessedSample>> {
        require(greenValues.size == timestamps.size) { "Mismatched array sizes" }
        require(greenValues.size >= 10) { "Too few samples" }

        // Step 1: Bandpass filter (forward-backward for zero phase distortion)
        val signal = greenValues.map { it.toDouble() }.toDoubleArray()
        val filtered = filtFilt(signal, sampleRateHz)

        // Step 2: HeartPy-style peak detection
        val rollingWindowSamples = (ROLLING_MEAN_WINDOW_SECONDS * sampleRateHz).toInt().coerceAtLeast(3)
        val rollingMeanArr = rollingMean(filtered, rollingWindowSamples)
        val peakIndices = detectPeaks(filtered, rollingMeanArr)

        // Step 3: Compute NN intervals from peak timestamps
        val rawNnMs = mutableListOf<Double>()
        for (i in 1 until peakIndices.size) {
            val dtMs = (timestamps[peakIndices[i]] - timestamps[peakIndices[i - 1]]).toDouble()
            rawNnMs.add(dtMs)
        }

        // Step 4: Validate NN intervals
        val validNn = validateNnIntervals(rawNnMs)

        // Step 5: Compute HRV metrics
        val durationSeconds = (timestamps.last() - timestamps.first()) / 1000.0
        val hrvResult = computeHrv(validNn, sequenceId, durationSeconds, greenValues.size, peakIndices.size)

        // Build processed samples list
        val processedSamples = List(greenValues.size) { i ->
            PpgProcessedSample(timestamps[i], sequenceId, greenValues[i], filtered[i])
        }

        return Pair(hrvResult, processedSamples)
    }

    // --- 2nd-order Butterworth bandpass, forward-backward (zero phase) ---

    private fun filtFilt(data: DoubleArray, fs: Double): DoubleArray {
        // Get coefficients for cascaded high-pass + low-pass (bandpass)
        val hpCoeffs = butterworth2ndOrderHighpass(fs, LOW_CUTOFF_HZ)
        val lpCoeffs = butterworth2ndOrderLowpass(fs, HIGH_CUTOFF_HZ)

        // Forward pass: HP then LP
        var result = applyFilter(data, hpCoeffs)
        result = applyFilter(result, lpCoeffs)

        // Reverse
        result.reverse()

        // Backward pass: HP then LP
        result = applyFilter(result, hpCoeffs)
        result = applyFilter(result, lpCoeffs)

        // Reverse to restore order
        result.reverse()
        return result
    }

    private data class BiquadCoeffs(
        val b0: Double, val b1: Double, val b2: Double,
        val a1: Double, val a2: Double // a0 is normalized to 1.0
    )

    private fun butterworth2ndOrderLowpass(fs: Double, fc: Double): BiquadCoeffs {
        val wc = tan(PI * fc / fs)
        val wc2 = wc * wc
        val sqrt2 = sqrt(2.0)
        val norm = 1.0 / (1.0 + sqrt2 * wc + wc2)
        return BiquadCoeffs(
            b0 = wc2 * norm,
            b1 = 2.0 * wc2 * norm,
            b2 = wc2 * norm,
            a1 = 2.0 * (wc2 - 1.0) * norm,
            a2 = (1.0 - sqrt2 * wc + wc2) * norm
        )
    }

    private fun butterworth2ndOrderHighpass(fs: Double, fc: Double): BiquadCoeffs {
        val wc = tan(PI * fc / fs)
        val wc2 = wc * wc
        val sqrt2 = sqrt(2.0)
        val norm = 1.0 / (1.0 + sqrt2 * wc + wc2)
        return BiquadCoeffs(
            b0 = norm,
            b1 = -2.0 * norm,
            b2 = norm,
            a1 = 2.0 * (wc2 - 1.0) * norm,
            a2 = (1.0 - sqrt2 * wc + wc2) * norm
        )
    }

    private fun applyFilter(data: DoubleArray, c: BiquadCoeffs): DoubleArray {
        val output = DoubleArray(data.size)
        var w1 = 0.0
        var w2 = 0.0
        for (i in data.indices) {
            val w0 = data[i] - c.a1 * w1 - c.a2 * w2
            output[i] = c.b0 * w0 + c.b1 * w1 + c.b2 * w2
            w2 = w1
            w1 = w0
        }
        return output
    }

    // --- Peak detection (HeartPy-style) ---

    private fun rollingMean(data: DoubleArray, windowSize: Int): DoubleArray {
        val result = DoubleArray(data.size)
        val halfWin = windowSize / 2
        for (i in data.indices) {
            val start = (i - halfWin).coerceAtLeast(0)
            val end = (i + halfWin).coerceAtMost(data.size - 1)
            var sum = 0.0
            for (j in start..end) sum += data[j]
            result[i] = sum / (end - start + 1)
        }
        return result
    }

    private fun detectPeaks(signal: DoubleArray, rollingMean: DoubleArray): List<Int> {
        val peaks = mutableListOf<Int>()
        var inRoi = false
        var roiStart = 0

        for (i in signal.indices) {
            if (signal[i] > rollingMean[i]) {
                if (!inRoi) {
                    inRoi = true
                    roiStart = i
                }
            } else {
                if (inRoi) {
                    peaks.add(findMaxInRange(signal, roiStart, i))
                    inRoi = false
                }
            }
        }

        if (inRoi) {
            peaks.add(findMaxInRange(signal, roiStart, signal.size))
        }

        return peaks
    }

    private fun findMaxInRange(signal: DoubleArray, from: Int, to: Int): Int {
        var maxIdx = from
        var maxVal = signal[from]
        for (j in from until to) {
            if (signal[j] > maxVal) {
                maxVal = signal[j]
                maxIdx = j
            }
        }
        return maxIdx
    }

    // --- NN interval validation ---

    private fun validateNnIntervals(rawNnMs: List<Double>): List<Double> {
        if (rawNnMs.isEmpty()) return emptyList()

        val rangeFiltered = rawNnMs.filter { it in NN_MIN_MS..NN_MAX_MS }
        if (rangeFiltered.isEmpty()) return emptyList()

        val mean = rangeFiltered.average()
        val lower = mean * (1.0 - NN_DEVIATION_FACTOR)
        val upper = mean * (1.0 + NN_DEVIATION_FACTOR)
        return rangeFiltered.filter { it in lower..upper }
    }

    // --- HRV computation ---

    private fun computeHrv(
        nnIntervals: List<Double>,
        sequenceId: String,
        durationSeconds: Double,
        totalSamples: Int,
        peaksDetected: Int
    ): HrvResult {
        if (nnIntervals.size < 2) {
            return HrvResult(sequenceId, durationSeconds, totalSamples, peaksDetected,
                nnIntervals.size, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }

        val meanNn = nnIntervals.average()
        val meanHr = 60000.0 / meanNn

        // SDNN
        val sdnn = sqrt(nnIntervals.map { (it - meanNn) * (it - meanNn) }.average())

        // Successive differences
        val diffs = (1 until nnIntervals.size).map { nnIntervals[it] - nnIntervals[it - 1] }

        // RMSSD
        val rmssd = sqrt(diffs.map { it * it }.average())

        // pNN20
        val pnn20 = diffs.count { abs(it) > 20.0 } * 100.0 / diffs.size

        // pNN50
        val pnn50 = diffs.count { abs(it) > 50.0 } * 100.0 / diffs.size

        // SDSD
        val meanDiff = diffs.average()
        val sdsd = sqrt(diffs.map { (it - meanDiff) * (it - meanDiff) }.average())

        return HrvResult(sequenceId, durationSeconds, totalSamples, peaksDetected,
            nnIntervals.size, meanHr, sdnn, rmssd, pnn20, pnn50, sdsd)
    }
}
