package com.example.fitguard.presentation

import android.content.Context
import android.os.Handler
import android.os.Looper
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

    // Cache HR tracker — SDK docs: "Only one TrackerEventListener should be set per HealthTrackerType"
    private var cachedHrTracker: HealthTracker? = null

    private var spo2RetryCount = 0
    private var hrRetryCount = 0
    private var hrDataReceived = false
    private var hrWatchdogHandler: Handler? = null
    private var hrWatchdogRunnable: Runnable? = null

    companion object {
        private const val TAG = "PassiveTrackerManager"
        private const val MAX_SPO2_RETRIES = 3
        private const val SPO2_RETRY_DELAY_MS = 2000L
        private const val MAX_HR_RETRIES = 3
        private const val HR_RETRY_DELAY_MS = 3000L
        private const val HR_WATCHDOG_TIMEOUT_MS = 10_000L
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
            hrDataReceived = false
            val tracker = cachedHrTracker
                ?: healthTrackingService?.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
            cachedHrTracker = tracker

            tracker?.setEventListener(object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(dataPoints: MutableList<DataPoint>) {
                    dataPoints.forEach { dp ->
                        val status = dp.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS) ?: -1
                        val heartRate = dp.getValue(ValueKey.HeartRateSet.HEART_RATE) ?: 0
                        Log.d(TAG, "HR data: status=$status hr=$heartRate")
                        if (status == 1 && heartRate > 0) {
                            hrDataReceived = true
                            cancelHrWatchdog()
                            hrRetryCount = 0
                        }
                        onDataCallback(HealthTrackerManager.TrackerData.HeartRateData(
                            heartRate = heartRate,
                            ibiList = dp.getValue(ValueKey.HeartRateSet.IBI_LIST) ?: emptyList(),
                            ibiStatusList = dp.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST) ?: emptyList(),
                            status = status,
                            timestamp = dp.timestamp
                        ))
                    }
                }

                override fun onFlushCompleted() {
                    Log.d(TAG, "Heart Rate flush completed")
                }

                override fun onError(error: HealthTracker.TrackerError) {
                    Log.e(TAG, "Heart Rate error: ${error.name}")
                    retryHeartRate()
                }
            })

            activeTrackers[HealthTrackerType.HEART_RATE_CONTINUOUS] = tracker!!
            Log.d(TAG, "Started Heart Rate Continuous tracker")
            startHrWatchdog()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Heart Rate: ${e.message}", e)
            cachedHrTracker = null
            false
        }
    }

    fun startSpO2OnDemand(): Boolean {
        return try {
            val tracker = healthTrackingService?.getHealthTracker(HealthTrackerType.SPO2_ON_DEMAND)

            tracker?.setEventListener(object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(dataPoints: MutableList<DataPoint>) {
                    dataPoints.forEach { dp ->
                        val status = dp.getValue(ValueKey.SpO2Set.STATUS) ?: -1
                        val spo2 = dp.getValue(ValueKey.SpO2Set.SPO2) ?: 0
                        val heartRate = dp.getValue(ValueKey.SpO2Set.HEART_RATE) ?: 0
                        // Status 2 = completed, 0 = calculating, negative = error
                        when {
                            status == 2 && spo2 > 0 -> {
                                spo2RetryCount = 0
                                onDataCallback(HealthTrackerManager.TrackerData.SpO2Data(
                                    spO2 = spo2,
                                    heartRate = heartRate,
                                    status = status,
                                    timestamp = dp.timestamp
                                ))
                            }
                            status == 2 && spo2 == 0 -> {
                                Log.w(TAG, "SpO2 completed with spo2=0, retrying")
                                retrySpO2()
                            }
                            status < 0 -> {
                                Log.w(TAG, "SpO2 error: status=$status, retrying")
                                retrySpO2()
                            }
                            else -> {
                                Log.d(TAG, "SpO2 in progress: status=$status spo2=$spo2")
                            }
                        }
                    }
                }

                override fun onFlushCompleted() {
                    Log.d(TAG, "SpO2 flush completed")
                }

                override fun onError(error: HealthTracker.TrackerError) {
                    Log.e(TAG, "SpO2 error: ${error.name}")
                    retrySpO2()
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

    private fun retrySpO2() {
        if (spo2RetryCount >= MAX_SPO2_RETRIES) {
            Log.w(TAG, "SpO2 max retries reached ($MAX_SPO2_RETRIES)")
            spo2RetryCount = 0
            onDataCallback(HealthTrackerManager.TrackerData.SpO2Data(
                spO2 = 0, heartRate = 0, status = -1, timestamp = System.currentTimeMillis()
            ))
            return
        }
        spo2RetryCount++
        Log.d(TAG, "SpO2 retry $spo2RetryCount/$MAX_SPO2_RETRIES")
        stopTracker(HealthTrackerType.SPO2_ON_DEMAND)
        Handler(Looper.getMainLooper()).postDelayed({ startSpO2OnDemand() }, SPO2_RETRY_DELAY_MS)
    }

    private fun retryHeartRate() {
        cancelHrWatchdog()
        if (hrRetryCount >= MAX_HR_RETRIES) {
            Log.w(TAG, "HR max retries reached ($MAX_HR_RETRIES)")
            hrRetryCount = 0
            onDataCallback(HealthTrackerManager.TrackerData.HeartRateData(
                heartRate = 0, ibiList = emptyList(), ibiStatusList = emptyList(),
                status = -1, timestamp = System.currentTimeMillis()
            ))
            return
        }
        hrRetryCount++
        Log.d(TAG, "HR retry $hrRetryCount/$MAX_HR_RETRIES")
        stopTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
        Handler(Looper.getMainLooper()).postDelayed({ startHeartRateContinuous() }, HR_RETRY_DELAY_MS)
    }

    private fun startHrWatchdog() {
        cancelHrWatchdog()
        val handler = Handler(Looper.getMainLooper())
        val runnable = Runnable {
            if (!hrDataReceived) {
                Log.w(TAG, "HR watchdog: no valid data in ${HR_WATCHDOG_TIMEOUT_MS}ms, retrying")
                retryHeartRate()
            }
        }
        hrWatchdogHandler = handler
        hrWatchdogRunnable = runnable
        handler.postDelayed(runnable, HR_WATCHDOG_TIMEOUT_MS)
    }

    private fun cancelHrWatchdog() {
        hrWatchdogRunnable?.let { hrWatchdogHandler?.removeCallbacks(it) }
        hrWatchdogRunnable = null
        hrWatchdogHandler = null
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
        if (type == HealthTrackerType.HEART_RATE_CONTINUOUS) {
            cancelHrWatchdog()
        }
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
        cancelHrWatchdog()
        stopAllTrackers()
        cachedHrTracker = null
        healthTrackingService?.disconnectService()
        Log.d(TAG, "Passive Health Tracking Service disconnected")
    }
}
