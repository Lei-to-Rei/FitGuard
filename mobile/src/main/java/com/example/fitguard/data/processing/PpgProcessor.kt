package com.example.fitguard.data.processing

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

data class PpgFeatures(
    val meanHrBpm: Double,       // 1
    val hrStdBpm: Double,        // 2
    val hrMinBpm: Double,        // 3
    val hrMaxBpm: Double,        // 4
    val hrRangeBpm: Double,      // 5
    val hrSlopeBpmPerS: Double,  // 6
    val nnQualityRatio: Double,  // 7
    val sdnnMs: Double,          // 8
    val rmssdMs: Double,         // 9
    val pnn50Pct: Double,        // 10
    val meanNnMs: Double,        // 11
    val cvNn: Double,            // 12
    val lfPowerMs2: Double,      // 13
    val hfPowerMs2: Double,      // 14
    val lfHfRatio: Double,       // 15
    val totalPowerMs2: Double,   // 16
    val spo2MeanPct: Double,     // 17
    val spo2MinPct: Double,      // 18
    val spo2StdPct: Double       // 19
)

data class FeatureVector(
    val timestamp: Long,
    val sequenceId: String,
    val ppg: PpgFeatures,
    val accelXMean: Double,
    val accelYMean: Double,
    val accelZMean: Double,
    val accelXVar: Double,
    val accelYVar: Double,
    val accelZVar: Double,
    val accelMagMean: Double,
    val accelMagVar: Double,
    val accelPeak: Double,
    val skinTempObj: Double,
    val skinTempDelta: Double,
    val skinTempAmbient: Double,
    val totalSteps: Int,
    val cadenceSpm: Double,
    val activityLabel: String = "",
    val fatigueLevel: String = "",
    val rpeRaw: Int = -1
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

    // Frequency domain
    private const val INTERP_FS = 4.0 // Hz for NN interpolation
    private const val LF_LOW = 0.04
    private const val LF_HIGH = 0.15
    private const val HF_LOW = 0.15
    private const val HF_HIGH = 0.4

    fun process(samples: List<PpgSample>, sampleRateHz: Double = 25.0): PpgFeatures {
        require(samples.size >= 10) { "Too few samples" }

        val greenValues = IntArray(samples.size) { samples[it].green }
        val timestamps = LongArray(samples.size) { samples[it].timestamp }

        // Step 1: Bandpass filter green channel
        val signal = greenValues.map { it.toDouble() }.toDoubleArray()
        val filtered = filtFilt(signal, sampleRateHz, LOW_CUTOFF_HZ, HIGH_CUTOFF_HZ)

        // Step 2: Peak detection
        val rollingWindowSamples = (ROLLING_MEAN_WINDOW_SECONDS * sampleRateHz).toInt().coerceAtLeast(3)
        val rollingMeanArr = rollingMean(filtered, rollingWindowSamples)
        val peakIndices = detectPeaks(filtered, rollingMeanArr)

        // Step 3: NN intervals from peak timestamps
        val rawNnMs = mutableListOf<Double>()
        for (i in 1 until peakIndices.size) {
            val dtMs = (timestamps[peakIndices[i]] - timestamps[peakIndices[i - 1]]).toDouble()
            rawNnMs.add(dtMs)
        }

        // Step 4: Validate NN intervals
        val validNn = validateNnIntervals(rawNnMs)

        // Step 5: Compute time-domain HRV + HR stats (features 1-12)
        val timeDomain = computeTimeDomainFeatures(validNn, peakIndices.size)

        // Step 6: Compute frequency-domain HRV (features 13-16)
        val freqDomain = computeFrequencyDomainFeatures(validNn)

        // Step 7: Compute SpO2 (features 17-19)
        val spo2 = computeSpO2(samples, peakIndices, sampleRateHz)

        return PpgFeatures(
            meanHrBpm = timeDomain.meanHr,
            hrStdBpm = timeDomain.hrStd,
            hrMinBpm = timeDomain.hrMin,
            hrMaxBpm = timeDomain.hrMax,
            hrRangeBpm = timeDomain.hrRange,
            hrSlopeBpmPerS = timeDomain.hrSlope,
            nnQualityRatio = timeDomain.nnQualityRatio,
            sdnnMs = timeDomain.sdnn,
            rmssdMs = timeDomain.rmssd,
            pnn50Pct = timeDomain.pnn50,
            meanNnMs = timeDomain.meanNn,
            cvNn = timeDomain.cvNn,
            lfPowerMs2 = freqDomain.lfPower,
            hfPowerMs2 = freqDomain.hfPower,
            lfHfRatio = freqDomain.lfHfRatio,
            totalPowerMs2 = freqDomain.totalPower,
            spo2MeanPct = spo2.mean,
            spo2MinPct = spo2.min,
            spo2StdPct = spo2.std
        )
    }

    // --- Time-domain features ---

    private data class TimeDomainResult(
        val meanHr: Double, val hrStd: Double, val hrMin: Double, val hrMax: Double,
        val hrRange: Double, val hrSlope: Double, val nnQualityRatio: Double,
        val sdnn: Double, val rmssd: Double, val pnn50: Double,
        val meanNn: Double, val cvNn: Double
    )

    private fun computeTimeDomainFeatures(nnIntervals: List<Double>, peaksDetected: Int): TimeDomainResult {
        if (nnIntervals.size < 2) {
            return TimeDomainResult(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }

        val meanNn = nnIntervals.average()
        val sdnn = sqrt(nnIntervals.map { (it - meanNn) * (it - meanNn) }.average())
        val cvNn = if (meanNn > 0) sdnn / meanNn else 0.0

        // Successive differences
        val diffs = (1 until nnIntervals.size).map { nnIntervals[it] - nnIntervals[it - 1] }
        val rmssd = sqrt(diffs.map { it * it }.average())
        val pnn50 = diffs.count { abs(it) > 50.0 } * 100.0 / diffs.size

        // Instantaneous HR from each NN interval
        val instHr = nnIntervals.map { 60000.0 / it }
        val meanHr = instHr.average()
        val hrStd = sqrt(instHr.map { (it - meanHr) * (it - meanHr) }.average())
        val hrMin = instHr.min()
        val hrMax = instHr.max()
        val hrRange = hrMax - hrMin

        // HR slope: linear regression of instantaneous HR vs cumulative time (seconds)
        val cumulativeTimeS = DoubleArray(nnIntervals.size)
        var cumSum = 0.0
        for (i in nnIntervals.indices) {
            cumSum += nnIntervals[i] / 1000.0
            cumulativeTimeS[i] = cumSum
        }
        val hrSlope = linearRegressionSlope(cumulativeTimeS, instHr.toDoubleArray())

        // NN quality ratio
        val nnQualityRatio = if (peaksDetected > 1) {
            nnIntervals.size.toDouble() / (peaksDetected - 1).toDouble()
        } else 0.0

        return TimeDomainResult(
            meanHr, hrStd, hrMin, hrMax, hrRange, hrSlope, nnQualityRatio,
            sdnn, rmssd, pnn50, meanNn, cvNn
        )
    }

    // --- Frequency-domain features ---

    private data class FreqDomainResult(
        val lfPower: Double, val hfPower: Double, val lfHfRatio: Double, val totalPower: Double
    )

    private fun computeFrequencyDomainFeatures(nnIntervals: List<Double>): FreqDomainResult {
        if (nnIntervals.size < 4) {
            return FreqDomainResult(0.0, 0.0, 0.0, 0.0)
        }

        // Interpolate NN intervals to uniform 4 Hz time series
        val uniformSeries = interpolateNnToUniform(nnIntervals, INTERP_FS)
        if (uniformSeries.size < 8) {
            return FreqDomainResult(0.0, 0.0, 0.0, 0.0)
        }

        // Welch PSD
        val (freqs, psd) = welchPsd(uniformSeries, INTERP_FS)

        val lfPower = bandPower(freqs, psd, LF_LOW, LF_HIGH)
        val hfPower = bandPower(freqs, psd, HF_LOW, HF_HIGH)
        val totalPower = lfPower + hfPower
        val lfHfRatio = if (hfPower > 0) lfPower / hfPower else 0.0

        return FreqDomainResult(lfPower, hfPower, lfHfRatio, totalPower)
    }

    private fun interpolateNnToUniform(nnIntervals: List<Double>, fs: Double): DoubleArray {
        // Cumulative time in seconds for each NN interval endpoint
        val times = DoubleArray(nnIntervals.size + 1)
        for (i in nnIntervals.indices) {
            times[i + 1] = times[i] + nnIntervals[i] / 1000.0
        }
        // NN interval values at each point (step function: value at t[i+1] = nn[i])
        val totalDuration = times.last()
        val dt = 1.0 / fs
        val nSamples = (totalDuration / dt).toInt()
        if (nSamples < 2) return doubleArrayOf()

        val result = DoubleArray(nSamples)
        var j = 0
        for (i in 0 until nSamples) {
            val t = i * dt
            while (j < nnIntervals.size - 1 && times[j + 1] < t) j++
            // Linear interpolation between nn[j] and nn[j+1] if possible
            if (j < nnIntervals.size - 1) {
                val t0 = times[j + 1]
                val t1 = times[j + 2]
                val frac = if (t1 > t0) (t - t0) / (t1 - t0) else 0.0
                result[i] = nnIntervals[j] + frac * (nnIntervals[j + 1] - nnIntervals[j])
            } else {
                result[i] = nnIntervals.last()
            }
        }

        // Remove mean (detrend)
        val mean = result.average()
        for (i in result.indices) result[i] -= mean
        return result
    }

    private fun welchPsd(data: DoubleArray, fs: Double): Pair<DoubleArray, DoubleArray> {
        val segLen = minOf(data.size, 128).let { nextPowerOf2(it) }
        val overlap = segLen / 2
        val step = segLen - overlap

        // Hanning window
        val window = DoubleArray(segLen) { 0.5 * (1.0 - cos(2.0 * PI * it / (segLen - 1))) }
        val windowPower = window.map { it * it }.average()

        val nSegments = ((data.size - segLen) / step) + 1
        val psdAccum = DoubleArray(segLen / 2 + 1)

        for (seg in 0 until nSegments) {
            val start = seg * step
            val segment = DoubleArray(segLen) { data[start + it] * window[it] }
            val fftResult = fft(segment)

            for (k in 0..segLen / 2) {
                val re = fftResult[2 * k]
                val im = fftResult[2 * k + 1]
                val power = (re * re + im * im) / (fs * segLen * windowPower)
                psdAccum[k] += if (k > 0 && k < segLen / 2) 2.0 * power else power
            }
        }

        val freqs = DoubleArray(segLen / 2 + 1) { it * fs / segLen }
        for (i in psdAccum.indices) psdAccum[i] /= nSegments
        return Pair(freqs, psdAccum)
    }

    private fun bandPower(freqs: DoubleArray, psd: DoubleArray, fLow: Double, fHigh: Double): Double {
        var power = 0.0
        val df = if (freqs.size > 1) freqs[1] - freqs[0] else 1.0
        for (i in freqs.indices) {
            if (freqs[i] in fLow..fHigh) {
                power += psd[i] * df
            }
        }
        return power
    }

    /** Radix-2 Cooley-Tukey FFT. Returns interleaved [re0, im0, re1, im1, ...] */
    private fun fft(data: DoubleArray): DoubleArray {
        val n = data.size
        // Pack into complex: real parts from data, imaginary = 0
        val re = data.copyOf()
        val im = DoubleArray(n)

        // Bit-reversal permutation
        var j = 0
        for (i in 0 until n) {
            if (i < j) {
                var tmp = re[i]; re[i] = re[j]; re[j] = tmp
                tmp = im[i]; im[i] = im[j]; im[j] = tmp
            }
            var m = n / 2
            while (m >= 1 && j >= m) { j -= m; m /= 2 }
            j += m
        }

        // FFT butterfly
        var step = 1
        while (step < n) {
            val halfStep = step
            step *= 2
            val wRe = cos(PI / halfStep)
            val wIm = -sin(PI / halfStep)
            for (group in 0 until n step step) {
                var tRe = 1.0
                var tIm = 0.0
                for (pair in 0 until halfStep) {
                    val a = group + pair
                    val b = a + halfStep
                    val bRe = re[b] * tRe - im[b] * tIm
                    val bIm = re[b] * tIm + im[b] * tRe
                    re[b] = re[a] - bRe
                    im[b] = im[a] - bIm
                    re[a] += bRe
                    im[a] += bIm
                    val newTRe = tRe * wRe - tIm * wIm
                    tIm = tRe * wIm + tIm * wRe
                    tRe = newTRe
                }
            }
        }

        // Interleave
        val result = DoubleArray(2 * n)
        for (i in 0 until n) {
            result[2 * i] = re[i]
            result[2 * i + 1] = im[i]
        }
        return result
    }

    private fun nextPowerOf2(n: Int): Int {
        var p = 1
        while (p < n) p *= 2
        return p
    }

    // --- SpO2 computation ---

    private data class SpO2Result(val mean: Double, val min: Double, val std: Double)

    private fun computeSpO2(
        samples: List<PpgSample>,
        peakIndices: List<Int>,
        sampleRateHz: Double
    ): SpO2Result {
        // Need IR and Red channels + at least 2 peaks for one cardiac cycle
        if (peakIndices.size < 2) return SpO2Result(0.0, 0.0, 0.0)

        val irValues = samples.map { it.ir.toDouble() }.toDoubleArray()
        val redValues = samples.map { it.red.toDouble() }.toDoubleArray()

        // Check if IR/Red data is present (non-zero)
        if (irValues.all { it == 0.0 } || redValues.all { it == 0.0 }) {
            return SpO2Result(0.0, 0.0, 0.0)
        }

        // Bandpass filter IR and Red
        val irFiltered = filtFilt(irValues, sampleRateHz, LOW_CUTOFF_HZ, HIGH_CUTOFF_HZ)
        val redFiltered = filtFilt(redValues, sampleRateHz, LOW_CUTOFF_HZ, HIGH_CUTOFF_HZ)

        val spo2Values = mutableListOf<Double>()
        for (i in 0 until peakIndices.size - 1) {
            val start = peakIndices[i]
            val end = peakIndices[i + 1]
            if (end - start < 3) continue

            // AC = peak-to-trough amplitude of filtered signal
            // DC = mean of raw signal in the cardiac cycle
            val irSegFiltered = irFiltered.sliceArray(start until end)
            val redSegFiltered = redFiltered.sliceArray(start until end)
            val irSegRaw = irValues.sliceArray(start until end)
            val redSegRaw = redValues.sliceArray(start until end)

            val irAc = (irSegFiltered.max() - irSegFiltered.min())
            val irDc = irSegRaw.average()
            val redAc = (redSegFiltered.max() - redSegFiltered.min())
            val redDc = redSegRaw.average()

            if (irDc == 0.0 || redDc == 0.0 || irAc == 0.0) continue

            val r = (redAc / redDc) / (irAc / irDc)
            val spo2 = (110.0 - 25.0 * r).coerceIn(50.0, 100.0)
            spo2Values.add(spo2)
        }

        if (spo2Values.isEmpty()) return SpO2Result(0.0, 0.0, 0.0)

        val mean = spo2Values.average()
        val min = spo2Values.min()
        val std = if (spo2Values.size > 1) {
            sqrt(spo2Values.map { (it - mean) * (it - mean) }.average())
        } else 0.0

        return SpO2Result(mean, min, std)
    }

    // --- Shared filter infrastructure ---

    fun filtFilt(data: DoubleArray, fs: Double, lowCutoff: Double, highCutoff: Double): DoubleArray {
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

    data class BiquadCoeffs(
        val b0: Double, val b1: Double, val b2: Double,
        val a1: Double, val a2: Double
    )

    fun butterworth2ndOrderLowpass(fs: Double, fc: Double): BiquadCoeffs {
        val wc = tan(PI * fc / fs)
        val wc2 = wc * wc
        val sqrt2 = sqrt(2.0)
        val norm = 1.0 / (1.0 + sqrt2 * wc + wc2)
        return BiquadCoeffs(
            b0 = wc2 * norm, b1 = 2.0 * wc2 * norm, b2 = wc2 * norm,
            a1 = 2.0 * (wc2 - 1.0) * norm, a2 = (1.0 - sqrt2 * wc + wc2) * norm
        )
    }

    fun butterworth2ndOrderHighpass(fs: Double, fc: Double): BiquadCoeffs {
        val wc = tan(PI * fc / fs)
        val wc2 = wc * wc
        val sqrt2 = sqrt(2.0)
        val norm = 1.0 / (1.0 + sqrt2 * wc + wc2)
        return BiquadCoeffs(
            b0 = norm, b1 = -2.0 * norm, b2 = norm,
            a1 = 2.0 * (wc2 - 1.0) * norm, a2 = (1.0 - sqrt2 * wc + wc2) * norm
        )
    }

    fun applyFilter(data: DoubleArray, c: BiquadCoeffs): DoubleArray {
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
                if (!inRoi) { inRoi = true; roiStart = i }
            } else {
                if (inRoi) { peaks.add(findMaxInRange(signal, roiStart, i)); inRoi = false }
            }
        }
        if (inRoi) peaks.add(findMaxInRange(signal, roiStart, signal.size))
        return peaks
    }

    private fun findMaxInRange(signal: DoubleArray, from: Int, to: Int): Int {
        var maxIdx = from
        var maxVal = signal[from]
        for (j in from until to) {
            if (signal[j] > maxVal) { maxVal = signal[j]; maxIdx = j }
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

    // --- Utilities ---

    private fun linearRegressionSlope(x: DoubleArray, y: DoubleArray): Double {
        if (x.size < 2) return 0.0
        val n = x.size.toDouble()
        val sumX = x.sum()
        val sumY = y.sum()
        val sumXY = x.zip(y.toList()).sumOf { it.first * it.second }
        val sumX2 = x.sumOf { it * it }
        val denom = n * sumX2 - sumX * sumX
        return if (denom != 0.0) (n * sumXY - sumX * sumY) / denom else 0.0
    }
}
