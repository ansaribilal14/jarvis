package com.jarvis.mobile.core.model

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.PowerManager
import java.io.File

/**
 * Capability-aware device profiling (spec: MODEL COMPATIBILITY ENGINE).
 * Never recommends a model the device cannot run.
 */
class DeviceProfiler(private val context: Context) {

    enum class DeviceClass { BASIC, STANDARD, POWER, HIGH_END }

    data class Profile(
        val model: String,
        val androidVersion: String,
        val sdkInt: Int,
        val abi: String,
        val cores: Int,
        val totalRamGb: Double,
        val availRamGb: Double,
        val appHeapGb: Double,
        val vulkan: Boolean,
        val storageFreeGb: Double,
        val deviceClass: DeviceClass,
        val thermalStatus: String,
    )

    fun profile(): Profile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mem)
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (pm.getCurrentThermalStatus()) {
                0 -> "none"; 1 -> "light"; 2 -> "moderate"; 3 -> "severe"; else -> "critical"
            }
        } else "unknown"

        val totalGb = mem.totalMem / 1e9
        val availGb = mem.availMem / 1e9
        val heapGb = Runtime.getRuntime().maxMemory() / 1e9
        val stat = StatFs(Environment.getDataDirectory().path)
        val freeGb = stat.availableBytes / 1e9

        val cls = when {
            totalGb >= 8 && Runtime.getRuntime().availableProcessors() >= 8 -> DeviceClass.HIGH_END
            totalGb >= 6 -> DeviceClass.POWER
            totalGb >= 4 -> DeviceClass.STANDARD
            else -> DeviceClass.BASIC
        }
        return Profile(
            model = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            cores = Runtime.getRuntime().availableProcessors(),
            totalRamGb = Math.round(totalGb * 10) / 10.0,
            availRamGb = Math.round(availGb * 10) / 10.0,
            appHeapGb = Math.round(heapGb * 10) / 10.0,
            vulkan = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_VULKAN_HARDWARE_VERSION),
            storageFreeGb = Math.round(freeGb * 10) / 10.0,
            deviceClass = cls,
            thermalStatus = thermal,
        )
    }
}
