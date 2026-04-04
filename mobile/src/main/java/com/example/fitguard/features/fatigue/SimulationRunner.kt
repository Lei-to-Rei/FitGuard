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
        val seqId = "sim_${System.currentTimeMillis()}"

        processor.accumulator.addPpgSamples(seqId, 1, ppgSamples)
        processor.accumulator.addAccelSamples(seqId, 1, accelSamples)
        processor.accumulator.markBatchReceived(seqId, 1, 1)

        val spanMs = ppgSamples.last().timestamp - ppgSamples.first().timestamp
        val windowCount = ((spanMs - 60_000L) / 15_000L + 1).coerceAtLeast(0).toInt()
        Log.d(TAG, "Simulation submitted. Estimated $windowCount windows from ${spanMs}ms span.")

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
