package com.example.fitguard.data.processing

import android.content.Context
import android.os.Environment
import android.util.Log
import com.google.android.gms.tflite.java.TfLite
import com.google.android.gms.tasks.Tasks
import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.InterpreterApi
import org.tensorflow.lite.InterpreterApi.Options.TfLiteRuntime
import java.io.File
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

data class ScalerParams(
    val mean: FloatArray,
    val std: FloatArray,
    val featureNames: List<String>,
    val fatigueThresholds: Map<String, Float>,
    val fatigueLevelNames: List<String>
)

data class FatigueResult(
    val pLow: Float,
    val pHigh: Float,
    val level: String,
    val levelIndex: Int,
    val percentDisplay: Int
)

class FatigueDetector(private val context: Context) {
    companion object {
        private const val TAG = "FatigueDetector"
        private const val SEQ_LENGTH = 5
        private const val LSTM_UNITS = 64
        private const val DEFAULT_MODEL = "fatigue_model.tflite"
        private const val DEFAULT_SCALER = "scaler_params.json"
    }

    private var interpreter: InterpreterApi? = null
    private var scaler: ScalerParams? = null
    private val windowBuffer = ArrayDeque<FloatArray>()

    // LSTM state carried across inference calls within a session
    private var stateH = Array(1) { FloatArray(LSTM_UNITS) }
    private var stateC = Array(1) { FloatArray(LSTM_UNITS) }

    // Tensor indices resolved at init by name
    private var inputSeqIdx = 0
    private var inputStateHIdx = 1
    private var inputStateCIdx = 2
    private var outputProbsIdx = 0
    private var outputStateHIdx = 1
    private var outputStateCIdx = 2

    val isReady: Boolean get() = interpreter != null && scaler != null
    val bufferedWindows: Int get() = windowBuffer.size

    fun initialize(): Boolean {
        return try {
            Tasks.await(TfLite.initialize(context))
            scaler = loadScalerFromAssets(DEFAULT_SCALER)
            val options = InterpreterApi.Options().setRuntime(TfLiteRuntime.FROM_SYSTEM_ONLY)
            interpreter = InterpreterApi.create(loadModelFromAssets(DEFAULT_MODEL), options)
            resolveTensorIndices()
            Log.d(TAG, "Initialized with default model and scaler")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize: ${e.message}", e)
            false
        }
    }

    fun initializePersonalized(userId: String): Boolean {
        val dataDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "FitGuard_Data/$userId/personalized"
        )
        val modelFile = File(dataDir, "user_${userId}_model.tflite")
        val scalerFile = File(dataDir, "user_${userId}_scaler.json")

        val hasPersonalizedScaler = scalerFile.exists()
        val hasPersonalizedModel = modelFile.exists()

        if (!hasPersonalizedScaler) return false

