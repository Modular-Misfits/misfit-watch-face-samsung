package com.modularmisfits.watchface

data class HealthSnapshot(
    val steps: Int = 0,
    val activeMinutes: Int = 0,
    val heartRate: Int = 0,
    // 0–100 daily energy/wellness score (proxy for stress gauge)
    val energyScore: Int = 50
)
