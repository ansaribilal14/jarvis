package com.jarvis.mobile.core.shizuku

import com.jarvis.mobile.core.adb.SelfHostShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader

/**
 * Unified privileged-shell surface - "who can read the kernel touch stream":
 *  1. the BUILT-IN self-host shell ([SelfHostShell], started through JARVIS's
 *     own in-app wireless-debugging pairing - no other app needed), then
 *  2. the external Shizuku app ([ShizukuShell]) for users who run it anyway.
 *
 * The recorder depends on this facade, never on a concrete transport, so
 * "records nothing" can no longer be caused by a missing third-party app.
 */
object PrivilegedShell {

    enum class Layer { BUILT_IN, SHIZUKU }

    data class Acquired(val layer: Layer, val shell: PrivShell)

    suspend fun acquire(): Acquired? = withContext(Dispatchers.IO) {
        if (SelfHostShell.ready()) {
            Acquired(Layer.BUILT_IN, BuiltInShell)
        } else if (ShizukuBridge.ready()) {
            Acquired(Layer.SHIZUKU, ShizukuShellAdapter)
        } else {
            null
        }
    }

    /** Human description of what precision capture would need right now. */
    fun missingReason(): String =
        "no privileged shell yet - set up the built-in one (Skills screen) or start the Shizuku app"
}

/** Transport-agnostic exec/stream contract. */
interface PrivShell {
    suspend fun exec(vararg cmd: String, timeoutMs: Long = 20_000): String?
    suspend fun stream(vararg cmd: String): Pair<BufferedReader, StreamHandle>?
}

interface StreamHandle {
    /** Tear the stream down and kill its child process tree. */
    fun stop()
}

/** Adapter for the self-hosted shell (com.jarvis.mobile.core.adb). */
private object BuiltInShell : PrivShell {
    override suspend fun exec(vararg cmd: String, timeoutMs: Long): String? =
        SelfHostShell.exec(*cmd, timeoutMs = timeoutMs)?.let { r ->
            if (r.exit == 0) r.stdout else null
        }

    override suspend fun stream(vararg cmd: String): Pair<BufferedReader, StreamHandle>? =
        SelfHostShell.stream(*cmd)?.let { (reader, handle) ->
            reader to object : StreamHandle {
                override fun stop() = handle.stop()
            }
        }
}

/** Adapter for the external Shizuku app transport. */
private object ShizukuShellAdapter : PrivShell {
    override suspend fun exec(vararg cmd: String, timeoutMs: Long): String? =
        ShizukuShell.exec(*cmd, timeoutMs = timeoutMs)

    override suspend fun stream(vararg cmd: String): Pair<BufferedReader, StreamHandle>? =
        ShizukuShell.stream(*cmd)?.let { (reader, handle) ->
            reader to object : StreamHandle {
                override fun stop() = handle.stop()
            }
        }
}