        return try {
            Tasks.await(TfLite.initialize(context))
            scaler = loadScalerFromFile(scalerFile)
            val options = InterpreterApi.Options().setRuntime(TfLiteRuntime.FROM_SYSTEM_ONLY)
            if (hasPersonalizedModel) {
                interpreter = InterpreterApi.create(loadModelFromFile(modelFile), options)
                Log.d(TAG, "Initialized personalized model + scaler for user $userId")
            } else {
                interpreter = InterpreterApi.create(loadModelFromAssets(DEFAULT_MODEL), options)
                Log.d(TAG, "Initialized base model + personalized scaler for user $userId")
            }
            resolveTensorIndices()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load personalized config: ${e.message}", e)
            false
        }
    }

    private fun resolveTensorIndices() {
        val interp = interpreter ?: return

        // Resolve inputs by shape: [1,5,30] = sequence, [1,64] = LSTM states
        val stateInputIndices = mutableListOf<Int>()
        for (i in 0 until interp.inputTensorCount) {
            val tensor = interp.getInputTensor(i)
            val shape = tensor.shape()
            val name = tensor.name()
            Log.d(TAG, "Input tensor $i: name=$name, shape=${shape.contentToString()}")

            when {
                shape.contentEquals(intArrayOf(1, SEQ_LENGTH, 30)) -> inputSeqIdx = i
                shape.contentEquals(intArrayOf(1, LSTM_UNITS)) -> {
                    // Try name hint first, otherwise collect for ordered assignment
                    when {
                        name.contains("state_c") -> inputStateCIdx = i
                        name.contains("state_h") -> inputStateHIdx = i
                        else -> stateInputIndices.add(i)
                    }
                }
            }
        }
        // If names didn't distinguish H vs C, assign by index order
        if (stateInputIndices.size == 2) {
            inputStateHIdx = stateInputIndices[0]
            inputStateCIdx = stateInputIndices[1]
        } else if (stateInputIndices.size == 1) {
            // One was matched by name, assign the remaining one
            if (inputStateHIdx == inputSeqIdx) inputStateHIdx = stateInputIndices[0]
            else if (inputStateCIdx == inputSeqIdx) inputStateCIdx = stateInputIndices[0]
        }

        // Resolve outputs by shape: [1,2] = probs, [1,64] = LSTM states
        val stateOutputIndices = mutableListOf<Int>()
        for (i in 0 until interp.outputTensorCount) {
            val tensor = interp.getOutputTensor(i)
            val shape = tensor.shape()
            val name = tensor.name()
            Log.d(TAG, "Output tensor $i: name=$name, shape=${shape.contentToString()}")

            when {
                shape.contentEquals(intArrayOf(1, 2)) -> outputProbsIdx = i
                shape.contentEquals(intArrayOf(1, LSTM_UNITS)) -> {
                    when {
                        name.contains("state_c") -> outputStateCIdx = i
                        name.contains("state_h") -> outputStateHIdx = i
                        else -> stateOutputIndices.add(i)
                    }
                }
            }
        }
        if (stateOutputIndices.size == 2) {
            outputStateHIdx = stateOutputIndices[0]
            outputStateCIdx = stateOutputIndices[1]
        } else if (stateOutputIndices.size == 1) {
            if (outputStateHIdx == outputProbsIdx) outputStateHIdx = stateOutputIndices[0]
            else if (outputStateCIdx == outputProbsIdx) outputStateCIdx = stateOutputIndices[0]
        }

        Log.d(TAG, "Tensor indices — inputs: seq=$inputSeqIdx, stateH=$inputStateHIdx, stateC=$inputStateCIdx; " +
                "outputs: probs=$outputProbsIdx, stateH=$outputStateHIdx, stateC=$outputStateCIdx")
    }

    fun normalize(features: FloatArray): FloatArray {
        val s = scaler ?: return features
        return FloatArray(features.size) { i ->
            val std = if (s.std[i] != 0f) s.std[i] else 1f
            (features[i] - s.mean[i]) / std
        }
    }

    fun addWindowAndPredict(rawFeatures: FloatArray): FatigueResult? {
        val normalized = normalize(rawFeatures)
        windowBuffer.addLast(normalized)
        if (windowBuffer.size > SEQ_LENGTH) windowBuffer.removeFirst()
        if (windowBuffer.size < SEQ_LENGTH) return null
        return runInference()
    }

    /**
     * Normalize and buffer features WITHOUT running inference.
     * Keeps the sliding window in sync on non-prediction windows.
     */
    fun bufferWindowOnly(rawFeatures: FloatArray) {
        val normalized = normalize(rawFeatures)
        windowBuffer.addLast(normalized)
        if (windowBuffer.size > SEQ_LENGTH) windowBuffer.removeFirst()
    }

    fun predictBatch(windows: List<FloatArray>): FatigueResult? {
        if (windows.size < SEQ_LENGTH) return null
        // Use the last SEQ_LENGTH windows
        val recent = windows.takeLast(SEQ_LENGTH)
        val input = arrayOf(recent.map { normalize(it) }.toTypedArray())
        return runInferenceOn(input)
    }

    fun initializeWithScalerFile(scalerFile: File): Boolean {
        if (!scalerFile.exists()) return false
        return try {
            Tasks.await(TfLite.initialize(context))
            scaler = loadScalerFromFile(scalerFile)
            val options = InterpreterApi.Options().setRuntime(TfLiteRuntime.FROM_SYSTEM_ONLY)
            interpreter = InterpreterApi.create(loadModelFromAssets(DEFAULT_MODEL), options)
            resolveTensorIndices()
            Log.d(TAG, "Initialized with base model + scaler from ${scalerFile.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize with scaler ${scalerFile.name}: ${e.message}", e)
            false
        }
    }

    fun resetSessionState() {
        stateH = Array(1) { FloatArray(LSTM_UNITS) }
        stateC = Array(1) { FloatArray(LSTM_UNITS) }
        windowBuffer.clear()
        Log.d(TAG, "Session state reset (LSTM states zeroed, buffer cleared)")
    }

    fun clearBuffer() {
        resetSessionState()
    }

    private fun runInference(): FatigueResult? {
        val input = arrayOf(windowBuffer.map { it }.toTypedArray())
        return runInferenceOn(input)
    }

    private fun runInferenceOn(input: Array<Array<FloatArray>>): FatigueResult? {
        val interp = interpreter ?: return null
        val s = scaler ?: return null
        return try {
            // Build inputs array ordered by tensor index
            val inputs = arrayOfNulls<Any>(3)
            inputs[inputSeqIdx] = input
            inputs[inputStateHIdx] = stateH
            inputs[inputStateCIdx] = stateC

            val outputProbs = Array(1) { FloatArray(2) }
            val newStateH = Array(1) { FloatArray(LSTM_UNITS) }
            val newStateC = Array(1) { FloatArray(LSTM_UNITS) }

            val outputs: Map<Int, Any> = mapOf(
                outputProbsIdx to outputProbs,
                outputStateHIdx to newStateH,
                outputStateCIdx to newStateC
            )

            interp.runForMultipleInputsOutputs(inputs, outputs)

            // Persist LSTM state for next call
            stateH = newStateH
            stateC = newStateC

            val pLow = outputProbs[0][0]
            val pHigh = outputProbs[0][1]
            mapToFatigueResult(pLow, pHigh, s)
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed: ${e.message}", e)
            null
        }
    }

    private fun mapToFatigueResult(pLow: Float, pHigh: Float, s: ScalerParams): FatigueResult {
        val thresholds = s.fatigueThresholds
        val mildMax = thresholds["mild_max"] ?: 0.25f
        val moderateMax = thresholds["moderate_max"] ?: 0.50f
        val highMax = thresholds["high_max"] ?: 0.75f

        val levelIndex = when {
            pHigh < mildMax -> 0
            pHigh < moderateMax -> 1
            pHigh < highMax -> 2
            else -> 3
        }
        val level = s.fatigueLevelNames.getOrElse(levelIndex) { "Unknown" }
        val percentDisplay = (pHigh * 100).toInt().coerceIn(0, 100)

        return FatigueResult(pLow, pHigh, level, levelIndex, percentDisplay)
    }

    private fun loadScalerFromAssets(filename: String): ScalerParams {
        val json = context.assets.open(filename).bufferedReader().readText()
        return parseScalerJson(json)
    }

    private fun loadScalerFromFile(file: File): ScalerParams {
        val json = file.readText()
        return parseScalerJson(json)
    }

    private fun parseScalerJson(json: String): ScalerParams {
        val obj = JSONObject(json)
        val meanArr = obj.getJSONArray("mean")
        val stdArr = obj.getJSONArray("std")
        val namesArr = obj.getJSONArray("feature_names")
        val thresholdsObj = obj.getJSONObject("fatigue_thresholds")
        val levelsArr = obj.getJSONArray("fatigue_level_names")

        val mean = FloatArray(meanArr.length()) { meanArr.getDouble(it).toFloat() }
        val std = FloatArray(stdArr.length()) { stdArr.getDouble(it).toFloat() }
        val names = List(namesArr.length()) { namesArr.getString(it) }
        val thresholds = mutableMapOf<String, Float>()
        thresholdsObj.keys().forEach { key ->
            thresholds[key] = thresholdsObj.getDouble(key).toFloat()
        }
        val levels = List(levelsArr.length()) { levelsArr.getString(it) }

        return ScalerParams(mean, std, names, thresholds, levels)
    }

    private fun loadModelFromAssets(filename: String): MappedByteBuffer {
        val fd = context.assets.openFd(filename)
        val inputStream = FileInputStream(fd.fileDescriptor)
        val channel = inputStream.channel
        return channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    private fun loadModelFromFile(file: File): MappedByteBuffer {
        val inputStream = FileInputStream(file)
        val channel = inputStream.channel
        return channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
