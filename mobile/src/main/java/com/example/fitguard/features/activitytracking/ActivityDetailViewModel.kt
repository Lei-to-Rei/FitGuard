package com.example.fitguard.features.activitytracking

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.fitguard.data.repository.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ActivityDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val _routePoints = MutableLiveData<List<RoutePoint>>(emptyList())
    val routePoints: LiveData<List<RoutePoint>> = _routePoints

    private val _summary = MutableLiveData<RouteSummary>()
    val summary: LiveData<RouteSummary> = _summary

    private val _splits = MutableLiveData<List<SplitData>>(emptyList())
    val splits: LiveData<List<SplitData>> = _splits

    private val _hrData = MutableLiveData<List<HrDataPoint>>(emptyList())
    val hrData: LiveData<List<HrDataPoint>> = _hrData

    private val _avgHr = MutableLiveData(0f)
    val avgHr: LiveData<Float> = _avgHr

    private val _fatigueData = MutableLiveData<List<FatigueDataPoint>>(emptyList())
    val fatigueData: LiveData<List<FatigueDataPoint>> = _fatigueData

    private val _fatigueLevel = MutableLiveData("--")
    val fatigueLevel: LiveData<String> = _fatigueLevel

    private val _activityType = MutableLiveData("")
    val activityType: LiveData<String> = _activityType

    private val _startTimeMillis = MutableLiveData(0L)
    val startTimeMillis: LiveData<Long> = _startTimeMillis

    fun loadDetail(userId: String, sessionDirName: String, activityType: String, startTime: Long) {
        _activityType.value = activityType
        _startTimeMillis.value = startTime

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val route = SessionDetailRepository.loadRouteCsv(userId, sessionDirName)
                val sum = SessionDetailRepository.loadRouteSummary(userId, sessionDirName)
                val splits = SessionDetailRepository.computeSplits(route)
                val hr = SessionDetailRepository.loadHeartRateData(userId, sessionDirName)
                val fatigue = SessionDetailRepository.loadFatigueData(userId, sessionDirName)
                val fLevel = SessionDetailRepository.loadOverallFatigueLevel(userId, sessionDirName)
                val avg = if (hr.isNotEmpty()) hr.map { it.hrBpm }.average().toFloat() else 0f
                Result(route, sum, splits, hr, avg, fatigue, fLevel)
            }
            _routePoints.value = result.route
            _summary.value = result.summary
            _splits.value = result.splits
            _hrData.value = result.hr
            _avgHr.value = result.avgHr
            _fatigueData.value = result.fatigue
            _fatigueLevel.value = result.fatigueLevel
        }
    }

    private data class Result(
        val route: List<RoutePoint>,
        val summary: RouteSummary,
        val splits: List<SplitData>,
        val hr: List<HrDataPoint>,
        val avgHr: Float,
        val fatigue: List<FatigueDataPoint>,
        val fatigueLevel: String
    )
}
