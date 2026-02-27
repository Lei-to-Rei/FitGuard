package com.example.fitguard.data.processing

import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.math.tan

data class AccelFeatures(
    val xMean: Double,
    val yMean: Double,
    val zMean: Double,
    val xVar: Double,
    val yVar: Double,
    val zVar: Double,
    val magMean: Double,
    val magVar: Double,
    val magPeak: Double,
    val totalSteps: Int,
    val cadenceSpm: Double
)

object AccelProcessor {

    private const val STEP_BP_LOW_HZ = 1.0
    private const val STEP_BP_HIGH_HZ = 4.0
    private const val ACCEL_SAMPLE_RATE_HZ = 25.0
    private const val STEP_MIN_AMPLITUDE = 0.15  // m/s² — walking > 0.5, sitting noise < 0.05
    // Samsung Health Sensor SDK ACCELEROMETER_CONTINUOUS raw ADC to m/s²
    private const val RAW_TO_MS2 = 9.80665 / 4096.0

    fun process(samples: List<AccelSample>, durationSeconds: Double): AccelFeatures {
        if (samples.isEmpty()) {
            return AccelFeatures(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0.0)
        }

        val xs = samples.map { it.x * RAW_TO_MS2 }
        val ys = samples.map { it.y * RAW_TO_MS2 }
        val zs = samples.map { it.z * RAW_TO_MS2 }

        val xMean = xs.average()
        val yMean = ys.average()
        val zMean = zs.average()
        val xVar = variance(xs, xMean)
        val yVar = variance(ys, yMean)
        val zVar = variance(zs, zMean)

        // Magnitude
        val mags = xs.indices.map { i -> sqrt(xs[i] * xs[i] + ys[i] * ys[i] + zs[i] * zs[i]) }
        val magMean = mags.average()
        val magVar = variance(mags, magMean)
        val magPeak = mags.max()

        // Step detection via bandpass filter on accel magnitude + peak counting
        val steps = detectSteps(mags.toDoubleArray())
        val dur = if (durationSeconds > 0) durationSeconds else 1.0
        val cadence = steps * 60.0 / dur

        return AccelFeatures(xMean, yMean, zMean, xVar, yVar, zVar, magMean, magVar, magPeak, steps, cadence)
    }

    private fun detectSteps(magnitude: DoubleArray): Int {
        if (magnitude.size < 10) return 0

        val filtered = filtFiltAccel(magnitude, ACCEL_SAMPLE_RATE_HZ, STEP_BP_LOW_HZ, STEP_BP_HIGH_HZ)

        // Count positive half-cycles whose peak exceeds the amplitude threshold
        var steps = 0
        var peakInHalfCycle = 0.0
        for (i in 1 until filtered.size) {
            if (filtered[i] > 0) {
                peakInHalfCycle = maxOf(peakInHalfCycle, filtered[i])
            }
            // Negative-going crossing = end of positive half-cycle
            if (filtered[i - 1] > 0 && filtered[i] <= 0) {
                if (peakInHalfCycle >= STEP_MIN_AMPLITUDE) {
                    steps++
                }
                peakInHalfCycle = 0.0
            }
        }
        return steps
    }

    private fun filtFiltAccel(data: DoubleArray, fs: Double, lowCutoff: Double, highCutoff: Double): DoubleArray {
        val hpCoeffs = butterworth2ndOrderHighpass(fs, lowCutoff)
        val lpCoeffs = butterworth2ndOrderLowpass(fs, highCutoff)

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

    private fun variance(values: List<Double>, mean: Double): Double {
        if (values.size < 2) return 0.0
        return values.sumOf { (it - mean) * (it - mean) } / values.size
    }
}
