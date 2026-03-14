package com.example.fitguard.features.profile

import android.app.Application
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

data class WatchDevice(
    val nodeId: String,
    val displayName: String,
    val isConnected: Boolean,
    val batteryPercent: Int? = null
)

class ConnectedDevicesViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ConnectedDevicesVM"
        private const val POLL_INTERVAL_MS = 10_000L
        private const val PREFS_NAME = "device_sync"
        private const val KEY_LAST_SYNC = "last_sync_time"
    }

    private val _phoneName = MutableLiveData(Build.MODEL)
    val phoneName: LiveData<String> = _phoneName

    private val _phoneBattery = MutableLiveData<Int>()
    val phoneBattery: LiveData<Int> = _phoneBattery

    private val _watchDevices = MutableLiveData<List<WatchDevice>>(emptyList())
    val watchDevices: LiveData<List<WatchDevice>> = _watchDevices

    private val _lastSyncTime = MutableLiveData<Long>(0L)
    val lastSyncTime: LiveData<Long> = _lastSyncTime

    private var pollJob: Job? = null

    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                refreshPhoneBattery()
                refreshNodes()
                refreshLastSync()
                requestWatchBattery()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun refreshPhoneBattery() {
        val bm = getApplication<Application>().getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level >= 0) _phoneBattery.postValue(level)
    }

    private suspend fun refreshNodes() {
        try {
            val nodes = Wearable.getNodeClient(getApplication<Application>()).connectedNodes.await()
            val currentList = _watchDevices.value ?: emptyList()
            val updated = nodes.map { node ->
                val existing = currentList.find { it.nodeId == node.id }
                WatchDevice(
                    nodeId = node.id,
                    displayName = node.displayName,
                    isConnected = true,
                    batteryPercent = existing?.batteryPercent
                )
            }
            _watchDevices.postValue(updated)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get connected nodes: ${e.message}", e)
            _watchDevices.postValue(emptyList())
        }
    }

    private fun refreshLastSync() {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _lastSyncTime.postValue(prefs.getLong(KEY_LAST_SYNC, 0L))
    }

    private suspend fun requestWatchBattery() {
        val devices = _watchDevices.value ?: return
        for (device in devices) {
            try {
                Wearable.getMessageClient(getApplication<Application>())
                    .sendMessage(device.nodeId, "/fitguard/device/battery_request", byteArrayOf())
                    .await()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request battery from ${device.displayName}: ${e.message}")
            }
        }
    }

    fun onWatchBatteryResponse(nodeId: String, batteryPercent: Int) {
        val currentList = _watchDevices.value ?: return
        val updated = currentList.map { device ->
            if (device.nodeId == nodeId) device.copy(batteryPercent = batteryPercent)
            else device
        }
        _watchDevices.value = updated
    }
}
