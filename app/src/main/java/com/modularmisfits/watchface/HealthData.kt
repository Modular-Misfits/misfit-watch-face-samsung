package com.modularmisfits.watchface

data class HealthSnapshot(
    val steps: Int = 0,
    val activeMinutes: Int = 0,
    val heartRate: Int = 0,
    val calories: Int = 0,
    val sleepScore: Int = 0
)

data class WeatherSnapshot(
    val tempF: Float = Float.NaN,
    val highF: Float = Float.NaN,
    val lowF: Float = Float.NaN,
    val conditionCode: Int = -1  // WMO weather code
) {
    val hasData get() = !tempF.isNaN()

    fun conditionLabel(): String = when (conditionCode) {
        0 -> "Clear"
        1, 2 -> "Partly Cloudy"
        3 -> "Cloudy"
        45, 48 -> "Foggy"
        51, 53, 55 -> "Drizzle"
        61, 63, 65 -> "Rain"
        71, 73, 75 -> "Snow"
        77 -> "Sleet"
        80, 81, 82 -> "Showers"
        85, 86 -> "Snow Showers"
        95 -> "Thunderstorm"
        96, 99 -> "Severe Storm"
        else -> "--"
    }

    fun conditionSymbol(): String = when (conditionCode) {
        0 -> "☀"
        1, 2 -> "⛅"
        3 -> "☁"
        45, 48 -> "🌫"
        51, 53, 55, 61, 63, 65, 80, 81, 82 -> "🌧"
        71, 73, 75, 77, 85, 86 -> "❄"
        95, 96, 99 -> "⛈"
        else -> "?"
    }
}
