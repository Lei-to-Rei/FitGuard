package com.example.fitguard.features.fatigue

import android.content.Context
import android.os.Environment
import android.util.Log
import com.example.fitguard.data.processing.AccelSample
import com.example.fitguard.data.processing.PpgSample
import com.example.fitguard.data.processing.SequenceProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

object SimulationRunner {

    private const val TAG = "SimulationRunner"
    private const val BATCH_DURATION_MS = 60_000L  // 60s per batch, matching watch collection interval

    sealed class SimResult {
        data class Success(val windowsQueued: Int) : SimResult()
        data class Error(val message: String) : SimResult()
    }

    suspend fun run(context: Context, userId: String): SimResult = withContext(Dispatchers.IO) {
        if (userId.isBlank()) {
            return@withContext SimResult.Error("Not signed in — cannot locate simulation files.")
        }

        val simDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "FitGuard_Data/$userId/simulation"
        )

        if (!simDir.exists()) {
            return@withContext SimResult.Error("Simulation folder not found: ${simDir.absolutePath}")
        }

        // Find PPG_*.jsonl and Accelerometer_*.jsonl
        val ppgFile = simDir.listFiles { f -> f.name.startsWith("PPG_") && f.name.endsWith(".jsonl") }
            ?.maxByOrNull { it.lastModified() }
        val accelFile = simDir.listFiles { f -> f.name.startsWith("Accelerometer_") && f.name.endsWith(".jsonl") }
            ?.maxByOrNull { it.lastModified() }

        if (ppgFile == null) {
            return@withContext SimResult.Error("No PPG_*.jsonl file found in ${simDir.absolutePath}")
        }
        if (accelFile == null) {
            return@withContext SimResult.Error("No Accelerometer_*.jsonl file found in ${simDir.absolutePath}")
        }

        Log.d(TAG, "Using PPG: ${ppgFile.name}, Accel: ${accelFile.name}")

        val ppgSamples = try {
            parsePpgJsonl(ppgFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse ${ppgFile.name}", e)
            return@withContext SimResult.Error("${ppgFile.name} parse error: ${e.message}")
        }

        val accelSamples = try {
            parseAccelJsonl(accelFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse ${accelFile.name}", e)
            return@withContext SimResult.Error("${accelFile.name} parse error: ${e.message}")
        }

        if (ppgSamples.isEmpty()) {
            return@withContext SimResult.Error("${ppgFile.name} contains no samples.")
        }
        if (accelSamples.isEmpty()) {
            return@withContext SimResult.Error("${accelFile.name} contains no samples.")
        }

        Log.d(TAG, "Loaded ${ppgSamples.size} PPG samples, ${accelSamples.size} accel samples")

        // Reset static pipeline state so simulation starts fresh
        SequenceProcessor.clearBuffer()

        val processor = SequenceProcessor(context)
        val simTs = System.currentTimeMillis()

        // Sort by timestamp to ensure correct ordering
        val sortedPpg = ppgSamples.sortedBy { it.timestamp }
        val sortedAccel = accelSamples.sortedBy { it.timestamp }

        // Determine the time range from PPG (primary signal)
        val dataStart = sortedPpg.first().timestamp
        val dataEnd = sortedPpg.last().timestamp
        val spanMs = dataEnd - dataStart

        // Split into 60-second batches, matching real watch behavior:
        // watch sends ~1600 PPG + ~1500 accel samples every 60 seconds
        var batchStart = dataStart
        var batchIdx = 0
        while (batchStart < dataEnd) {
            val batchEnd = batchStart + BATCH_DURATION_MS

            val batchPpg = sortedPpg.filter { it.timestamp in batchStart until batchEnd }
            val batchAccel = sortedAccel.filter { it.timestamp in batchStart until batchEnd }

            if (batchPpg.isNotEmpty()) {
                val seqId = "sim_${simTs}_$batchIdx"
                processor.accumulator.addPpgSamples(seqId, 1, batchPpg)
                processor.accumulator.addAccelSamples(seqId, 1, batchAccel)
                processor.accumulator.markBatchReceived(seqId, 1, 1)
                Log.d(TAG, "Batch $batchIdx: ${batchPpg.size} PPG, ${batchAccel.size} accel " +
                        "(${batchStart}-${batchEnd}ms)")
            }

            batchStart = batchEnd
            batchIdx++
        }

        val windowCount = ((spanMs - 60_000L) / 15_000L + 1).coerceAtLeast(0).toInt()
        Log.d(TAG, "Simulation submitted: $batchIdx batches, estimated $windowCount windows from ${spanMs}ms span.")

        SimResult.Success(windowCount)
    }

    // JSONL: one JSON object per line
    // {"type":"PPG","green":83448,"ir":77129,"red":67607,"timestamp":1772262827342,...}
    private fun parsePpgJsonl(file: File): List<PpgSample> {
        return file.bufferedReader().lineSequence()
            .filter { it.isNotBlank() }
            .map { line ->
                val obj = JSONObject(line)
                PpgSample(
                    timestamp = obj.getLong("timestamp"),
                    green = obj.getInt("green"),
                    ir = obj.optInt("ir", 0),
                    red = obj.optInt("red", 0)
                )
            }
            .toList()
    }

    // JSONL: one JSON object per line
    // {"type":"Accelerometer","x":-2952,"y":-2617,"z":1134,"timestamp":1772262827196,...}
    private fun parseAccelJsonl(file: File): List<AccelSample> {
        return file.bufferedReader().lineSequence()
            .filter { it.isNotBlank() }
            .map { line ->
                val obj = JSONObject(line)
                AccelSample(
                    timestamp = obj.getLong("timestamp"),
                    x = obj.getInt("x").toFloat(),
                    y = obj.getInt("y").toFloat(),
                    z = obj.getInt("z").toFloat()
                )
            }
            .toList()
    }
}
