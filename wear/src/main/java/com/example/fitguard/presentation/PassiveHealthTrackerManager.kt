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
 * Separate Health Tracker Manager for passive/metrics tracking.
 * Has its own HealthTrackingService connection, independent from the
 * sequence pipeline's HealthTrackerManager.
 *
 * Used by: MetricsMonitoring (HR, SpO2, SkinTemp), SleepStress (HR/IBI)
 * All 5 trackers available: PPG, Accelerometer, HR, SpO2, SkinTemp
 */
class PassiveHealthTrackerManager(
    private val context: Context,
    private val onDataCallback: (HealthTrackerManager.TrackerData) -> Unit
) {
    private var healthTrackingService: HealthTrackingService? = null
    private val activeTrackers = mutableMapOf<HealthTrackerType, HealthTracker>()

    companion object {
        private const val TAG = "PassiveTrackerManager"
    }

    fun initialize(onSuccess: () -> Unit, onError: (HealthTrackerException) -> Unit) {
        val connectionListener = object : com.samsung.android.service.health.tracking.ConnectionListener {
            override fun onConnectionSuccess() {
                Log.d(TAG, "Passive Health Tracking Service connected")
                onSuccess()
            }

            override fun onConnectionEnded() {
                Log.d(TAG, "Passive Health Tracking Service connection ended")
            }

            override fun onConnectionFailed(error: HealthTrackerException) {
                Log.e(TAG, "Passive connection failed: ${error.errorCode}")
                onError(error)
            }
        }

        healthTrackingService = HealthTrackingService(connectionListener, context)
        healthTrackingService?.connectService()
    }

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
                        onDataCallback(HealthTrackerManager.TrackerData.PPGData(
                            green = dp.getValue(ValueKey.PpgSet.PPG_GREEN),
                            ir = dp.getValue(ValueKey.PpgSet.PPG_IR),
                            red = dp.getValue(ValueKey.PpgSet.PPG_RED),
                            timestamp = dp.timestamp
                        ))
                    }
                }

                override fun onFlushCompleted() {
                    Log.d(TAG, "PPG flush completed")
                }

                override fun onError(error: HealthTracker.TrackerError) {
                    Log.e(TAG, "PPG error: ${error.name}")
                }
            })

            activeTrackers[HealthTrackerType.PPG_CONTINUOUS] = tracker!!
            Log.d(TAG, "Started PPG Continuous tracker")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start PPG: ${e.message}", e)
            false
        }
    }

    fun startAccelerometerContinuous(): Boolean {
        return try {
            val tracker = healthTrackingService?.getHealthTracker(HealthTrackerType.ACCELEROMETER_CONTINUOUS)

            tracker?.setEventListener(object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(dataPoints: MutableList<DataPoint>) {
                    dataPoints.forEach { dp ->
                        onDataCallback(HealthTrackerManager.TrackerData.AccelerometerData(
                            x = dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_X),
                            y = dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Y),
                            z = dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Z),
                            timestamp = dp.timestamp
                        ))
                    }
                }

                override fun onFlushCompleted() {
                    Log.d(TAG, "Accelerometer flush completed")
                }

                override fun onError(error: HealthTracker.TrackerError) {
                    Log.e(TAG, "Accelerometer error: ${error.name}")
                }
            })

            activeTrackers[HealthTrackerType.ACCELEROMETER_CONTINUOUS] = tracker!!
            Log.d(TAG, "Started Accelerometer Continuous tracker")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Accelerometer: ${e.message}", e)
            false
        }
    }

    fun startHeartRateContinuous(): Boolean {
        return try {
            val tracker = healthTrackingService?.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)

            tracker?.setEventListener(object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(dataPoints: MutableList<DataPoint>) {
                    dataPoints.forEach { dp ->
                        onDataCallback(HealthTrackerManager.TrackerData.HeartRateData(
                            heartRate = dp.getValue(ValueKey.HeartRateSet.HEART_RATE) ?: 0,
                            ibiList = dp.getValue(ValueKey.HeartRateSet.IBI_LIST) ?: emptyList(),
                            ibiStatusList = dp.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST) ?: emptyList(),
                            status = dp.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS) ?: 0,
                            timestamp = dp.timestamp
                        ))
                    }
                }

                override fun onFlushCompleted() {
                    Log.d(TAG, "Heart Rate flush completed")
                }

                override fun onError(error: HealthTracker.TrackerError) {
                    Log.e(TAG, "Heart Rate error: ${error.name}")
                }
            })

            activeTrackers[HealthTrackerType.HEART_RATE_CONTINUOUS] = tracker!!
            Log.d(TAG, "Started Heart Rate Continuous tracker")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Heart Rate: ${e.message}", e)
            false
        }
    }

    fun startSpO2OnDemand(): Boolean {
        return try {
            val tracker = healthTrackingService?.getHealthTracker(HealthTrackerType.SPO2_ON_DEMAND)

            tracker?.setEventListener(object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(dataPoints: MutableList<DataPoint>) {
                    dataPoints.forEach { dp ->
                        onDataCallback(HealthTrackerManager.TrackerData.SpO2Data(
                            spO2 = dp.getValue(ValueKey.SpO2Set.SPO2) ?: 0,
                            heartRate = dp.getValue(ValueKey.SpO2Set.HEART_RATE) ?: 0,
                            status = dp.getValue(ValueKey.SpO2Set.STATUS) ?: 0,
                            timestamp = dp.timestamp
                        ))
                    }
                }

                override fun onFlushCompleted() {
                    Log.d(TAG, "SpO2 flush completed")
                }

                override fun onError(error: HealthTracker.TrackerError) {
                    Log.e(TAG, "SpO2 error: ${error.name}")
                }
            })

            activeTrackers[HealthTrackerType.SPO2_ON_DEMAND] = tracker!!
            Log.d(TAG, "Started SpO2 On-Demand tracker")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SpO2: ${e.message}", e)
            false
        }
    }

    fun startSkinTemperatureOnDemand(): Boolean {
        return try {
            val tracker = healthTrackingService?.getHealthTracker(HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND)

            tracker?.setEventListener(object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(dataPoints: MutableList<DataPoint>) {
                    dataPoints.forEach { dp ->
                        onDataCallback(HealthTrackerManager.TrackerData.SkinTemperatureData(
                            status = dp.getValue(ValueKey.SkinTemperatureSet.STATUS) ?: 0,
                            objectTemperature = dp.getValue(ValueKey.SkinTemperatureSet.OBJECT_TEMPERATURE),
                            ambientTemperature = dp.getValue(ValueKey.SkinTemperatureSet.AMBIENT_TEMPERATURE),
                            timestamp = dp.timestamp
                        ))
                    }
                }

                override fun onFlushCompleted() {
                    Log.d(TAG, "Skin Temperature flush completed")
                }

                override fun onError(error: HealthTracker.TrackerError) {
                    Log.e(TAG, "Skin Temperature error: ${error.name}")
                }
            })

            activeTrackers[HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND] = tracker!!
            Log.d(TAG, "Started Skin Temperature On-Demand tracker")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Skin Temperature: ${e.message}", e)
            false
        }
    }

    fun stopTracker(type: HealthTrackerType) {
        activeTrackers[type]?.let { tracker ->
            tracker.unsetEventListener()
            activeTrackers.remove(type)
            Log.d(TAG, "Stopped tracker: ${type.name}")
        }
    }

    fun stopAllTrackers() {
        activeTrackers.keys.toList().forEach { type ->
            stopTracker(type)
        }
    }

    fun disconnect() {
        stopAllTrackers()
        healthTrackingService?.disconnectService()
        Log.d(TAG, "Passive Health Tracking Service disconnected")
    }
}
