package com.modularmisfits.watchface

import android.content.Context
import android.os.Handler
import android.os.Looper

// Swap MockHealthDataProvider for SamsungHealthDataProvider once the SDK AARs are added.
class HealthDataProvider(context: Context, private val onUpdate: (HealthSnapshot) -> Unit) {

    private val handler = Handler(Looper.getMainLooper())
    private var snapshot = HealthSnapshot(steps = 6842, activeMinutes = 42, heartRate = 72, energyScore = 68)

    // Simulate sensor updates every 10 minutes (600_000ms); use 30s on emulator for visibility.
    private val intervalMs = 30_000L

    private val tick = object : Runnable {
        override fun run() {
            // Mock: drift values slightly so the face feels live on the emulator.
            snapshot = snapshot.copy(
                heartRate = (snapshot.heartRate + (-2..2).random()).coerceIn(55, 110),
                steps = snapshot.steps + (0..80).random(),
                activeMinutes = snapshot.activeMinutes + if ((0..9).random() == 0) 1 else 0,
                energyScore = (snapshot.energyScore + (-3..3).random()).coerceIn(0, 100)
            )
            onUpdate(snapshot)
            handler.postDelayed(this, intervalMs)
        }
    }

    fun start() {
        onUpdate(snapshot)
        handler.postDelayed(tick, intervalMs)
    }

    fun stop() {
        handler.removeCallbacks(tick)
    }
}
