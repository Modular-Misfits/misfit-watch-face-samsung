package com.modularmisfits.watchface

data class HealthSnapshot(
    val steps: Int = 0,
    val activeMinutes: Int = 0,
    val heartRate: Int = 0,
    val energyScore: Int = 50,
    val calories: Int = 0,
    val sleepScore: Int = 0   // 0 = no data yet
)
