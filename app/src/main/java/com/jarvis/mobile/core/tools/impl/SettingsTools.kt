package com.jarvis.mobile.core.tools.impl

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.jarvis.mobile.JarvisApp
import com.jarvis.mobile.core.tools.Tool
import com.jarvis.mobile.core.tools.ToolContext
import com.jarvis.mobile.core.tools.ToolResult
import com.jarvis.mobile.core.tools.ToolSpec
import com.jarvis.mobile.core.tools.Verification
import kotlinx.serialization.json.JsonObject

/**
 * Device settings tools.
 * Android reality (honesty requirement): Wi-Fi / Bluetooth CANNOT be silently
 * toggled by third-party apps on modern Android. JARVIS opens the official
 * panel (Settings.Panel) so the user completes one tap, then JARVIS verifies
 * the resulting state and reports honestly.
 */
class ControlBrightnessTool : Tool(
    ToolSpec(
        "control_brightness", "Set screen brightness (0-255).",
        listOf(com.jarvis.mobile.core.tools.ParamSpec("value", "int", true, "brightness 0-255, or 1-100 if percent")),
        com.jarvis.mobile.core.tools.Risk.MEDIUM,
    ),
) {
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val context = JarvisApp.instance
        if (!Settings.System.canWrite(context)) {
            val i = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .setData(Uri.parse("package:${context.packageName}"))
            runCatching { context.startActivity(i) }
            return ToolResult(
                com.jarvis.mobile.core.tools.ToolStatus.REQUIRES_CONFIRMATION,
                "JARVIS needs the \"Modify system settings\" permission once. Grant it in the screen I opened, then ask me again.",
                Verification.COULD_NOT_VERIFY,
                recoveryHint = "user-grants-write-settings",
            )
        }
        var value = T.int(args, "value") ?: return ToolResult.fail("Missing required arg: value.")
        if (value in 1..100) value = (value * 255) / 100 // percent form
        value = value.coerceIn(1, 255)
        return runCatching {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
            val readBack = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
            ToolResult.ok(
                "Brightness set.",
                if (readBack == value) Verification.VERIFIED else Verification.UNVERIFIED,
                detail = "brightness=$value",
            )
        }.getOrElse { ToolResult.fail("Brightness change failed: ${it.message?.take(60)}") }
    }
}

class ControlVolumeTool : Tool(
    ToolSpec(
        "control_volume", "Set or adjust media volume.",
        listOf(
            com.jarvis.mobile.core.tools.ParamSpec("value", "int", false, "absolute level 0-15"),
            com.jarvis.mobile.core.tools.ParamSpec("direction", "string", false, "up | down | mute"),
        ),
        com.jarvis.mobile.core.tools.Risk.MEDIUM,
    ),
) {
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val context = JarvisApp.instance
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val dir = T.str(args, "direction")?.lowercase()
        val target: Int = when {
            dir == "up" -> cur + 1
            dir == "down" -> cur - 1
            dir == "mute" -> 0
            else -> (T.int(args, "value") ?: return ToolResult.fail("Provide value (0-$max) or direction."))
                .let { if (it in 1..100 && max > 100) it else (it * max) / 100.coerceAtLeast(1) }
        }.coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        val readBack = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        return ToolResult.ok(
            "Media volume set to $target of $max.",
            if (readBack == target) Verification.VERIFIED else Verification.UNVERIFIED,
        )
    }
}

class ControlFlashlightTool : Tool(
    ToolSpec(
        "control_flashlight", "Turn the flashlight (torch) on or off.",
        listOf(com.jarvis.mobile.core.tools.ParamSpec("on", "bool", true, "true = on, false = off")),
        com.jarvis.mobile.core.tools.Risk.LOW,
    ),
) {
    private var torchOn = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val context = JarvisApp.instance
        val cam = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cam.cameraIdList.firstOrNull {
            cam.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                cam.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return ToolResult.unavailable("No flash unit found on this device.")
        val on = T.bool(args, "on") ?: return ToolResult.fail("Missing required arg: on (true/false).")
        return runCatching {
            cam.setTorchMode(cameraId, on)
            torchOn = on
            ToolResult.ok("Flashlight turned ${if (on) "on" else "off"}.", Verification.UNVERIFIED, detail = "torch=$on")
        }.getOrElse { ToolResult.fail("Torch control failed: ${it.message?.take(60)} (camera may be in use).") }
    }
}

