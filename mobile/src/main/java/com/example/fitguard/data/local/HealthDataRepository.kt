package com.example.fitguard.data.local

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.text.SimpleDateFormat
import java.util.*

/**
 * Repository for accessing locally stored health data
 */
class HealthDataRepository(private val context: Context) {

    companion object {
        private const val TAG = "HealthDataRepository"
        private const val DATA_FOLDER = "FitGuard_Data"
    }

    /**
     * Represents a single health data entry
     */
    sealed class HealthEntry {
        abstract val timestamp: Long
        abstract val type: String

        data class PPGEntry(
            override val timestamp: Long,
            val green: Int,
            val ir: Int,
            val red: Int
        ) : HealthEntry() {
            override val type = "PPG"
        }

        data class HeartRateEntry(
            override val timestamp: Long,
            val heartRate: Int,
            val ibiList: List<Int>,
            val status: Int
        ) : HealthEntry() {
            override val type = "HeartRate"
        }

        data class SpO2Entry(
            override val timestamp: Long,
            val spo2: Int,
            val heartRate: Int,
            val status: Int
        ) : HealthEntry() {
            override val type = "SpO2"
        }

        data class ECGEntry(
            override val timestamp: Long,
            val ecgMv: Float,
            val ppgGreen: Int,
            val sequence: Int,
            val leadOff: Int
        ) : HealthEntry() {
            override val type = "ECG"
        }

        data class SkinTempEntry(
            override val timestamp: Long,
            val objectTemp: Float?,
            val ambientTemp: Float?,
            val status: Int
        ) : HealthEntry() {
            override val type = "SkinTemp"
        }

        data class BIAEntry(
            override val timestamp: Long,
            val bmr: Float,
            val bodyFatMass: Float,
            val bodyFatRatio: Float,
            val fatFreeMass: Float,
            val muscleMass: Float
        ) : HealthEntry() {
            override val type = "BIA"
        }

        data class SweatEntry(
            override val timestamp: Long,
            val sweatLoss: Float
        ) : HealthEntry() {
            override val type = "Sweat"
        }
    }

    /**
     * Get the data directory
     */
    private fun getDataDirectory(): File {
        // Try app-specific external storage first
        val appSpecific = context.getExternalFilesDir(DATA_FOLDER)
        if (appSpecific != null && appSpecific.exists() && appSpecific.listFiles()?.isNotEmpty() == true) {
            return appSpecific
        }

        // Fall back to Downloads folder
        @Suppress("DEPRECATION")
        val downloads = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            DATA_FOLDER
        )
        if (downloads.exists()) {
            return downloads
        }

