package com.modularmisfits.watchface

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.DataTypes
import kotlinx.coroutines.*

private const val TAG = "HealthPermission"

class HealthPermissionActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val requiredPermissions = setOf(
        Permission.of(DataTypes.STEPS, AccessType.READ),
        Permission.of(DataTypes.ACTIVITY_SUMMARY, AccessType.READ),
        Permission.of(DataTypes.HEART_RATE, AccessType.READ),
        Permission.of(DataTypes.ENERGY_SCORE, AccessType.READ)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scope.launch {
            try {
                val store = HealthDataService.getStore(this@HealthPermissionActivity)
                val granted = store.requestPermissions(requiredPermissions, this@HealthPermissionActivity)
                Log.i(TAG, "Permissions granted: $granted")
            } catch (e: Exception) {
                Log.e(TAG, "Permission request failed: ${e.message}")
            } finally {
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