class ControlWifiTool : Tool(
    ToolSpec(
        "control_wifi", "Toggle Wi-Fi. Opens the official connectivity panel on Android 10+ (system policy), then verifies the resulting state.",
        listOf(com.jarvis.mobile.core.tools.ParamSpec("on", "bool", true, "true = enable, false = disable")),
        com.jarvis.mobile.core.tools.Risk.MEDIUM,
    ),
) {
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val context = JarvisApp.instance
        val on = T.bool(args, "on") ?: return ToolResult.fail("Missing required arg: on.")
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val ok = wm.isWifiEnabled != on && runCatching { wm.setWifiEnabled(on) }.getOrDefault(false)
            kotlinx.coroutines.delay(700)
            val state = wm.isWifiEnabled
            return ToolResult.ok(
                "Wi-Fi ${if (state) "enabled" else "disabled"}.",
                if (state == on) Verification.VERIFIED else Verification.FAILED,
            )
        }
        val wasOn = runCatching { wm.isWifiEnabled }.getOrDefault(false)
        if (wasOn == on) return ToolResult.ok("Wi-Fi is already ${if (on) "on" else "off"}.", Verification.VERIFIED)
        val panel = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(panel)
            T.settle(900)
            val nowOn = runCatching { wm.isWifiEnabled }.getOrDefault(wasOn)
            if (nowOn == on) {
                ToolResult.ok("Wi-Fi is now ${if (on) "on" else "off"}.", Verification.VERIFIED)
            } else {
                ToolResult(
                    com.jarvis.mobile.core.tools.ToolStatus.SUCCESS,
                    "I opened the Android connectivity panel - Android does not allow silent Wi-Fi changes. Tap the Wi-Fi toggle ${if (on) "on" else "off"} and I will verify.",
                    Verification.UNVERIFIED,
                    recoveryHint = "user-toggles-in-panel",
                )
            }
        }.getOrElse { ToolResult.fail("Could not open the connectivity panel.") }
    }
}

class ControlBluetoothTool : Tool(
    ToolSpec(
        "control_bluetooth", "Toggle Bluetooth. Opens Bluetooth settings on Android 13+ (system policy), then verifies.",
        listOf(com.jarvis.mobile.core.tools.ParamSpec("on", "bool", true, "true = enable, false = disable")),
        com.jarvis.mobile.core.tools.Risk.MEDIUM,
    ),
) {
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val context = JarvisApp.instance
        val on = T.bool(args, "on") ?: return ToolResult.fail("Missing required arg: on.")
        val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            ?: return ToolResult.unavailable("This device has no Bluetooth adapter.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
            return ToolResult(
                com.jarvis.mobile.core.tools.ToolStatus.SUCCESS,
                "Android 13+ blocks silent Bluetooth changes - I opened Bluetooth settings. Toggle it ${if (on) "on" else "off"} and tell me to verify.",
                Verification.UNVERIFIED,
                recoveryHint = "user-toggles-in-settings",
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return ToolResult(
                    com.jarvis.mobile.core.tools.ToolStatus.REQUIRES_CONFIRMATION,
                    "Grant the Nearby devices (Bluetooth) permission to JARVIS first.",
                    Verification.COULD_NOT_VERIFY,
                    recoveryHint = "grant-bluetooth-permission",
                )
            }
        }
        val wasOn = runCatching { adapter.isEnabled }.getOrDefault(false)
        if (wasOn == on) return ToolResult.ok("Bluetooth is already ${if (on) "on" else "off"}.", Verification.VERIFIED)
        val ok = if (on) runCatching { adapter.enable() }.getOrDefault(false) else runCatching { adapter.disable() }.getOrDefault(false)
        kotlinx.coroutines.delay(1200)
        val now = runCatching { adapter.isEnabled }.getOrDefault(wasOn)
        return ToolResult.ok(
            "Bluetooth ${if (now == on) "is now" else "toggle requested →"} ${if (on) "on" else "off"}.",
            if (now == on) Verification.VERIFIED else Verification.UNVERIFIED,
        )
    }
}
