package com.jarvis.mobile.core.adb

import androidx.annotation.Keep
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JARVIS's own shell-identity server - the self-hosted Shizuku server. Started
 * BY JARVIS VIA ITS OWN in-app ADB connection:
 *
 *   CLASSPATH=<apk> app_process / --nice-name=jarvis-shell \
 *       com.jarvis.mobile.core.adb.SelfHostServer <port> <token>
 *
 * so it runs as uid 2000 (shell) - the same identity Shizuku's server uses.
 * That identity is what can read /dev/input (the precision touch stream) and
 * run privileged commands, root-free. Listens ONLY on 127.0.0.1 and requires
 * a per-start random token, so no other app on the phone can use it.
 *
 * Binary protocol (big-endian, one TCP connection per command):
 *   client  -> "JARVIS/1 <token>\n"          server -> 'K' (ok) | 'X' (bad token)
 *   'P' ping:                                   server -> frame {"uid":<uid>}
 *   'E' exec: frame(argv...)                    server -> stdout chunks, then result frame
 *   'S' stream: frame(argv...)                  server -> output chunks until exit (0x7FFFFFFF marker)
 *
 * Frames: [4-byte length][bytes]; length 0x7FFFFFFF is the stream-exit marker.
 * Closing the socket kills that connection's child processes.
 */
@Keep
object SelfHostServer {

