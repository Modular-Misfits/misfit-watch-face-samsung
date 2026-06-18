package com.modularmisfits.watchface

import android.content.Context
import android.util.Log
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.HealthDataStore
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.DataType
import com.samsung.android.sdk.health.data.request.DataTypes
import com.samsung.android.sdk.health.data.request.LocalDateFilter
import com.samsung.android.sdk.health.data.request.LocalTimeFilter
import com.samsung.android.sdk.health.data.request.Ordering
import kotlinx.coroutines.*
import java.time.LocalDate
import java.time.LocalDateTime

private const val TAG = "MisfitHealth"
private const val REFRESH_INTERVAL_MS = 10 * 60 * 1000L

class HealthDataProvider(
    private val context: Context,
    private val onUpdate: (HealthSnapshot) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var store: HealthDataStore? = null
    private var refreshJob: Job? = null

    private val requiredPermissions = setOf(
        Permission.of(DataTypes.STEPS, AccessType.READ),
        Permission.of(DataTypes.ACTIVITY_SUMMARY, AccessType.READ),
        Permission.of(DataTypes.HEART_RATE, AccessType.READ),
        Permission.of(DataTypes.ENERGY_SCORE, AccessType.READ)
    )

    fun start() {
        scope.launch {
            try {
                store = HealthDataService.getStore(context)
                val granted = store!!.getGrantedPermissions(requiredPermissions)
                if (granted.containsAll(requiredPermissions)) {
                    scheduleRefresh()
                } else {
                    Log.w(TAG, "Missing Samsung Health permissions — showing placeholder data")
                    onUpdate(HealthSnapshot())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Samsung Health not available: ${e.message}")
                onUpdate(HealthSnapshot())
            }
        }
    }

    fun stop() {
        refreshJob?.cancel()
        scope.cancel()
    }

    private fun scheduleRefresh() {
        refreshJob = scope.launch {
            while (isActive) {
                fetchAndEmit()
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    private suspend fun fetchAndEmit() {
        val s = store ?: return
        val today = LocalDate.now()
        val localTimeFilter = LocalTimeFilter.of(today.atStartOfDay(), LocalDateTime.now())
        val localDateFilter = LocalDateFilter.of(today, today)

        val steps = fetchSteps(s, localTimeFilter)
        val activeMinutes = fetchActiveMinutes(s, localTimeFilter)
        val heartRate = fetchHeartRate(s, localTimeFilter)
        val energyScore = fetchEnergyScore(s, localDateFilter)

        onUpdate(HealthSnapshot(
            steps = steps,
            activeMinutes = activeMinutes,
            heartRate = heartRate,
            energyScore = energyScore
        ))
    }

    private suspend fun fetchSteps(s: HealthDataStore, filter: LocalTimeFilter): Int {
        return try {
            val request = DataType.StepsType.TOTAL
                .requestBuilder
                .setLocalTimeFilter(filter)
                .build()
            val response = s.aggregateData(request)
            response.dataList.firstOrNull()?.value?.toLong()?.toInt() ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "fetchSteps: ${e.message}"); 0
        }
    }

    private suspend fun fetchActiveMinutes(s: HealthDataStore, filter: LocalTimeFilter): Int {
        return try {
            val request = DataType.ActivitySummaryType.TOTAL_ACTIVE_TIME
                .requestBuilder
                .setLocalTimeFilter(filter)
                .build()
            val response = s.aggregateData(request)
            response.dataList.firstOrNull()?.value?.toMinutes()?.toInt() ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "fetchActiveMinutes: ${e.message}"); 0
        }
    }

    // HeartRateType.readDataRequestBuilder returns DualTimeBuilder which only accepts LocalTimeFilter
    private suspend fun fetchHeartRate(s: HealthDataStore, filter: LocalTimeFilter): Int {
        return try {
            val request = DataTypes.HEART_RATE
                .readDataRequestBuilder
                .setLocalTimeFilter(filter)
                .setOrdering(Ordering.DESC)
                .setLimit(1)
                .build()
            val response = s.readData(request)
            val point = response.dataList.firstOrNull()
            point?.getValue(DataType.HeartRateType.HEART_RATE)?.toInt() ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "fetchHeartRate: ${e.message}"); 0
        }
    }

    // EnergyScoreType.readDataRequestBuilder returns LocalDateBuilder which accepts LocalDateFilter
    private suspend fun fetchEnergyScore(s: HealthDataStore, filter: LocalDateFilter): Int {
        return try {
            val request = DataTypes.ENERGY_SCORE
                .readDataRequestBuilder
                .setLocalDateFilter(filter)
                .setOrdering(Ordering.DESC)
                .setLimit(1)
                .build()
            val response = s.readData(request)
            val point = response.dataList.firstOrNull()
            point?.getValue(DataType.EnergyScoreType.ENERGY_SCORE)?.toInt() ?: 50
        } catch (e: Exception) {
            Log.e(TAG, "fetchEnergyScore: ${e.message}"); 50
        }
    }
}
