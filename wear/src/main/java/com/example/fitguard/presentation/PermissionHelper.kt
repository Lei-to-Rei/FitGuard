package com.example.fitguard.presentation

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Enhanced Permission Helper with detailed diagnostics
 * for Wear OS 6 / Health Platform 1.7
 */
object PermissionHelper {
    private const val TAG = "PermissionHelper"
    const val PERMISSION_REQUEST_CODE = 100

    object Permissions {
        // Runtime permissions
        const val ACTIVITY_RECOGNITION = Manifest.permission.ACTIVITY_RECOGNITION
        const val BODY_SENSORS = Manifest.permission.BODY_SENSORS
        const val BODY_SENSORS_BACKGROUND = Manifest.permission.BODY_SENSORS_BACKGROUND
        const val HIGH_SAMPLING_RATE = "android.permission.HIGH_SAMPLING_RATE_SENSORS"
        const val POST_NOTIFICATIONS = Manifest.permission.POST_NOTIFICATIONS

        // Samsung Health Platform
        const val SAMSUNG_HEALTH_DATA = "com.samsung.android.permission.HEALTH_DATA"
        const val SAMSUNG_HEALTH_READ = "com.samsung.android.providers.health.permission.READ"
        const val SAMSUNG_HEALTH_WRITE = "com.samsung.android.providers.health.permission.WRITE"
        const val SAMSUNG_SENSOR_MANAGER = "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA"
    }

    /**
     * Get ALL possible runtime permissions based on Android version
     */
    fun getAllRuntimePermissions(): Array<String> {
        val permissions = mutableListOf(
            Permissions.ACTIVITY_RECOGNITION,
            Permissions.BODY_SENSORS
        )

        // Background sensors (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Permissions.BODY_SENSORS_BACKGROUND)
        }

