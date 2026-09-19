package com.jarvis.mobile.core.adb

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.jarvis.mobile.util.Logx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * App-side driver for the self-hosted privileged shell ([SelfHostServer]) -
 * the "Shizuku setup inside the app" story:
 *
 *  1. PAIR once (Android 11+): connect to the phone's own wireless-debugging
 *     pairing port, run the SPAKE2 handshake with the user's 6-digit code
 *     ([AdbPairing]) - adbd then permanently trusts our generated key.
 *     Both ports are auto-discovered via mDNS (NSD), so usually the only
 *     thing the user types is the pairing code.
 *  2. CONNECT: TLS-ADB to our own adbd ([AdbClient]) and launch
 *     [SelfHostServer] from our own APK via app_process - it now runs as
 *     uid 2000 (shell) and serves exec/stream over loopback with a per-start
 *     random token.
 *  3. USE: [exec]/[stream] - the same surface ShizukuShell exposes, so the
 *     precision touch recorder needs no external app anymore.
 *
 * The token lives only in memory; nothing sensitive is persisted.
 */
object SelfHostShell {

    private const val TAG = "selfhost"
    private const val PREFS = "selfhost_adb"
    private const val KEY_PAIRED = "paired"

    enum class Status { UNPAIRED, PAIRED, CONNECTING, READY, ERROR }

    data class SelfHostState(val status: Status = Status.UNPAIRED, val reason: String = "")

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(SelfHostState())
    val state: kotlinx.coroutines.flow.StateFlow<SelfHostState> = _state

    // session secrets - memory only
    private var serverPort: Int = 0
    private var token: String = ""
    private val connecting = AtomicBoolean(false)

    fun paired(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PAIRED, false)

    fun ready(): Boolean = _state.value.status == Status.READY

    // --------------------------------------------------------------- pairing

