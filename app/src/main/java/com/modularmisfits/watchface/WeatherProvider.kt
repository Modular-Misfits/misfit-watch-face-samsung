package com.modularmisfits.watchface

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.net.URL

private const val TAG = "MisfitWeather"
private const val REFRESH_MS = 30 * 60 * 1000L

class WeatherProvider(
    private val context: Context,
    private val onUpdate: (WeatherSnapshot) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)

    fun start() {
        scope.launch {
            while (isActive) {
                fetchAndEmit()
                delay(REFRESH_MS)
            }
        }
    }

    fun stop() = scope.cancel()

    private suspend fun fetchAndEmit() {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Location permission not granted")
            return
        }

        val loc = try {
            // Try last known first (instant, no battery cost)
            val last = fusedClient.lastLocation.await()
            if (last != null) {
                Log.d(TAG, "Using last known location: ${last.latitude}, ${last.longitude}")
                last
            } else {
                // Request a fresh coarse fix
                Log.d(TAG, "No last location — requesting current")
                val cts = CancellationTokenSource()
                fusedClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token).await()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Location fetch failed: ${e.message}")
            null
        }

        if (loc == null) {
            Log.w(TAG, "No location available")
            return
        }

        try {
            val lat = "%.4f".format(loc.latitude)
            val lon = "%.4f".format(loc.longitude)
            val url = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,weather_code" +
                "&daily=temperature_2m_max,temperature_2m_min" +
                "&temperature_unit=fahrenheit&forecast_days=1&timezone=auto"

            val json = JSONObject(URL(url).readText())
            val current = json.getJSONObject("current")
            val daily = json.getJSONObject("daily")

            val snap = WeatherSnapshot(
                tempF = current.getDouble("temperature_2m").toFloat(),
                conditionCode = current.getInt("weather_code"),
                highF = daily.getJSONArray("temperature_2m_max").getDouble(0).toFloat(),
                lowF = daily.getJSONArray("temperature_2m_min").getDouble(0).toFloat()
            )
            Log.i(TAG, "Weather: ${snap.tempF}°F code=${snap.conditionCode} H=${snap.highF} L=${snap.lowF}")
            onUpdate(snap)
        } catch (e: Exception) {
            Log.e(TAG, "Weather fetch failed: ${e.message}")
        }
    }
}
