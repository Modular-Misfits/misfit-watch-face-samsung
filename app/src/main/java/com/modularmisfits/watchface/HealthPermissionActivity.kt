package com.modularmisfits.watchface

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.error.ResolvablePlatformException
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
        Permission.of(DataTypes.ENERGY_SCORE, AccessType.READ),
        Permission.of(DataTypes.SLEEP, AccessType.READ)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scope.launch {
            try {
                val store = HealthDataService.getStore(this@HealthPermissionActivity)
                val granted = store.requestPermissions(requiredPermissions, this@HealthPermissionActivity)
                Log.i(TAG, "Permissions granted: $granted")
            } catch (e: ResolvablePlatformException) {
                Log.w(TAG, "ResolvablePlatformException — resolving: ${e.message}")
                if (e.hasResolution) {
                    e.resolve(this@HealthPermissionActivity)
                    // resolve() shows a system dialog; Activity result will come back via onActivityResult
                } else {
                    Log.e(TAG, "No resolution available: ${e.message}")
                    finish()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Permission request failed: ${e.message}")
                finish()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // After platform resolution (e.g. Samsung Health install/update), re-attempt permission request
        scope.launch {
            try {
                val store = HealthDataService.getStore(this@HealthPermissionActivity)
                val granted = store.requestPermissions(requiredPermissions, this@HealthPermissionActivity)
                Log.i(TAG, "Permissions granted after resolution: $granted")
            } catch (e: Exception) {
                Log.e(TAG, "Permission request failed after resolution: ${e.message}")
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
