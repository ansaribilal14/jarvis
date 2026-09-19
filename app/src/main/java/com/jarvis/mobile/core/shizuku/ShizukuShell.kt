package com.jarvis.mobile.core.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.ParcelFileDescriptor
import com.jarvis.mobile.JarvisApp
import com.jarvis.mobile.util.Logx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * App-side transport to [JarvisShellUserService] via Shizuku.bindUserService -
 * the argus-proven pattern, implemented against Shizuku api 13.1.5. Lazy bind,
 * ping-validating reuse, single connection, explicit destroy() on stop (which
 * kills the shell process tree, including a live getevent stream).
 */
object ShizukuShell {

    private const val TAG = "shizuku-shell"
    private const val VERSION = 1
    private const val BIND_TIMEOUT_MS = 15_000L

    private val mutex = Mutex()
    private val binding = AtomicBoolean(false)
    private var service: IJarvisShellService? = null
    private var connection: ServiceConnection? = null

    private val args by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(JarvisApp.instance.packageName, JarvisShellUserService::class.java.name),
        )
            .daemon(false)
            .processNameSuffix("jarvis_shell")
            .version(VERSION)
    }

    /** Bind (or reuse) the UserService; suspend until the binder is live. */
    suspend fun ensureService(): IJarvisShellService = mutex.withLock {
        service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        if (!ShizukuBridge.ready()) error("shizuku_not_ready")
        if (!binding.compareAndSet(false, true)) error("bind_in_progress")
        try {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    val conn = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, binder: android.os.IBinder?) {
                            val remote = binder?.let { IJarvisShellService.Stub.asInterface(it) }
                            if (remote == null || !remote.asBinder().pingBinder()) {
                                if (cont.isActive) cont.resumeWithException(IllegalStateException("bad_binder"))
                                return
                            }
                            service = remote
                            connection = this
                            if (cont.isActive) cont.resume(remote)
                        }

                        override fun onServiceDisconnected(name: ComponentName?) {
                            service = null
                            connection = null
                        }
                    }
                    try {
                        Shizuku.bindUserService(args, conn)
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }
            }
        } finally {
            binding.set(false)
        }
    }

    /** One-shot command; returns stdout text or null on any failure/non-zero exit. */
    suspend fun exec(vararg cmd: String, timeoutMs: Long = 20_000): String? = withContext(Dispatchers.IO) {
        runCatching {
            val remote = ensureService()
            val b = remote.exec(cmd, timeoutMs.coerceAtMost(JarvisShellUserService.MAX_TIMEOUT_MS), JarvisShellUserService.MAX_OUTPUT_BYTES)
            val exit = b.getInt(JarvisShellUserService.KEY_EXIT_CODE, -127)
            val out = String(b.getByteArray(JarvisShellUserService.KEY_STDOUT) ?: ByteArray(0))
            if (exit != 0) {
                Logx.w(TAG, "exec $cmd exit=$exit err=${String(b.getByteArray(JarvisShellUserService.KEY_STDERR) ?: ByteArray(0)).take(120)}")
                null
            } else out
        }.onFailure { Logx.w(TAG, "exec failed: ${it.message}") }.getOrNull()
    }

    /**
     * Start a continuous command stream; the reader side is returned. Stop by
     * [closeStream] (kills the shell process tree via destroy()).
     */
    suspend fun stream(vararg cmd: String): Pair<BufferedReader, StreamHandle>? = withContext(Dispatchers.IO) {
        runCatching {
            val remote = ensureService()
            val pipe = ParcelFileDescriptor.createPipe()
            val readEnd = pipe[0]
            val writeEnd = pipe[1]
            remote.stream(cmd, writeEnd)
            // The service dups its own copy of the write end; closing ours lets EOF
            // propagate to the reader once the stream process exits.
            runCatching { writeEnd.close() }
            val input: InputStream = ParcelFileDescriptor.AutoCloseInputStream(readEnd)
            Pair(input.bufferedReader(), StreamHandle(readEnd, remote))
        }.onFailure { Logx.w(TAG, "stream failed: ${it.message}") }.getOrNull()
    }

    class StreamHandle(private val readEnd: ParcelFileDescriptor, private val remote: IJarvisShellService) {
        /** Tear the stream down: close the pipe (unblocks the reader) + kill the service process. */
        fun stop() {
            runCatching { readEnd.close() }
            runCatching { remote.destroy() }
            runCatching { connection?.let { Shizuku.unbindUserService(args, it, true) } }
            service = null
            connection = null
        }
    }
}
