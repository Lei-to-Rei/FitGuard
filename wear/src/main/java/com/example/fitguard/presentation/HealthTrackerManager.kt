package com.example.fitguard.presentation

import android.content.Context
import android.util.Log
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey

/**
 * Health Tracker Manager for Samsung Watch 7
 * Samsung Health Sensor API 1.4.1 - All Available Trackers
 *
 * Working Trackers: PPG, Heart Rate, SpO2, ECG, Skin Temp, BIA, Sweat Loss
 */
class HealthTrackerManager(
    private val context: Context,
    private val defaultDataCallback: (TrackerData) -> Unit
) {
    var onDataCallback: (TrackerData) -> Unit = defaultDataCallback

    fun restoreDefaultCallback() {
        onDataCallback = defaultDataCallback
    }
    private var healthTrackingService: HealthTrackingService? = null
    private val activeTrackers = mutableMapOf<HealthTrackerType, HealthTracker>()

    companion object {
        private const val TAG = "HealthTrackerManager"
    }

    /**
     * Data class to hold tracker data
     */
    sealed class TrackerData {
        data class PPGData(
            val green: Int?,
            val ir: Int?,
            val red: Int?,
            val timestamp: Long
        ) : TrackerData()

        data class SkinTemperatureData(
            val status: Int,
            val objectTemperature: Float?,
            val ambientTemperature: Float?,
            val timestamp: Long
        ) : TrackerData()

        data class AccelerometerData(
            val x: Int?,
            val y: Int?,
            val z: Int?,
            val timestamp: Long
        ) : TrackerData()
    }

    /**
     * Initialize health tracking service
     */
    fun initialize(onSuccess: () -> Unit, onError: (HealthTrackerException) -> Unit) {
        val connectionListener = object : com.samsung.android.service.health.tracking.ConnectionListener {
            override fun onConnectionSuccess() {
                Log.d(TAG, "Health Tracking Service connected")
                onSuccess()
            }

            override fun onConnectionEnded() {
                Log.d(TAG, "Health Tracking Service connection ended")
            }

            override fun onConnectionFailed(error: HealthTrackerException) {
                Log.e(TAG, "Connection failed: ${error.errorCode}")
                onError(error)
            }
        }

        healthTrackingService = HealthTrackingService(connectionListener, context)
        healthTrackingService?.connectService()
    }

    /**
     * Get available tracker types for this device
     */
    fun getAvailableTrackers(): List<HealthTrackerType> {
        return healthTrackingService?.trackingCapability?.supportHealthTrackerTypes ?: emptyList()
    }

    /**
     * Start PPG Continuous tracker
     */
    fun startPPGContinuous(): Boolean {
        return try {
            val ppgTypes = setOf(
                com.samsung.android.service.health.tracking.data.PpgType.GREEN,
                com.samsung.android.service.health.tracking.data.PpgType.IR,
                com.samsung.android.service.health.tracking.data.PpgType.RED
            )

            val tracker = healthTrackingService?.getHealthTracker(
                HealthTrackerType.PPG_CONTINUOUS,
                ppgTypes
            )

            tracker?.setEventListener(object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(dataPoints: MutableList<DataPoint>) {
                    dataPoints.forEach { dp ->
                        val data = TrackerData.PPGData(
                            green = dp.getValue(ValueKey.PpgSet.PPG_GREEN),
                            ir = dp.getValue(ValueKey.PpgSet.PPG_IR),
                            red = dp.getValue(ValueKey.PpgSet.PPG_RED),
                            timestamp = dp.timestamp
                        )
                        onDataCallback(data)
                    }
                }

                override fun onFlushCompleted() {
                    Log.d(TAG, "PPG Continuous flush completed")
                }

                override fun onError(error: HealthTracker.TrackerError) {
                    Log.e(TAG, "PPG Continuous error: ${error.name}")
                }
            })

            activeTrackers[HealthTrackerType.PPG_CONTINUOUS] = tracker!!
            Log.d(TAG, "Started PPG Continuous tracker")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start PPG Continuous: ${e.message}", e)
            false
        }
    }

    /**
     * Start Skin Temperature On-Demand tracker
     */
    fun startSkinTemperatureOnDemand(): Boolean {
        return try {
            val tracker = healthTrackingService?.getHealthTracker(HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND)

            tracker?.setEventListener(object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(dataPoints: MutableList<DataPoint>) {
                    dataPoints.forEach { dp ->
                        val data = TrackerData.SkinTemperatureData(
                            status = dp.getValue(ValueKey.SkinTemperatureSet.STATUS) ?: 0,
                            objectTemperature = dp.getValue(ValueKey.SkinTemperatureSet.OBJECT_TEMPERATURE),
                            ambientTemperature = dp.getValue(ValueKey.SkinTemperatureSet.AMBIENT_TEMPERATURE),
                            timestamp = dp.timestamp
                        )
                        onDataCallback(data)
                    }
                }

                override fun onFlushCompleted() {
                    Log.d(TAG, "Skin Temperature On-Demand flush completed")
                }

                override fun onError(error: HealthTracker.TrackerError) {
                    Log.e(TAG, "Skin Temperature On-Demand error: ${error.name}")
                }
            })

            activeTrackers[HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND] = tracker!!
            Log.d(TAG, "Started Skin Temperature On-Demand tracker")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Skin Temperature On-Demand: ${e.message}", e)
            false
        }
    }

    /**
     * Start Accelerometer Continuous tracker
     */
    fun startAccelerometerContinuous(): Boolean {
        return try {
            val tracker = healthTrackingService?.getHealthTracker(HealthTrackerType.ACCELEROMETER_CONTINUOUS)

            tracker?.setEventListener(object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(dataPoints: MutableList<DataPoint>) {
                    dataPoints.forEach { dp ->
                        val data = TrackerData.AccelerometerData(
                            x = dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_X),
                            y = dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Y),
                            z = dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Z),
                            timestamp = dp.timestamp
                        )
                        onDataCallback(data)
                    }
                }

                override fun onFlushCompleted() {
                    Log.d(TAG, "Accelerometer Continuous flush completed")
                }

                override fun onError(error: HealthTracker.TrackerError) {
                    Log.e(TAG, "Accelerometer Continuous error: ${error.name}")
                }
            })

            activeTrackers[HealthTrackerType.ACCELEROMETER_CONTINUOUS] = tracker!!
            Log.d(TAG, "Started Accelerometer Continuous tracker")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Accelerometer Continuous: ${e.message}", e)
            false
        }
    }

    /**
     * Stop a specific tracker
     */
    fun stopTracker(type: HealthTrackerType) {
        activeTrackers[type]?.let { tracker ->
            tracker.unsetEventListener()
            activeTrackers.remove(type)
            Log.d(TAG, "Stopped tracker: ${type.name}")
        }
    }

    /**
     * Stop all trackers
     */
    fun stopAllTrackers() {
        activeTrackers.keys.toList().forEach { type ->
            stopTracker(type)
        }
    }

    /**
     * Disconnect service
     */
    fun disconnect() {
        stopAllTrackers()
        healthTrackingService?.disconnectService()
        Log.d(TAG, "Health Tracking Service disconnected")
    }
}