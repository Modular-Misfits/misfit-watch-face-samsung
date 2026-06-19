package com.modularmisfits.watchface

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.*
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private const val TAG = "MisfitHealth"
private const val REFRESH_INTERVAL_MS = 10 * 60 * 1000L

class HealthDataProvider(
    private val context: Context,
    private val onUpdate: (HealthSnapshot) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var refreshJob: Job? = null

    fun start() {
        scope.launch {
            try {
                val status = HealthConnectClient.getSdkStatus(context)
                Log.i(TAG, "Health Connect SDK status=$status")
                // On Wear OS the provider is wearable.healthservices — status may not be SDK_AVAILABLE
                // but getOrCreate still works. Only bail on SDK_UNAVAILABLE (2).
                if (status == HealthConnectClient.SDK_UNAVAILABLE) {
                    Log.w(TAG, "Health Connect unavailable on this device")
                    onUpdate(HealthSnapshot())
                    return@launch
                }
                val client = HealthConnectClient.getOrCreate(context)
                val granted = client.permissionController.getGrantedPermissions()
                if (granted.containsAll(HealthPermissionActivity.PERMISSIONS)) {
                    scheduleRefresh(client)
                } else {
                    Log.w(TAG, "Missing Health Connect permissions — launching permission activity")
                    val intent = Intent(context, HealthPermissionActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    onUpdate(HealthSnapshot())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Health Connect error: ${e.message}")
                onUpdate(HealthSnapshot())
            }
        }
    }

    fun stop() {
        refreshJob?.cancel()
        scope.cancel()
    }

    private fun scheduleRefresh(client: HealthConnectClient) {
        refreshJob = scope.launch {
            while (isActive) {
                fetchAndEmit(client)
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    private suspend fun fetchAndEmit(client: HealthConnectClient) {
        val zone = ZoneId.systemDefault()
        val todayStart = LocalDate.now().atStartOfDay(zone).toInstant()
        val now = Instant.now()
        val todayRange = TimeRangeFilter.between(todayStart, now)

        // Yesterday 6pm → today noon for sleep
        val yesterdayEvening = LocalDate.now().minusDays(1).atTime(18, 0).atZone(zone).toInstant()
        val todayNoon = LocalDate.now().atTime(12, 0).atZone(zone).toInstant()
        val sleepRange = TimeRangeFilter.between(yesterdayEvening, todayNoon)

        val steps = fetchSteps(client, todayRange)
        val heartRate = fetchHeartRate(client, todayRange)
        val calories = fetchCalories(client, todayRange)
        val activeMinutes = fetchActiveMinutes(client, todayRange)
        val sleepScore = fetchSleepScore(client, sleepRange)

        onUpdate(HealthSnapshot(
            steps = steps,
            heartRate = heartRate,
            calories = calories,
            activeMinutes = activeMinutes,
            sleepScore = sleepScore,
            energyScore = 50 // Health Connect has no energy score equivalent
        ))
    }

    private suspend fun fetchSteps(client: HealthConnectClient, range: TimeRangeFilter): Int {
        return try {
            val response = client.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = range
                )
            )
            response[StepsRecord.COUNT_TOTAL]?.toInt() ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "fetchSteps: ${e.message}"); 0
        }
    }

    private suspend fun fetchHeartRate(client: HealthConnectClient, range: TimeRangeFilter): Int {
        return try {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = range,
                    ascendingOrder = false,
                    pageSize = 1
                )
            )
            response.records.firstOrNull()?.samples?.lastOrNull()?.beatsPerMinute?.toInt() ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "fetchHeartRate: ${e.message}"); 0
        }
    }

    private suspend fun fetchCalories(client: HealthConnectClient, range: TimeRangeFilter): Int {
        return try {
            val response = client.aggregate(
                AggregateRequest(
                    metrics = setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
                    timeRangeFilter = range
                )
            )
            response[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories?.toInt() ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "fetchCalories: ${e.message}"); 0
        }
    }

    private suspend fun fetchActiveMinutes(client: HealthConnectClient, range: TimeRangeFilter): Int {
        return try {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = ExerciseSessionRecord::class,
                    timeRangeFilter = range
                )
            )
            response.records.sumOf {
                Duration.between(it.startTime, it.endTime).toMinutes()
            }.toInt()
        } catch (e: Exception) {
            Log.e(TAG, "fetchActiveMinutes: ${e.message}"); 0
        }
    }

    private suspend fun fetchSleepScore(client: HealthConnectClient, range: TimeRangeFilter): Int {
        return try {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = range,
                    ascendingOrder = false,
                    pageSize = 1
                )
            )
            val session = response.records.firstOrNull() ?: return 0
            // Derive a 0-100 score from sleep duration (7-9h = 100, scaled)
            val hours = Duration.between(session.startTime, session.endTime).toMinutes() / 60.0
            (((hours.coerceIn(0.0, 9.0) / 9.0) * 100).toInt()).coerceIn(0, 100)
        } catch (e: Exception) {
            Log.e(TAG, "fetchSleepScore: ${e.message}"); 0
        }
    }
}
