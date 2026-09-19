package com.jarvis.mobile.core.shizuku

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.annotation.Keep
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Shizuku UserService: instantiated by the Shizuku server in a process running
 * as the shell identity (uid 2000, root-free). It only knows how to run an
 * argv array and move bytes - no policy, no parsing (the argus split: the
 * gateway owns trust decisions, the service owns process plumbing).
 *
 * The two-constructor shape is REQUIRED: Shizuku instantiates the service with
 * the (Context) constructor; the no-arg one keeps manual construction valid.
 */
@Keep
class JarvisShellUserService() : IJarvisShellService.Stub() {

    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : this()

    private val streams = CopyOnWriteArrayList<Process>()
    private val tornDown = AtomicBoolean(false)

    override fun exec(command: Array<out String>?, timeoutMillis: Long, maxOutputBytes: Int): Bundle {
        if (tornDown.get()) return errorBundle("torn_down")
        val cmd = command?.toList().orEmpty()
        if (cmd.isEmpty() || cmd.size > MAX_ARGUMENTS || cmd.sumOf { it.length } > MAX_COMMAND_CHARS) {
            return errorBundle("command_invalid")
        }
        if (timeoutMillis !in 1..MAX_TIMEOUT_MS || maxOutputBytes !in 1..MAX_OUTPUT_BYTES) {
            return errorBundle("limits_invalid")
        }
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val truncated = AtomicBoolean(false)
        val process = try {
            ProcessBuilder(cmd).directory(File("/")).start()
        } catch (_: Exception) {
            return errorBundle("start_failed")
        }
        runCatching { process.outputStream.close() }
        val outThread = drain(process.inputStream, stdout, maxOutputBytes, truncated)
        val errThread = drain(process.errorStream, stderr, MAX_STDERR_BYTES, truncated)
        val finished = try {
            process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) process.destroy()
        outThread.join(JOIN_MS)
        errThread.join(JOIN_MS)
        return Bundle().apply {
            putInt(KEY_EXIT_CODE, if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1)
            putByteArray(KEY_STDOUT, stdout.toByteArray())
            putByteArray(KEY_STDERR, stderr.toByteArray())
            putBoolean(KEY_TIMED_OUT, !finished)
            putBoolean(KEY_TRUNCATED, truncated.get())
        }
    }

    override fun stream(command: Array<out String>?, sink: ParcelFileDescriptor?) {
        if (tornDown.get() || command.isNullOrEmpty() || sink == null) return
        try {
            val process = ProcessBuilder(*command)
                .directory(File("/"))
                .redirectErrorStream(true)
                .start()
            streams.add(process)
            thread(isDaemon = true, name = "jarvis-shell-stream") {
                ParcelFileDescriptor.AutoCloseOutputStream(sink).use { out ->
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val n = process.inputStream.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                    out.flush()
                }
            }
        } catch (_: Exception) {
            runCatching { sink.close() }
        }
    }

    override fun uid(): Int = Process.myUid()

    override fun destroy() {
        if (!tornDown.compareAndSet(false, true)) return
        streams.forEach { runCatching { it.destroy() } }
        streams.clear()
        kotlin.system.exitProcess(0)
    }

    private fun drain(
        input: java.io.InputStream,
        output: OutputStream,
        limit: Int,
        truncated: AtomicBoolean,
    ) = thread(start = true, isDaemon = true, name = "jarvis-shell-drain") {
        val buffer = ByteArray(8 * 1024)
        var written = 0
        try {
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                val accepted = minOf(n, limit - written)
                if (accepted > 0) output.write(buffer, 0, accepted)
                if (accepted < n) truncated.set(true)
                written += accepted
            }
            output.flush()
        } catch (_: Exception) {
            // stream closed during teardown - the exit code already reflects reality
        }
    }

    private fun errorBundle(code: String) = Bundle().apply {
        putInt(KEY_EXIT_CODE, -127)
        putString(KEY_ERROR_CODE, code)
    }

    companion object {
        const val KEY_EXIT_CODE = "exit_code"
        const val KEY_STDOUT = "stdout"
        const val KEY_STDERR = "stderr"
        const val KEY_TIMED_OUT = "timed_out"
        const val KEY_TRUNCATED = "truncated"
        const val KEY_ERROR_CODE = "error_code"
        const val MAX_TIMEOUT_MS = 60_000L
        const val MAX_OUTPUT_BYTES = 1024 * 1024
        private const val MAX_ARGUMENTS = 64
        private const val MAX_COMMAND_CHARS = 8 * 1024
        private const val MAX_STDERR_BYTES = 64 * 1024
        private const val JOIN_MS = 2_000L
    }
}