        // Return app-specific anyway
        return appSpecific ?: context.getDir(DATA_FOLDER, Context.MODE_PRIVATE)
    }

    /**
     * Get available dates with data
     */
    suspend fun getAvailableDates(): List<String> = withContext(Dispatchers.IO) {
        val dir = getDataDirectory()
        if (!dir.exists()) return@withContext emptyList()

        val dates = mutableSetOf<String>()
        dir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".jsonl")) {
                // Extract date from filename like "PPG_2024-01-15.jsonl"
                val parts = file.nameWithoutExtension.split("_")
                if (parts.size >= 2) {
                    dates.add(parts.last())
                }
            }
        }

        dates.sortedDescending()
    }

    /**
     * Get available data types for a date
     */
    suspend fun getAvailableTypes(date: String): List<String> = withContext(Dispatchers.IO) {
        val dir = getDataDirectory()
        if (!dir.exists()) return@withContext emptyList()

        dir.listFiles()
            ?.filter { it.name.endsWith("_$date.jsonl") }
            ?.map { it.nameWithoutExtension.substringBefore("_") }
            ?: emptyList()
    }

    /**
     * Load PPG data for a specific date
     */
    suspend fun loadPPGData(date: String): List<HealthEntry.PPGEntry> = withContext(Dispatchers.IO) {
        loadData("PPG", date) { json ->
            HealthEntry.PPGEntry(
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                green = json.optInt("green", 0),
                ir = json.optInt("ir", 0),
                red = json.optInt("red", 0)
            )
        }
    }

    /**
     * Load Heart Rate data for a specific date
     */
    suspend fun loadHeartRateData(date: String): List<HealthEntry.HeartRateEntry> = withContext(Dispatchers.IO) {
        loadData("HeartRate", date) { json ->
            val ibiArray = json.optJSONArray("ibi_list")
            val ibiList = mutableListOf<Int>()
            if (ibiArray != null) {
                for (i in 0 until ibiArray.length()) {
                    ibiList.add(ibiArray.optInt(i, 0))
                }
            }

            HealthEntry.HeartRateEntry(
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                heartRate = json.optInt("heart_rate", json.optInt("hr", 0)),
                ibiList = ibiList,
                status = json.optInt("status", 0)
            )
        }
    }

    /**
     * Load SpO2 data for a specific date
     */
    suspend fun loadSpO2Data(date: String): List<HealthEntry.SpO2Entry> = withContext(Dispatchers.IO) {
        loadData("SpO2", date) { json ->
            HealthEntry.SpO2Entry(
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                spo2 = json.optInt("spo2", 0),
                heartRate = json.optInt("heart_rate", json.optInt("hr", 0)),
                status = json.optInt("status", 0)
            )
        }
    }

    /**
     * Load ECG data for a specific date
     */
    suspend fun loadECGData(date: String): List<HealthEntry.ECGEntry> = withContext(Dispatchers.IO) {
        loadData("ECG", date) { json ->
            HealthEntry.ECGEntry(
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                ecgMv = json.optDouble("ecg_mv", 0.0).toFloat(),
                ppgGreen = json.optInt("ppg_green", 0),
                sequence = json.optInt("sequence", 0),
                leadOff = json.optInt("lead_off", 0)
            )
        }
    }

    /**
     * Load Skin Temperature data for a specific date
     */
    suspend fun loadSkinTempData(date: String): List<HealthEntry.SkinTempEntry> = withContext(Dispatchers.IO) {
        loadData("SkinTemp", date) { json ->
            HealthEntry.SkinTempEntry(
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                objectTemp = if (json.has("obj") || json.has("object_temp")) {
                    json.optDouble("obj", json.optDouble("object_temp", Double.NaN)).toFloat()
                        .takeIf { !it.isNaN() }
                } else null,
                ambientTemp = if (json.has("amb") || json.has("ambient_temp")) {
                    json.optDouble("amb", json.optDouble("ambient_temp", Double.NaN)).toFloat()
                        .takeIf { !it.isNaN() }
                } else null,
                status = json.optInt("status", 0)
            )
        }
    }

    /**
     * Load BIA data for a specific date
     */
    suspend fun loadBIAData(date: String): List<HealthEntry.BIAEntry> = withContext(Dispatchers.IO) {
        loadData("BIA", date) { json ->
            HealthEntry.BIAEntry(
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                bmr = json.optDouble("bmr", 0.0).toFloat(),
                bodyFatMass = json.optDouble("body_fat_mass", json.optDouble("fat_mass", 0.0)).toFloat(),
                bodyFatRatio = json.optDouble("body_fat_ratio", json.optDouble("fat_ratio", 0.0)).toFloat(),
                fatFreeMass = json.optDouble("fat_free_mass", json.optDouble("ffm", 0.0)).toFloat(),
                muscleMass = json.optDouble("skeletal_muscle_mass", json.optDouble("muscle", 0.0)).toFloat()
            )
        }
    }

    /**
     * Load Sweat Loss data for a specific date
     */
    suspend fun loadSweatData(date: String): List<HealthEntry.SweatEntry> = withContext(Dispatchers.IO) {
        loadData("Sweat", date) { json ->
            HealthEntry.SweatEntry(
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                sweatLoss = json.optDouble("sweat_loss", json.optDouble("loss", 0.0)).toFloat()
            )
        }
    }

    /**
     * Generic data loader
     */
    private inline fun <T : HealthEntry> loadData(
        type: String,
        date: String,
        crossinline parser: (JSONObject) -> T
    ): List<T> {
        val dir = getDataDirectory()
        val file = File(dir, "${type}_$date.jsonl")

        if (!file.exists()) {
            Log.d(TAG, "File not found: ${file.absolutePath}")
            return emptyList()
        }

        val entries = mutableListOf<T>()

        try {
            BufferedReader(FileReader(file)).use { reader ->
                reader.forEachLine { line ->
                    if (line.isNotBlank()) {
                        try {
                            val json = JSONObject(line)
                            entries.add(parser(json))
                        } catch (e: Exception) {
                            Log.w(TAG, "Error parsing line: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading file: ${e.message}", e)
        }

        return entries.sortedBy { it.timestamp }
    }

    /**
     * Get data summary for a date
     */
    suspend fun getDataSummary(date: String): DataSummary = withContext(Dispatchers.IO) {
        val types = getAvailableTypes(date)
        val counts = mutableMapOf<String, Int>()

        types.forEach { type ->
            val file = File(getDataDirectory(), "${type}_$date.jsonl")
            if (file.exists()) {
                counts[type] = file.readLines().count { it.isNotBlank() }
            }
        }

        DataSummary(date, counts)
    }

    data class DataSummary(
        val date: String,
        val entryCounts: Map<String, Int>
    ) {
        val totalEntries: Int get() = entryCounts.values.sum()
    }

    /**
     * Load PPG data for analysis (returns arrays suitable for PPGAnalyzer)
     */
    suspend fun loadPPGForAnalysis(date: String): PPGAnalysisData? = withContext(Dispatchers.IO) {
        val entries = loadPPGData(date)
        if (entries.isEmpty()) return@withContext null

        PPGAnalysisData(
            greenSignal = entries.map { it.green.toDouble() }.toDoubleArray(),
            irSignal = entries.map { it.ir.toDouble() }.toDoubleArray(),
            redSignal = entries.map { it.red.toDouble() }.toDoubleArray(),
            timestamps = entries.map { it.timestamp }.toLongArray()
        )
    }

    data class PPGAnalysisData(
        val greenSignal: DoubleArray,
        val irSignal: DoubleArray,
        val redSignal: DoubleArray,
        val timestamps: LongArray
    ) {
        val sampleCount: Int get() = greenSignal.size

        val durationSeconds: Double get() = if (timestamps.size >= 2) {
            (timestamps.last() - timestamps.first()) / 1000.0
        } else 0.0

        val estimatedSampleRate: Double get() = if (durationSeconds > 0) {
            sampleCount / durationSeconds
        } else 25.0 // Default
    }

    /**
     * Get today's date string
     */
    fun getTodayDateString(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

    /**
     * Delete data for a specific date
     */
    suspend fun deleteData(date: String): Boolean = withContext(Dispatchers.IO) {
        val dir = getDataDirectory()
        var deletedAny = false

        dir.listFiles()?.filter { it.name.endsWith("_$date.jsonl") }?.forEach { file ->
            if (file.delete()) {
                deletedAny = true
                Log.d(TAG, "Deleted: ${file.name}")
            }
        }

        deletedAny
    }

    /**
     * Get total storage used by health data (in bytes)
     */
    suspend fun getStorageUsed(): Long = withContext(Dispatchers.IO) {
        val dir = getDataDirectory()
        if (!dir.exists()) return@withContext 0L

        dir.listFiles()?.sumOf { it.length() } ?: 0L
    }
}