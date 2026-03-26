package com.example.fitguard.features.sleep

import kotlin.math.sqrt

data class SleepEpoch(
    val epochStartMs: Long,
    val epochEndMs: Long,
    val hrValues: List<Int>,
    val ibiValues: List<Int>,
    val accelMovementVar: Float,
    val accelSampleCount: Int,
    val skinTempValues: List<Float>,
    val spo2Values: List<Int>
)

class SleepEpochAccumulator {

    companion object {
        const val EPOCH_DURATION_MS = 5 * 60 * 1000L // 5 minutes
    }

    private var sessionStartMs: Long = 0L

    // HR and IBI stored per-sample (moderate volume: ~1 per second)
    private val hrSamples = mutableListOf<Pair<Long, Int>>()       // timestamp, bpm
    private val ibiSamples = mutableListOf<Pair<Long, Int>>()      // timestamp, ibi_ms

    // Accelerometer: pre-aggregated per epoch to avoid memory blow-up
    // Key = epoch index, Value = running stats for accel magnitude
    private val accelEpochStats = mutableMapOf<Int, AccelRunningStats>()

    // Skin temp and SpO2: sparse (on-demand, periodic restart)
    private val skinTempSamples = mutableListOf<Pair<Long, Float>>()  // timestamp, objectTemp
    private val spo2Samples = mutableListOf<Pair<Long, Int>>()        // timestamp, spo2%

    private data class AccelRunningStats(
        var count: Int = 0,
        var sum: Double = 0.0,
        var sumOfSquares: Double = 0.0
    ) {
        fun add(magnitude: Double) {
            count++
            sum += magnitude
            sumOfSquares += magnitude * magnitude
        }

        fun variance(): Float {
            if (count < 2) return 0f
            val mean = sum / count
            return ((sumOfSquares / count) - (mean * mean)).toFloat().coerceAtLeast(0f)
        }
    }

    fun setSessionStart(startMs: Long) {
        sessionStartMs = startMs
    }

    fun addHeartRate(timestamp: Long, hr: Int, ibis: List<Int>) {
        if (hr > 0) hrSamples.add(timestamp to hr)
        ibis.filter { it in 300..2000 }.forEach { ibiSamples.add(timestamp to it) }
    }

    fun addAccelerometer(timestamp: Long, x: Int, y: Int, z: Int) {
        if (sessionStartMs == 0L) return
        val magnitude = sqrt((x.toLong() * x + y.toLong() * y + z.toLong() * z).toDouble())
        val epochIndex = ((timestamp - sessionStartMs) / EPOCH_DURATION_MS).toInt()
        val stats = accelEpochStats.getOrPut(epochIndex) { AccelRunningStats() }
        stats.add(magnitude)
    }

    fun addSkinTemperature(timestamp: Long, objectTemp: Float) {
        if (objectTemp > 0f) skinTempSamples.add(timestamp to objectTemp)
    }

    fun addSpO2(timestamp: Long, spo2: Int) {
        if (spo2 > 0) spo2Samples.add(timestamp to spo2)
    }

    fun finalizeEpochs(): List<SleepEpoch> {
        if (sessionStartMs == 0L) return emptyList()

        val allTimestamps = hrSamples.map { it.first } +
                ibiSamples.map { it.first } +
                skinTempSamples.map { it.first } +
                spo2Samples.map { it.first }

        if (allTimestamps.isEmpty() && accelEpochStats.isEmpty()) return emptyList()

        val minTime = (allTimestamps.minOrNull() ?: sessionStartMs)
            .coerceAtMost(sessionStartMs)
        val maxTime = allTimestamps.maxOrNull() ?: (sessionStartMs + EPOCH_DURATION_MS)

        val epochs = mutableListOf<SleepEpoch>()
        var epochStart = sessionStartMs
        var epochIndex = 0

        while (epochStart < maxTime) {
            val epochEnd = epochStart + EPOCH_DURATION_MS

            val epoch = SleepEpoch(
                epochStartMs = epochStart,
                epochEndMs = epochEnd,
                hrValues = hrSamples
                    .filter { it.first in epochStart until epochEnd }
                    .map { it.second },
                ibiValues = ibiSamples
                    .filter { it.first in epochStart until epochEnd }
                    .map { it.second },
                accelMovementVar = accelEpochStats[epochIndex]?.variance() ?: 0f,
                accelSampleCount = accelEpochStats[epochIndex]?.count ?: 0,
                skinTempValues = skinTempSamples
                    .filter { it.first in epochStart until epochEnd }
                    .map { it.second },
                spo2Values = spo2Samples
                    .filter { it.first in epochStart until epochEnd }
                    .map { it.second }
            )
            epochs.add(epoch)
            epochStart = epochEnd
            epochIndex++
        }
        return epochs
    }

    fun clear() {
        hrSamples.clear()
        ibiSamples.clear()
        accelEpochStats.clear()
        skinTempSamples.clear()
        spo2Samples.clear()
        sessionStartMs = 0L
    }
}
