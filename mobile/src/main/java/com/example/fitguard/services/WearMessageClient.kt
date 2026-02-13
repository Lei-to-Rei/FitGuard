package com.example.fitguard.services

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

class WearMessageClient(private val context: Context) {

    companion object {
        private const val TAG = "WearMessageClient"
        private const val PATH_START = "/sleep_stress/start"
        private const val PATH_STOP = "/sleep_stress/stop"
    }

    sealed class Result {
        object Success : Result()
        object NoWatch : Result()
        data class Error(val msg: String) : Result()
    }

    suspend fun sendStart(): Result {
        return sendMessage(PATH_START)
    }

    suspend fun sendStop(): Result {
        return sendMessage(PATH_STOP)
    }

    private suspend fun sendMessage(path: String): Result {
        return try {
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            if (nodes.isEmpty()) {
                Log.w(TAG, "No connected watch found")
                return Result.NoWatch
            }

            val node = nodes.first()
            Log.d(TAG, "Sending $path to ${node.displayName} (${node.id})")
            Wearable.getMessageClient(context)
                .sendMessage(node.id, path, ByteArray(0))
                .await()
            Log.d(TAG, "Message sent successfully: $path")
            Result.Success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message $path: ${e.message}", e)
            Result.Error(e.message ?: "Unknown error")
        }
    }
}
