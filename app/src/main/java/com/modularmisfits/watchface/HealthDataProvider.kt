package com.modularmisfits.watchface

import android.content.Context
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.guava.await

private const val TAG = "MisfitHealth"

const val STEP_GOAL = 10_000
const val ACTIVE_MIN_GOAL = 60

class HealthDataProvider(
    private val context: Context,
    private val onUpdate: (HealthSnapshot) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var snapshot = HealthSnapshot()

    // Rolling step-burst tracker for active minutes.
    // We receive STEPS (interval) deltas. Each delta covers a time window;
    // if steps/min >= 60 we count that window as active.
    private var lastStepBurstMs = 0L
    private var lastStepBurstCount = 0

    private val passiveCallback = object : PassiveListenerCallback {
        override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
            var changed = false

            dataPoints.getData(DataType.STEPS_DAILY).lastOrNull()?.let {
                val v = it.value.toInt()
                if (snapshot.steps != v) { snapshot = snapshot.copy(steps = v); changed = true }
                Log.d(TAG, "steps_daily=$v")
            }

            // Derive active minutes from STEPS interval bursts (cadence ≥ 60 steps/min = active)
            val stepBursts = dataPoints.getData(DataType.STEPS)
            for (burst in stepBursts) {
                val durationMs = burst.endDurationFromBoot.toMillis() - burst.startDurationFromBoot.toMillis()
                if (durationMs <= 0) continue
                val cadence = burst.value * 60_000.0 / durationMs
                if (cadence >= 60) {
                    val addedMinutes = (durationMs / 60_000.0).toInt().coerceAtLeast(1)
                    val newActive = snapshot.activeMinutes + addedMinutes
                    snapshot = snapshot.copy(activeMinutes = newActive)
                    changed = true
                    Log.d(TAG, "active burst cadence=${"%.0f".format(cadence)} → +${addedMinutes}min total=${snapshot.activeMinutes}")
                }
            }

            dataPoints.getData(DataType.CALORIES_DAILY).lastOrNull()?.let {
                val v = it.value.toInt()
                if (snapshot.calories != v) { snapshot = snapshot.copy(calories = v); changed = true }
                Log.d(TAG, "calories=$v")
            }
            dataPoints.getData(DataType.HEART_RATE_BPM).lastOrNull()?.let {
                val v = it.value.toInt()
                if (snapshot.heartRate != v) { snapshot = snapshot.copy(heartRate = v); changed = true }
                Log.d(TAG, "hr=$v")
            }

            if (changed) onUpdate(snapshot)
        }

        override fun onRegistered() { Log.i(TAG, "Passive listener registered") }
        override fun onRegistrationFailed(throwable: Throwable) {
            Log.e(TAG, "Passive listener registration failed: ${throwable.message}")
        }
    }

    fun start() {
        scope.launch {
            try {
                val client = HealthServices.getClient(context)
                val passiveClient = client.passiveMonitoringClient
                val caps = passiveClient.getCapabilitiesAsync().await()
                Log.i(TAG, "Passive caps: ${caps.supportedDataTypesPassiveMonitoring}")

                val requested = mutableSetOf<DataType<*, *>>()
                if (DataType.STEPS_DAILY in caps.supportedDataTypesPassiveMonitoring)
                    requested.add(DataType.STEPS_DAILY)
                // STEPS (interval) for active-minutes cadence tracking
                if (DataType.STEPS in caps.supportedDataTypesPassiveMonitoring)
                    requested.add(DataType.STEPS)
                if (DataType.CALORIES_DAILY in caps.supportedDataTypesPassiveMonitoring)
                    requested.add(DataType.CALORIES_DAILY)
                if (DataType.HEART_RATE_BPM in caps.supportedDataTypesPassiveMonitoring)
                    requested.add(DataType.HEART_RATE_BPM)

                if (requested.isNotEmpty()) {
                    val config = PassiveListenerConfig.builder()
                        .setDataTypes(requested)
                        .build()
                    passiveClient.setPassiveListenerCallback(config, passiveCallback)
                    Log.i(TAG, "Passive monitoring started: $requested")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Health Services start failed: ${e.javaClass.simpleName}: ${e.message}")
                onUpdate(snapshot)
            }
        }
    }

    fun stop() {
        scope.launch {
            try {
                HealthServices.getClient(context).passiveMonitoringClient
                    .clearPassiveListenerCallbackAsync()
            } catch (e: Exception) {
                Log.w(TAG, "stop: ${e.message}")
            }
        }
        scope.cancel()
    }
}