    /**
     * Pair with this device's own adbd. [manualPort] overrides mDNS discovery
     * (null = auto). [code] is the 6-digit pairing code from the Wireless
     * debugging dialog.
     */
    suspend fun pair(context: Context, manualPort: Int?, code: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanCode = code.filter { it.isDigit() }
            require(cleanCode.length == 6) { "Pairing code must be 6 digits" }
            val port = manualPort
                ?: resolvePort(context, TYPE_PAIRING, timeoutMs = 8_000)
                ?: error("Could not find the pairing port - make sure the pairing dialog is open, or type the port")
            val pairer = AdbPairing("127.0.0.1", port, cleanCode)
            try {
                pairer.start()
            } finally {
                runCatching { pairer.close() }
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PAIRED, true).apply()
            _state.value = SelfHostState(Status.PAIRED, "paired - key stored")
            Logx.i(TAG, "wireless pairing OK (port $port)")
        }.onFailure {
            _state.value = SelfHostState(if (paired(context)) Status.PAIRED else Status.UNPAIRED, "pair failed: ${it.message?.take(120)}")
            Logx.w(TAG, "pairing failed: ${it.message}")
        }
    }

    // -------------------------------------------------------------- connect

    /**
     * Connect to our own adbd and start (or reuse) the self-host server.
     * [manualConnectPort] overrides mDNS discovery for the adb port.
     */
    suspend fun connect(context: Context, manualConnectPort: Int? = null): Result<Unit> = withContext(Dispatchers.IO) {
        if (!connecting.compareAndSet(false, true)) {
            return@withContext Result.failure(IllegalStateException("connect already running"))
        }
        _state.value = SelfHostState(Status.CONNECTING, "connecting…")
        try {
            val result = runCatching { connectLocked(context, manualConnectPort) }
            result
                .onSuccess {
                    _state.value = SelfHostState(Status.READY, "privileged shell ready (uid 2000, built-in)")
                    Logx.i(TAG, "self-host shell ready on 127.0.0.1:$serverPort")
                }
                .onFailure {
                    _state.value = SelfHostState(
                        if (paired(context)) Status.PAIRED else Status.UNPAIRED,
                        "connect failed: ${it.message?.take(140)}",
                    )
                    Logx.w(TAG, "connect failed: ${it.message}")
                }
        } finally {
            connecting.set(false)
        }
    }

    private suspend fun connectLocked(context: Context, manualConnectPort: Int?) {
        val adbPort = manualConnectPort
            ?: resolvePort(context, TYPE_CONNECT, timeoutMs = 6_000)
            ?: error("adb port not found - is Wireless debugging ON? Type its port manually if needed")

        val adb = AdbClient.connectSelf("127.0.0.1", adbPort)
        try {
            // Kill any stale server from a previous session (its token is dead anyway).
            runCatching { adb.execForOutput("pkill -f com.jarvis.mobile.core.adb.SelfHostServer", 5_000) }

            val pmOut = adb.execForOutput("pm path ${context.packageName}", 10_000)
            val apkPath = pmOut.lineSequence().firstOrNull { it.startsWith("package:") }?.removePrefix("package:")?.trim()
                ?: error("pm path returned nothing (unexpected)")

            repeat(4) { attempt ->
                val port = 20_000 + SecureRandom().nextInt(40_000)
                val newToken = randomToken()
                val startCmd =
                    "nohup setsid sh -c 'CLASSPATH=$apkPath app_process / --nice-name=jarvis-shell " +
                        "com.jarvis.mobile.core.adb.SelfHostServer $port $newToken' >/dev/null 2>&1 &"
                runCatching { adb.execForOutput(startCmd, 6_000) }
                delay(500L + attempt * 300L)
                val session = handshake(port, newToken)
                if (session != null) {
                    serverPort = port
                    token = newToken
                    session.close()
                    return
                }
            }
            error("could not reach the shell server (app_process failed?)")
        } finally {
            runCatching { adb.close() }
        }
    }

    private fun randomToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Open a session socket and complete the token handshake. */
    private fun handshake(port: Int, tok: String): Socket? = runCatching {
        val s = Socket()
        s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2_000)
        s.soTimeout = 5_000
        s.tcpNoDelay = true
        s.getOutputStream().write("JARVIS/1 $tok\n".toByteArray(Charsets.UTF_8))
        s.getOutputStream().flush()
        val resp = s.getInputStream().read()
        if (resp != 'K'.code) {
            runCatching { s.close() }
            return null
        }
        s
    }.getOrNull()

    // ------------------------------------------------------------------ exec

    data class ShellResult(val exit: Int, val stdout: String, val stderr: String)

    /** One command through the self-host server. Returns null when the server is unreachable. */
    suspend fun exec(vararg argv: String, timeoutMs: Long = 20_000): ShellResult? = withContext(Dispatchers.IO) {
        if (_state.value.status != Status.READY) return@withContext null
        runCatching {
            val s = newSession() ?: return@withContext null
            try {
                val out = DataOutputStream(s.getOutputStream().buffered())
                out.writeByte('E'.code)
                SelfHostServer.writeArgv(out, argv)
                val input = DataInputStream(s.getInputStream().buffered())
                val stdout = StringBuilder()
                var stderr = ""
                var exit = -1
                val deadline = System.currentTimeMillis() + timeoutMs
                // stdout chunks until EXIT_MARKER, then exactly one result frame
                while (System.currentTimeMillis() < deadline) {
                    val len = input.readInt()
                    if (len == SelfHostServer.EXIT_MARKER) break
                    val bytes = ByteArray(len).also { input.readFully(it) }
                    stdout.append(String(bytes, Charsets.UTF_8))
                }
                if (System.currentTimeMillis() < deadline) {
                    val resultLen = input.readInt()
                    val result = String(ByteArray(resultLen).also { input.readFully(it) }, Charsets.UTF_8)
                    exit = result.removePrefix("exit=").substringBefore(';').toIntOrNull() ?: -1
                    stderr = result.substringAfter("stderr=", "")
                }
                ShellResult(exit, stdout.toString(), stderr)
            } finally {
                runCatching { s.close() }
            }
        }.getOrNull()
    }

    // ---------------------------------------------------------------- stream

    class StreamHandle(private val socket: Socket) {
        /** Stop the stream: closing the socket makes the server kill the child process. */
        fun stop() {
            runCatching { socket.close() }
        }
    }

    /**
     * Continuous command stream (getevent). Returns a line reader + a handle
     * whose [StreamHandle.stop] tears the command down.
     */
    suspend fun stream(vararg argv: String): Pair<BufferedReader, StreamHandle>? = withContext(Dispatchers.IO) {
        if (_state.value.status != Status.READY) return@withContext null
        runCatching {
            val s = newSession() ?: return@withContext null
            s.soTimeout = 0
            val out = DataOutputStream(s.getOutputStream().buffered())
            out.writeByte('S'.code)
            SelfHostServer.writeArgv(out, argv)
            // Skip the protocol framing lazily: a reader that strips the 4-byte
            // length prefixes and yields lines.
            Pair(FramedReader(DataInputStream(s.getInputStream().buffered())), StreamHandle(s))
        }.getOrNull()
    }

    /**
     * Wraps the socket stream, stripping length prefixes; the reader exposes
     * lines to the same callers ShizukuShell served before.
     */
    class FramedReader(private val input: DataInputStream) : BufferedReader(InputStreamReader(object : java.io.InputStream() {
        private val buf = ByteArray(32 * 1024)
        private var pos = 0
        private var end = 0

        private fun fill(): Boolean {
            if (pos < end) return true
            // next chunk: [4-byte len][bytes]; EXIT_MARKER ends the stream
            val len = try {
                input.readInt()
            } catch (_: Exception) {
                return false
            }
            if (len == SelfHostServer.EXIT_MARKER) return false
            if (len <= 0 || len > buf.size) return false
            try {
                input.readFully(buf, 0, len)
            } catch (_: Exception) {
                return false
            }
            pos = 0
            end = len
            return true
        }

        override fun read(): Int {
            if (!fill()) return -1
            return buf[pos++].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, ln: Int): Int {
            if (!fill()) return -1
            val n = minOf(ln, end - pos)
            System.arraycopy(buf, pos, b, off, n)
            pos += n
            return n
        }
    }))

    // --------------------------------------------------------------- session

    private fun newSession(): Socket? {
        if (serverPort == 0) return null
        return runCatching {
            val s = Socket()
            s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), serverPort), 2_000)
            s.tcpNoDelay = true
            s.getOutputStream().write("JARVIS/1 $token\n".toByteArray())
            s.getOutputStream().flush()
            if (s.getInputStream().read() != 'K'.code) {
                runCatching { s.close() }
                return null
            }
            s
        }.getOrNull()
    }

    fun disconnect() {
        _state.value = SelfHostState(Status.PAIRED, "disconnected")
    }

    // ------------------------------------------------------------------- NSD

    private const val TYPE_PAIRING = "_adb-tls-pairing._tcp"
    private const val TYPE_CONNECT = "_adb-tls-connect._tcp"

    /**
     * mDNS port discovery for the phone's OWN adb services (the same
     * advertisements the Wireless debugging screen shows). Adapted from
     * wuyr/jdwp-injector-for-android (Apache-2.0).
     */
    @SuppressLint("NewApi")
    private suspend fun resolvePort(context: Context, serviceType: String, timeoutMs: Long): Int? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                if (!cont.isActive) return@suspendCancellableCoroutine
                val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
                if (nsd == null) {
                    if (cont.isActive) cont.resume(null)
                    return@suspendCancellableCoroutine
                }
                val resolved = AtomicBoolean(false)
                val listener = object : NsdManager.DiscoveryListener {
                    override fun onServiceFound(info: NsdServiceInfo) {
                        if (resolved.get()) return
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            nsd.registerServiceInfoCallback(info, { it.run() }, object : NsdManager.ServiceInfoCallback {
                                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}
                                override fun onServiceUpdated(updated: NsdServiceInfo) {
                                    if (resolved.compareAndSet(false, true)) {
                                        if (cont.isActive) cont.resume(updated.port)
                                        nsd.unregisterServiceInfoCallback(this)
                                    }
                                }
                                override fun onServiceLost() {}
                                override fun onServiceInfoCallbackUnregistered() {}
                            })
                        } else {
                            @Suppress("DEPRECATION")
                            nsd.resolveService(info, object : NsdManager.ResolveListener {
                                override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {}
                                override fun onServiceResolved(info: NsdServiceInfo?) {
                                    if (resolved.compareAndSet(false, true)) {
                                        if (cont.isActive) cont.resume(info?.port)
                                    }
                                }
                            })
                        }
                    }

                    override fun onServiceLost(info: NsdServiceInfo?) {}
                    override fun onStartDiscoveryFailed(type: String?, errorCode: Int) {
                        if (resolved.compareAndSet(false, true)) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                    override fun onStopDiscoveryFailed(type: String?, errorCode: Int) {}
                    override fun onDiscoveryStarted(type: String?) {}
                    override fun onDiscoveryStopped(type: String?) {}
                }
                cont.invokeOnCancellation { runCatching { nsd.stopServiceDiscovery(listener) } }
                runCatching { nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener) }
                    .onFailure {
                        if (resolved.compareAndSet(false, true) && cont.isActive) cont.resume(null)
                    }
            }
        }
}
