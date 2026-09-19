package com.jarvis.mobile.core.shizuku

import android.content.Context
import android.content.pm.PackageManager
import com.jarvis.mobile.util.Logx
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/**
 * Shizuku privileged-shell bridge (the argus/AutoX capability bus, root-free).
 *
 * Why this exists: recording EVERY tap with exact coordinates system-wide is
 * impossible with plain accessibility events (apps can silently emit nothing -
 * the exact "records nothing" failure users kept reporting), and the v1.9/v1.10
 * attempts to use the framework's touch-interception APIs either consumed the
 * user's touches or depended on touch-exploration semantics that vary by ROM.
 * A `getevent` stream read through Shizuku (shell identity) is what AutoX does
 * with root and scrcpy does with adb - it observes the raw touchscreen without
 * touching a single user interaction.
 *
 * State machine mirrors argus ShizukuGateway: NOT_INSTALLED -> NOT_RUNNING ->
 * NOT_AUTHORIZED -> READY. All failure paths are honest states, never crashes.
 */
object ShizukuBridge {

    private const val TAG = "shizuku"

    enum class Status { NOT_INSTALLED, NOT_RUNNING, NOT_AUTHORIZED, READY }

    data class BridgeState(
        val status: Status = Status.NOT_INSTALLED,
        val reason: String = "",
    )

    private val _state = MutableStateFlow(BridgeState())
    val state: StateFlow<BridgeState> = _state.asStateFlow()

    private var listenersBound = false

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        refresh()
        if (grantResult != PackageManager.PERMISSION_GRANTED) {
            Logx.w(TAG, "Shizuku permission denied by user")
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refresh()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        _state.value = BridgeState(Status.NOT_RUNNING, "Shizuku server stopped")
    }

    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        true
    }.getOrDefault(false)

    /** Bind Shizuku listeners; safe to call repeatedly. Called from JarvisApp. */
    fun init() {
        runCatching {
            if (!listenersBound) {
                Shizuku.addRequestPermissionResultListener(permissionListener)
                Shizuku.addBinderReceivedListener(binderReceivedListener)
                Shizuku.addBinderDeadListener(binderDeadListener)
                listenersBound = true
            }
            refresh()
        }.onFailure { Logx.w(TAG, "init failed: ${it.message}") }
    }

    /** Re-read the live Shizuku status into [state]. */
    fun refresh() {
        val next = runCatching {
            when {
                Shizuku.pingBinder() -> {
                    if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                        BridgeState(Status.READY, "Shizuku ready")
                    } else {
                        BridgeState(Status.NOT_AUTHORIZED, "Shizuku permission not granted yet")
                    }
                }
                else -> BridgeState(Status.NOT_RUNNING, "Shizuku server not running")
            }
        }.getOrElse { BridgeState(Status.NOT_RUNNING, "Shizuku unavailable: ${it.message?.take(60)}") }
        if (_state.value != next) {
            _state.value = next
            Logx.i(TAG, "status=${next.status} (${next.reason})")
        }
    }

    /** Ask the user to grant the Shizuku permission (fires the manager dialog). */
    fun requestPermission() {
        runCatching {
            if (Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
            ) {
                Shizuku.requestPermission(0x517)
            }
        }.onFailure { Logx.w(TAG, "requestPermission failed: ${it.message}") }
    }

    /**
     * Run a command under the shell identity synchronously; returns full stdout
     * (argv-array only - never `sh -c`, the argus rule). Null on any failure.
     */
    fun exec(vararg cmd: String): String? = runCatching {
        val p = Shizuku.newProcess(cmd, null, "/")
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        out
    }.onFailure { Logx.w(TAG, "exec ${cmd.firstOrNull()} failed: ${it.message}") }
        .getOrNull()

    fun ready(): Boolean = _state.value.status == Status.READY
}
