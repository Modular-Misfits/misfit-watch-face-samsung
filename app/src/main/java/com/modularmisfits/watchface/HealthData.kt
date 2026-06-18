package com.modularmisfits.watchface

data class HealthSnapshot(
    val steps: Int = 0,
    val activeMinutes: Int = 0,
    val heartRate: Int = 0,
    // 0–100 wellness/energy score (proxy for stress; 0 = depleted, 100 = peak)
    val energyScore: Int = 50
)
