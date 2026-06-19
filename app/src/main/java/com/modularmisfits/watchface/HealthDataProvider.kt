package com.modularmisfits.watchface

import android.content.Context
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.guava.await

private const val TAG = "MisfitHealth"
private const val REFRESH_INTERVAL_MS = 5 * 60 * 1000L

class HealthDataProvider(
    private val context: Context,
    private val onUpdate: (HealthSnapshot) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var refreshJob: Job? = null
    private var snapshot = HealthSnapshot()

    private val passiveCallback = object : PassiveListenerCallback {
        override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
            var changed = false

            dataPoints.getData(DataType.STEPS_DAILY).lastOrNull()?.let {
                val v = it.value.toInt()
                if (snapshot.steps != v) { snapshot = snapshot.copy(steps = v); changed = true }
                Log.d(TAG, "steps=$v")
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

                // Check which data types are available
                val capabilities = passiveClient.getCapabilitiesAsync().await()
                Log.i(TAG, "Passive capabilities: ${capabilities.supportedDataTypesPassiveMonitoring}")

                val requested = mutableSetOf<DataType<*, *>>()
                if (DataType.STEPS_DAILY in capabilities.supportedDataTypesPassiveMonitoring)
                    requested.add(DataType.STEPS_DAILY)
                if (DataType.CALORIES_DAILY in capabilities.supportedDataTypesPassiveMonitoring)
                    requested.add(DataType.CALORIES_DAILY)
                if (DataType.HEART_RATE_BPM in capabilities.supportedDataTypesPassiveMonitoring)
                    requested.add(DataType.HEART_RATE_BPM)

                if (requested.isEmpty()) {
                    Log.w(TAG, "No supported passive data types — using defaults")
                    onUpdate(snapshot)
                    return@launch
                }

                val config = PassiveListenerConfig.builder()
                    .setDataTypes(requested)
                    .build()

                passiveClient.setPassiveListenerCallback(config, passiveCallback)
                Log.i(TAG, "Health Services passive monitoring started for: $requested")

            } catch (e: Exception) {
                Log.e(TAG, "Health Services start failed: ${e.javaClass.simpleName}: ${e.message}")
                onUpdate(snapshot)
            }
        }
    }

    fun stop() {
        try {
            val passiveClient = HealthServices.getClient(context).passiveMonitoringClient
            passiveClient.clearPassiveListenerCallbackAsync()
        } catch (e: Exception) {
            Log.w(TAG, "stop: ${e.message}")
        }
        refreshJob?.cancel()
        scope.cancel()
    }
}