    private const val HANDSHAKE_PREFIX = "JARVIS/1"
    const val EXIT_MARKER = 0x7FFFFFFF
    private const val MAX_ARGS = 64
    private const val MAX_ARG_BYTES = 8 * 1024
    private const val MAX_OUTPUT_BYTES = 4 * 1024 * 1024
    private const val MAX_STDERR_BYTES = 64 * 1024

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 2) {
            System.err.println("usage: SelfHostServer <port> <token>")
            kotlin.system.exitProcess(2)
        }
        val port = args[0].toIntOrNull() ?: kotlin.system.exitProcess(2)
        val token = args[1]
        val server = try {
            ServerSocket(port, 8, InetAddress.getLoopbackAddress())
        } catch (e: Exception) {
            System.err.println("bind failed: $e")
            kotlin.system.exitProcess(1)
        }
        server.soTimeout = 0
        while (true) {
            val client = try {
                server.accept()
            } catch (_: Exception) {
                continue
            }
            Thread({ handle(client, token) }, "jarvis-shell-conn").start()
        }
    }

    private fun handle(socket: Socket, token: String) {
        val kids = ArrayList<Process>()
        val torn = AtomicBoolean(false)
        try {
            socket.tcpNoDelay = true
            val input = DataInputStream(socket.getInputStream().buffered())
            val output = DataOutputStream(socket.getOutputStream().buffered())

            if (!authenticate(input, output, token)) return

            while (true) {
                when (input.readByte().toInt()) {
                    'P'.code -> {
                        writeFrame(output, ("{\"uid\":${android.os.Process.myUid()}}").toByteArray())
                    }
                    'E'.code -> {
                        val argv = readArgv(input) ?: break
                        runExec(output, argv, kids, torn)
                    }
                    'S'.code -> {
                        val argv = readArgv(input) ?: break
                        runStream(output, argv, kids, torn)
                        break // stream owns the connection until it ends
                    }
                    else -> break
                }
            }
        } catch (_: EOFException) {
            // client gone
        } catch (_: SocketException) {
            // client gone
        } catch (_: Exception) {
            // any protocol failure: drop the connection
        } finally {
            torn.set(true)
            kids.forEach { runCatching { it.destroy() } }
            runCatching { socket.close() }
        }
    }

    private fun authenticate(input: DataInputStream, output: DataOutputStream, token: String): Boolean {
        // Read until newline: "JARVIS/1 <token>"
        val line = StringBuilder()
        while (true) {
            val b = input.readByte().toInt()
            if (b == '\n'.code) break
            if (line.length > 256) return false
            line.append(b.toChar())
        }
        val expected = "$HANDSHAKE_PREFIX $token"
        val ok = line.toString() == expected
        output.writeByte(if (ok) 'K'.code else 'X'.code)
        output.flush()
        return ok
    }

    /** argv framing: [4-byte count][count x (4-byte len + bytes)] */
    internal fun writeArgv(out: DataOutputStream, argv: Array<out String>) {
        out.writeInt(argv.size)
        argv.forEach {
            val bytes = it.toByteArray(Charsets.UTF_8)
            out.writeInt(bytes.size)
            out.write(bytes)
        }
        out.flush()
    }

    private fun readArgv(input: DataInputStream): Array<String>? {
        val count = input.readInt()
        if (count !in 1..MAX_ARGS) return null
        return Array(count) {
            val len = input.readInt()
            if (len !in 0..MAX_ARG_BYTES) throw EOFException("argv length")
            String(ByteArray(len).also { input.readFully(it) }, Charsets.UTF_8)
        }
    }

    internal fun writeFrame(out: DataOutputStream, bytes: ByteArray) {
        out.writeInt(bytes.size)
        out.write(bytes)
        out.flush()
    }

    private fun runExec(out: DataOutputStream, argv: Array<String>, kids: ArrayList<Process>, torn: AtomicBoolean) {
        if (torn.get()) return
        val process = try {
            ProcessBuilder(*argv).directory(File("/")).start()
        } catch (_: Exception) {
            out.writeInt(EXIT_MARKER)
            writeFrame(out, "exit=-127;stderr=start_failed".toByteArray())
            return
        }
        synchronized(kids) { kids.add(process) }
        runCatching { process.outputStream.close() }
        // Drain stderr concurrently so a chatty child can never deadlock the pipe.
        val stderrBuf = java.io.ByteArrayOutputStream()
        Thread({
            val sink = ByteArray(8 * 1024)
            try {
                var total = 0
                while (true) {
                    val n = process.errorStream.read(sink)
                    if (n < 0) break
                    if (total < MAX_STDERR_BYTES) {
                        stderrBuf.write(sink, 0, n)
                        total += n
                    }
                }
            } catch (_: Exception) {}
        }, "jarvis-exec-stderr").apply { isDaemon = true }.start()

        var written = 0L
        val buf = ByteArray(32 * 1024)
        try {
            val stdout = process.inputStream
            while (true) {
                val n = stdout.read(buf)
                if (n < 0) break
                if (written + n > MAX_OUTPUT_BYTES) {
                    written = MAX_OUTPUT_BYTES.toLong() // stop streaming more, keep the exit path
                    break
                }
                out.writeInt(n)
                out.write(buf, 0, n)
                out.flush()
                written += n
            }
        } catch (_: Exception) {
            return // socket died - child killed by finally
        }
        val finished = try {
            process.waitFor(20, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) process.destroy()
        val exit = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1
        val result = "exit=$exit;stderr=${stderrBuf.toString("UTF-8").take(2048)}"
        // Protocol: EXIT_MARKER always terminates stdout, then the result frame.
        out.writeInt(EXIT_MARKER)
        writeFrame(out, result.toByteArray(Charsets.UTF_8))
        synchronized(kids) { kids.remove(process) }
    }

    private fun runStream(out: DataOutputStream, argv: Array<String>, kids: ArrayList<Process>, torn: AtomicBoolean) {
        if (torn.get()) return
        val process = try {
            ProcessBuilder(*argv).directory(File("/")).redirectErrorStream(false).start()
        } catch (_: Exception) {
            out.writeInt(EXIT_MARKER)
            out.flush()
            return
        }
        synchronized(kids) { kids.add(process) }
        runCatching { process.outputStream.close() }
        // stderr is dropped (bounded) so it can never pollute the stdout stream.
        Thread({
            val sink = ByteArray(8 * 1024)
            try { while (process.errorStream.read(sink) >= 0) { /* discard */ } } catch (_: Exception) {}
        }, "jarvis-stderr-sink").apply { isDaemon = true }.start()

        try {
            val buf = ByteArray(32 * 1024)
            val stdout = process.inputStream
            while (true) {
                val n = stdout.read(buf)
                if (n < 0) break
                out.writeInt(n)
                out.write(buf, 0, n)
                out.flush()
            }
        } catch (_: Exception) {
            // socket closed by client = stop requested
        }
        runCatching { process.destroy() }
        synchronized(kids) { kids.remove(process) }
        try {
            out.writeInt(EXIT_MARKER)
            out.flush()
        } catch (_: Exception) {}
    }
}
