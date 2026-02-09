package com.example.fitguard.data.processing

import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.math.tan

data class AccelResult(
    val sequenceId: String,
    val totalSteps: Int,
    val cadenceSpm: Double,
    val meanAccelMag: Double,
    val accelVariance: Double,
    val peakAccelMag: Double
)

object AccelProcessor {

    private const val LOW_CUTOFF_HZ = 0.5
    private const val HIGH_CUTOFF_HZ = 4.0
    private const val ROLLING_MEAN_WINDOW_SECONDS = 0.4

    fun process(
        samples: List<AccelSample>,
        sequenceId: String,
        sampleRateHz: Double = 25.0
    ): AccelResult {
        require(samples.size >= 10) { "Too few accel samples" }

        // Step 1: Compute magnitude for each sample
        val magnitudes = DoubleArray(samples.size) { i ->
            val x = samples[i].x.toDouble()
            val y = samples[i].y.toDouble()
            val z = samples[i].z.toDouble()
            sqrt(x * x + y * y + z * z)
        }

        // Step 2: Summary statistics on raw magnitudes
        val meanMag = magnitudes.average()
        val variance = magnitudes.map { (it - meanMag) * (it - meanMag) }.average()
        val peakMag = magnitudes.max()

        // Step 3: Bandpass filter magnitudes for step detection (0.5–4.0 Hz)
        val filtered = filtFilt(magnitudes, sampleRateHz)

        // Step 4: Peak detection on filtered signal
        val rollingWindowSamples = (ROLLING_MEAN_WINDOW_SECONDS * sampleRateHz).toInt().coerceAtLeast(3)
        val rollingMeanArr = rollingMean(filtered, rollingWindowSamples)
        val peakIndices = detectPeaks(filtered, rollingMeanArr)

        // Step 5: Compute step count and cadence
        val totalSteps = peakIndices.size
        val durationSeconds = (samples.last().timestamp - samples.first().timestamp) / 1000.0
        val cadence = if (durationSeconds > 0) (totalSteps / durationSeconds) * 60.0 else 0.0

        return AccelResult(
            sequenceId = sequenceId,
            totalSteps = totalSteps,
            cadenceSpm = cadence,
            meanAccelMag = meanMag,
            accelVariance = variance,
            peakAccelMag = peakMag
        )
    }

    // --- 2nd-order Butterworth bandpass, forward-backward (zero phase) ---

    private fun filtFilt(data: DoubleArray, fs: Double): DoubleArray {
        val hpCoeffs = butterworth2ndOrderHighpass(fs, LOW_CUTOFF_HZ)
        val lpCoeffs = butterworth2ndOrderLowpass(fs, HIGH_CUTOFF_HZ)

        var result = applyFilter(data, hpCoeffs)
        result = applyFilter(result, lpCoeffs)
        result.reverse()
        result = applyFilter(result, hpCoeffs)
        result = applyFilter(result, lpCoeffs)
        result.reverse()
        return result
    }

    private data class BiquadCoeffs(
        val b0: Double, val b1: Double, val b2: Double,
        val a1: Double, val a2: Double
    )

    private fun butterworth2ndOrderLowpass(fs: Double, fc: Double): BiquadCoeffs {
        val wc = tan(PI * fc / fs)
        val wc2 = wc * wc
        val sqrt2 = sqrt(2.0)
        val norm = 1.0 / (1.0 + sqrt2 * wc + wc2)
        return BiquadCoeffs(
            b0 = wc2 * norm, b1 = 2.0 * wc2 * norm, b2 = wc2 * norm,
            a1 = 2.0 * (wc2 - 1.0) * norm, a2 = (1.0 - sqrt2 * wc + wc2) * norm
        )
    }

    private fun butterworth2ndOrderHighpass(fs: Double, fc: Double): BiquadCoeffs {
        val wc = tan(PI * fc / fs)
        val wc2 = wc * wc
        val sqrt2 = sqrt(2.0)
        val norm = 1.0 / (1.0 + sqrt2 * wc + wc2)
        return BiquadCoeffs(
            b0 = norm, b1 = -2.0 * norm, b2 = norm,
            a1 = 2.0 * (wc2 - 1.0) * norm, a2 = (1.0 - sqrt2 * wc + wc2) * norm
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

    // --- Peak detection ---

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
}