        // High sampling rate (API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Permissions.HIGH_SAMPLING_RATE)
        }

        // Notifications (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Permissions.POST_NOTIFICATIONS)
        }

        return permissions.toTypedArray()
    }

    /**
     * Request permissions in STAGES to avoid denial
     */
    fun requestPermissionsStaged(activity: Activity, stage: Int = 1) {
        when (stage) {
            1 -> {
                // Stage 1: Core permissions first
                Log.d(TAG, "Stage 1: Requesting core permissions")
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(
                        Permissions.ACTIVITY_RECOGNITION,
                        Permissions.BODY_SENSORS
                    ),
                    PERMISSION_REQUEST_CODE + 1
                )
            }
            2 -> {
                // Stage 2: Background permission (after core granted)
                Log.d(TAG, "Stage 2: Requesting background permission")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ActivityCompat.requestPermissions(
                        activity,
                        arrayOf(Permissions.BODY_SENSORS_BACKGROUND),
                        PERMISSION_REQUEST_CODE + 2
                    )
                }
            }
            3 -> {
                // Stage 3: High sampling rate
                Log.d(TAG, "Stage 3: Requesting high sampling rate")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    ActivityCompat.requestPermissions(
                        activity,
                        arrayOf(Permissions.HIGH_SAMPLING_RATE),
                        PERMISSION_REQUEST_CODE + 3
                    )
                }
            }
        }
    }

    /**
     * Request ALL permissions at once (may fail on some devices)
     */
    fun requestAllPermissions(activity: Activity) {
        Log.d(TAG, "Requesting ALL permissions at once")
        ActivityCompat.requestPermissions(
            activity,
            getAllRuntimePermissions(),
            PERMISSION_REQUEST_CODE
        )
    }

    /**
     * Check permission with detailed logging
     */
    fun checkPermissionDetailed(context: Context, permission: String): PermissionDetail {
        val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        val shouldShow = if (context is Activity) {
            ActivityCompat.shouldShowRequestPermissionRationale(context, permission)
        } else false

        val declared = isPermissionDeclared(context, permission)

        Log.d(TAG, "Permission: ${permission.split(".").last()}")
        Log.d(TAG, "  - Granted: $granted")
        Log.d(TAG, "  - Should show rationale: $shouldShow")
        Log.d(TAG, "  - Declared in manifest: $declared")

        return PermissionDetail(
            permission = permission,
            granted = granted,
            shouldShowRationale = shouldShow,
            declaredInManifest = declared,
            status = when {
                granted -> PermissionStatus.GRANTED
                shouldShow -> PermissionStatus.DENIED_SHOW_RATIONALE
                !shouldShow && !granted -> PermissionStatus.DENIED_PERMANENTLY
                else -> PermissionStatus.NOT_REQUESTED
            }
        )
    }

    /**
     * Comprehensive diagnostic report
     */
    fun runDiagnostics(context: Context): DiagnosticReport {
        Log.d(TAG, "\n========== RUNNING DIAGNOSTICS ==========")

        // Device info
        Log.d(TAG, "Device: ${Build.MODEL}")
        Log.d(TAG, "Manufacturer: ${Build.MANUFACTURER}")
        Log.d(TAG, "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")

        // Check all runtime permissions
        val runtimePermissions = mutableMapOf<String, PermissionDetail>()
        getAllRuntimePermissions().forEach { permission ->
            runtimePermissions[permission] = checkPermissionDetailed(context, permission)
        }

        // Check Samsung permissions
        val samsungPermissions = mutableMapOf<String, Boolean>()
        listOf(
            Permissions.SAMSUNG_HEALTH_DATA,
            Permissions.SAMSUNG_HEALTH_READ,
            Permissions.SAMSUNG_HEALTH_WRITE,
            Permissions.SAMSUNG_SENSOR_MANAGER
        ).forEach { permission ->
            val declared = isPermissionDeclared(context, permission)
            samsungPermissions[permission] = declared
            Log.d(TAG, "${permission.split(".").last()}: ${if (declared) "✓ In manifest" else "✗ NOT in manifest"}")
        }

        // Check Samsung Health Platform
        val healthPlatformInstalled = isPackageInstalled(context, "com.samsung.android.service.health")
        Log.d(TAG, "Samsung Health Platform installed: $healthPlatformInstalled")

        // Check Samsung Health app
        val healthAppInstalled = isPackageInstalled(context, "com.sec.android.app.shealth")
        Log.d(TAG, "Samsung Health app installed: $healthAppInstalled")

        val allRuntimeGranted = runtimePermissions.values.all { it.granted }
        val allSamsungDeclared = samsungPermissions.values.all { it }

        Log.d(TAG, "\nSummary:")
        Log.d(TAG, "  All runtime permissions granted: $allRuntimeGranted")
        Log.d(TAG, "  All Samsung permissions declared: $allSamsungDeclared")
        Log.d(TAG, "  Ready for health tracking: ${allRuntimeGranted && allSamsungDeclared}")
        Log.d(TAG, "=========================================\n")

        return DiagnosticReport(
            runtimePermissions = runtimePermissions,
            samsungPermissions = samsungPermissions,
            healthPlatformInstalled = healthPlatformInstalled,
            healthAppInstalled = healthAppInstalled,
            allRuntimeGranted = allRuntimeGranted,
            allSamsungDeclared = allSamsungDeclared,
            readyForTracking = allRuntimeGranted && allSamsungDeclared && healthPlatformInstalled
        )
    }

    /**
     * Get user-friendly instructions for fixing permission issues
     */
    fun getFixInstructions(context: Context): String {
        val report = runDiagnostics(context)
        val instructions = StringBuilder()

        instructions.appendLine("🔧 Setup Instructions:\n")

        // Check denied permissions
        val denied = report.runtimePermissions.filter { !it.value.granted }
        if (denied.isNotEmpty()) {
            instructions.appendLine("❌ Missing Permissions:")
            denied.forEach { (permission, detail) ->
                val name = when (permission) {
                    Permissions.ACTIVITY_RECOGNITION -> "Physical Activity"
                    Permissions.BODY_SENSORS -> "Body Sensors / Fitness and Wellness"
                    Permissions.BODY_SENSORS_BACKGROUND -> "Background Body Sensors"
                    Permissions.HIGH_SAMPLING_RATE -> "High Sampling Rate Sensors"
                    else -> permission.split(".").last()
                }

                instructions.appendLine("\n📍 $name:")
                when (detail.status) {
                    PermissionStatus.DENIED_PERMANENTLY -> {
                        instructions.appendLine("   Status: Permanently Denied")
                        instructions.appendLine("   Fix: Settings → Apps → FitGuard → Permissions")
                        instructions.appendLine("        → Enable this permission")
                        if (permission == Permissions.BODY_SENSORS_BACKGROUND) {
                            instructions.appendLine("        → Select 'Allow all the time'")
                        }
                    }
                    PermissionStatus.DENIED_SHOW_RATIONALE -> {
                        instructions.appendLine("   Status: Denied (can request again)")
                        instructions.appendLine("   Fix: Tap 'Request Permissions' button")
                    }
                    PermissionStatus.NOT_REQUESTED -> {
                        instructions.appendLine("   Status: Not yet requested")
                        instructions.appendLine("   Fix: Tap 'Request Permissions' button")
                    }
                    else -> {}
                }
            }
        }

        // Check Samsung permissions
        val missingSamsung = report.samsungPermissions.filter { !it.value }
        if (missingSamsung.isNotEmpty()) {
            instructions.appendLine("\n\n❌ Missing Samsung Permissions in Manifest:")
            missingSamsung.forEach { (permission, _) ->
                instructions.appendLine("   - ${permission.split(".").last()}")
            }
            instructions.appendLine("\n   Fix: Update AndroidManifest.xml with Samsung permissions")
        }

        // Check Health Platform
        if (!report.healthPlatformInstalled) {
            instructions.appendLine("\n\n❌ Samsung Health Platform NOT Installed!")
            instructions.appendLine("   Fix: Install from Galaxy Store")
        }

        if (report.readyForTracking) {
            instructions.appendLine("\n\n✅ All checks passed! Ready to track health data.")
        }

        return instructions.toString()
    }

    private fun isPermissionDeclared(context: Context, permission: String): Boolean {
        return try {
            val info = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_PERMISSIONS
            )
            info.requestedPermissions?.contains(permission) ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    // Data classes
    data class PermissionDetail(
        val permission: String,
        val granted: Boolean,
        val shouldShowRationale: Boolean,
        val declaredInManifest: Boolean,
        val status: PermissionStatus
    )

    data class DiagnosticReport(
        val runtimePermissions: Map<String, PermissionDetail>,
        val samsungPermissions: Map<String, Boolean>,
        val healthPlatformInstalled: Boolean,
        val healthAppInstalled: Boolean,
        val allRuntimeGranted: Boolean,
        val allSamsungDeclared: Boolean,
        val readyForTracking: Boolean
    )

    enum class PermissionStatus {
        GRANTED,
        DENIED_SHOW_RATIONALE,
        DENIED_PERMANENTLY,
        NOT_REQUESTED
    }
}