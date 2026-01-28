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
    private val onDataCallback: (TrackerData) -> Unit
) {
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

        data class SpO2Data(
            val spO2: Int,
            val heartRate: Int,
            val status: Int,
            val timestamp: Long
        ) : TrackerData()

        data class HeartRateData(
            val heartRate: Int,
            val ibiList: List<Int>,
            val ibiStatusList: List<Int>,
            val status: Int,
            val timestamp: Long
        ) : TrackerData()

        data class ECGData(
            val ppgGreen: Int,
            val sequence: Int,
            val ecgMv: Float,
            val leadOff: Int,
            val maxThresholdMv: Float,
            val minThresholdMv: Float,
            val timestamp: Long
        ) : TrackerData()

        data class SkinTemperatureData(
            val status: Int,
            val objectTemperature: Float?,
            val ambientTemperature: Float?,
            val timestamp: Long
        ) : TrackerData()

        data class BIAData(
            val basalMetabolicRate: Float,
            val bodyFatMass: Float,
            val bodyFatRatio: Float,
            val fatFreeMass: Float,
            val skeletalMuscleMass: Float,
            val timestamp: Long
        ) : TrackerData()

        data class SweatLossData(
            val sweatLoss: Float,
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
     * Start SpO2 On-Demand tracker
     */
    fun startSpO2OnDemand(): Boolean {
        return try {
            val tracker = healthTrackingService?.getHealthTracker(HealthTrackerType.SPO2_ON_DEMAND)

            tracker?.setEventListener(object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(dataPoints: MutableList<DataPoint>) {
                    dataPoints.forEach { dp ->
                        val data = TrackerData.SpO2Data(
                            spO2 = dp.getValue(ValueKey.SpO2Set.SPO2) ?: 0,
                            heartRate = dp.getValue(ValueKey.SpO2Set.HEART_RATE) ?: 0,
                            status = dp.getValue(ValueKey.SpO2Set.STATUS) ?: 0,
                            timestamp = dp.timestamp
                        )
                        onDataCallback(data)
                    }
                }

                override fun onFlushCompleted() {
                    Log.d(TAG, "SpO2 On-Demand flush completed")
                }

                override fun onError(error: HealthTracker.TrackerError) {
                    Log.e(TAG, "SpO2 On-Demand error: ${error.name}")
                }
            })

            activeTrackers[HealthTrackerType.SPO2_ON_DEMAND] = tracker!!
            Log.d(TAG, "Started SpO2 On-Demand tracker")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SpO2 On-Demand: ${e.message}", e)
            false
        }
    }

    /**
     * Start Heart Rate Continuous tracker
     */
    fun startHeartRateContinuous(): Boolean {
        return try {
            val tracker = healthTrackingService?.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)

            tracker?.setEventListener(object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(dataPoints: MutableList<DataPoint>) {
                    dataPoints.forEach { dp ->
                        val data = TrackerData.HeartRateData(
                            heartRate = dp.getValue(ValueKey.HeartRateSet.HEART_RATE) ?: 0,
                            ibiList = dp.getValue(ValueKey.HeartRateSet.IBI_LIST) ?: emptyList(),
                            ibiStatusList = dp.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST) ?: emptyList(),
                            status = dp.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS) ?: 0,
                            timestamp = dp.timestamp
                        )
                        onDataCallback(data)
                    }
                }

                override fun onFlushCompleted() {
                    Log.d(TAG, "Heart Rate Continuous flush completed")
                }

                override fun onError(error: HealthTracker.TrackerError) {
                    Log.e(TAG, "Heart Rate Continuous error: ${error.name}")
                }
            })

            activeTrackers[HealthTrackerType.HEART_RATE_CONTINUOUS] = tracker!!
            Log.d(TAG, "Started Heart Rate Continuous tracker")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Heart Rate Continuous: ${e.message}", e)
            false
        }
    }

    /**
     * Start ECG On-Demand tracker
     */
    fun startECGOnDemand(): Boolean {
        return try {
            val tracker = healthTrackingService?.getHealthTracker(HealthTrackerType.ECG_ON_DEMAND)

            tracker?.setEventListener(object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(dataPoints: MutableList<DataPoint>) {
                    dataPoints.forEach { dp ->
                        val data = TrackerData.ECGData(
                            ppgGreen = dp.getValue(ValueKey.EcgSet.PPG_GREEN) ?: 0,
                            sequence = dp.getValue(ValueKey.EcgSet.SEQUENCE) ?: 0,
                            ecgMv = dp.getValue(ValueKey.EcgSet.ECG_MV) ?: 0f,
                            leadOff = dp.getValue(ValueKey.EcgSet.LEAD_OFF) ?: 0,
                            maxThresholdMv = dp.getValue(ValueKey.EcgSet.MAX_THRESHOLD_MV) ?: 0f,
                            minThresholdMv = dp.getValue(ValueKey.EcgSet.MIN_THRESHOLD_MV) ?: 0f,
                            timestamp = dp.timestamp
                        )
                        onDataCallback(data)
                    }
                }

                override fun onFlushCompleted() {
                    Log.d(TAG, "ECG On-Demand flush completed")
                }

                override fun onError(error: HealthTracker.TrackerError) {
                    Log.e(TAG, "ECG On-Demand error: ${error.name}")
                }
            })

            activeTrackers[HealthTrackerType.ECG_ON_DEMAND] = tracker!!
            Log.d(TAG, "Started ECG On-Demand tracker")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ECG On-Demand: ${e.message}", e)
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
     * Start BIA (Body Composition) tracker
     */
    fun startBIA(): Boolean {
        return try {
            val tracker = healthTrackingService?.getHealthTracker(HealthTrackerType.BIA)

            tracker?.setEventListener(object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(dataPoints: MutableList<DataPoint>) {
                    dataPoints.forEach { dp ->
                        val data = TrackerData.BIAData(
                            basalMetabolicRate = dp.getValue(ValueKey.BiaSet.BASAL_METABOLIC_RATE) ?: 0f,
                            bodyFatMass = dp.getValue(ValueKey.BiaSet.BODY_FAT_MASS) ?: 0f,
                            bodyFatRatio = dp.getValue(ValueKey.BiaSet.BODY_FAT_RATIO) ?: 0f,
                            fatFreeMass = dp.getValue(ValueKey.BiaSet.FAT_FREE_MASS) ?: 0f,
                            skeletalMuscleMass = dp.getValue(ValueKey.BiaSet.SKELETAL_MUSCLE_MASS) ?: 0f,
                            timestamp = dp.timestamp
                        )
                        onDataCallback(data)
                    }
                }

                override fun onFlushCompleted() {
                    Log.d(TAG, "BIA flush completed")
                }

                override fun onError(error: HealthTracker.TrackerError) {
                    Log.e(TAG, "BIA error: ${error.name}")
                }
            })

            activeTrackers[HealthTrackerType.BIA] = tracker!!
            Log.d(TAG, "Started BIA tracker")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BIA: ${e.message}", e)
            false
        }
    }

    /**
     * Start Sweat Loss tracker
     */
    fun startSweatLoss(): Boolean {
        return try {
            val tracker = healthTrackingService?.getHealthTracker(HealthTrackerType.SWEAT_LOSS)

            tracker?.setEventListener(object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(dataPoints: MutableList<DataPoint>) {
                    dataPoints.forEach { dp ->
                        val data = TrackerData.SweatLossData(
                            sweatLoss = dp.getValue(ValueKey.SweatLossSet.SWEAT_LOSS) ?: 0f,
                            timestamp = dp.timestamp
                        )
                        onDataCallback(data)
                    }
                }

                override fun onFlushCompleted() {
                    Log.d(TAG, "Sweat Loss flush completed")
                }

                override fun onError(error: HealthTracker.TrackerError) {
                    Log.e(TAG, "Sweat Loss error: ${error.name}")
                }
            })

            activeTrackers[HealthTrackerType.SWEAT_LOSS] = tracker!!
            Log.d(TAG, "Started Sweat Loss tracker")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Sweat Loss: ${e.message}", e)
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