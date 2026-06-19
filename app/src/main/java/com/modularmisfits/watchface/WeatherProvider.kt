package com.modularmisfits.watchface

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URL

private const val TAG = "MisfitWeather"
private const val REFRESH_MS = 30 * 60 * 1000L

class WeatherProvider(
    private val context: Context,
    private val onUpdate: (WeatherSnapshot) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        val loc = getLocation() ?: run {
            Log.w(TAG, "No location available")
            return
        }
        try {
            val lat = "%.4f".format(loc.latitude)
            val lon = "%.4f".format(loc.longitude)
            val url = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,weather_code" +
                "&daily=temperature_2m_max,temperature_2m_min,weather_code" +
                "&temperature_unit=celsius&forecast_days=1&timezone=auto"

            val json = JSONObject(URL(url).readText())
            val current = json.getJSONObject("current")
            val daily = json.getJSONObject("daily")

            val snap = WeatherSnapshot(
                tempC = current.getDouble("temperature_2m").toFloat(),
                conditionCode = current.getInt("weather_code"),
                highC = daily.getJSONArray("temperature_2m_max").getDouble(0).toFloat(),
                lowC = daily.getJSONArray("temperature_2m_min").getDouble(0).toFloat()
            )
            Log.i(TAG, "Weather: ${snap.tempC}°C code=${snap.conditionCode} H=${snap.highC} L=${snap.lowC}")
            onUpdate(snap)
        } catch (e: Exception) {
            Log.e(TAG, "Weather fetch failed: ${e.message}")
        }
    }

    private fun getLocation(): Location? {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return null

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        return providers.mapNotNull { provider ->
            try { lm.getLastKnownLocation(provider) } catch (e: Exception) { null }
        }.maxByOrNull { it.time }
    }
}
