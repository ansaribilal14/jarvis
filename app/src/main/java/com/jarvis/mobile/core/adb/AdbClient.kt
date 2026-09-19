package com.jarvis.mobile.core.adb

import java.io.Closeable
import java.io.DataInputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager

/**
 * Minimal ADB protocol client speaking to THIS device's own adbd over the
 * wireless-debugging TLS port (Android 11+): CNXN -> STLS -> TLS 1.3 (client
 * cert = our generated key) -> CNXN, then `exec:` services for one-shot
 * commands. Used by [SelfHostShell] to start JARVIS's own shell-identity
 * server - the Shizuku capability, self-contained.
 *
 * Protocol constants and framing adapted from
 * wuyr/jdwp-injector-for-android (Apache-2.0); keys/certs are ours (runtime
 * generated, see [AdbKeyStore]).
 */
class AdbClient private constructor(
    private val host: String,
    private val port: Int,
    private val connectTimeoutMs: Int,
) : Closeable {

    private var socket: Socket? = null
    private lateinit var input: DataInputStream
    private lateinit var output: OutputStream
    private var localId = 0
    private var remoteId = 0

    // ------------------------------------------------------------- connect

    fun connect() {
        val tcp = Socket().apply {
            tcpNoDelay = true
            soTimeout = connectTimeoutMs
        }
        tcp.connect(java.net.InetSocketAddress(host, port), connectTimeoutMs)
        socket = tcp
        input = DataInputStream(tcp.getInputStream().buffered())
        output = tcp.getOutputStream().buffered()

        writeFrame(A_CNXN, VERSION, MAX_PAYLOAD, EMPTY)
        val first = readFrame()
        if (first.cmd != A_CNXN) {
            if (first.cmd == A_STLS) {
                upgradeToTls(first)
            } else {
                throw AdbException("unexpected adbd response (cmd=0x${Integer.toHexString(first.cmd)})")
            }
        }
    }

    /** adbd asked for TLS: send STLS-VERSION, wrap the socket in TLS 1.3, CNXN again. */
    private fun upgradeToTls(stls: Frame) {
        if (stls.arg0 < STLS_VERSION) throw AdbException("adbd TLS version too old: ${stls.arg0}")
        writeFrame(A_STLS, STLS_VERSION, 0, EMPTY)
        val raw = socket ?: throw AdbException("socket gone")
        val ssl = SSLContext.getInstance("TLSv1.3").run {
            init(arrayOf(ClientKeyManager), arrayOf(TrustAllManager), SecureRandom())
            socketFactory.createSocket(raw, host, port, true) as SSLSocket
        }.apply {
            tcpNoDelay = true
            soTimeout = connectTimeoutMs
            startHandshake()
        }
        socket = ssl
        input = DataInputStream(ssl.getInputStream().buffered())
        output = ssl.getOutputStream().buffered()
        writeFrame(A_CNXN, VERSION, MAX_PAYLOAD, EMPTY)
        val reply = readFrame()
        if (reply.cmd != A_CNXN) throw AdbException("TLS connect rejected (cmd=0x${Integer.toHexString(reply.cmd)})")
    }

    // ---------------------------------------------------------------- exec

    /**
     * Run one command through the `exec:` service (no PTY, stream closes on
     * exit). Returns the full output. Safe one-liners only - arguments are
     * passed through `sh -c` semantics, so quote them.
     */
    fun execForOutput(command: String, timeoutMs: Long = 15_000L): String {
        val stream = open("exec:$command")
        return try {
            stream.readAll(timeoutMs)
        } finally {
            runCatching { stream.close() }
        }
    }

    class AdbStream internal constructor(
        private val client: AdbClient,
        private val timeoutMs: Long,
    ) {
        private val buffer = StringBuilder()
        @Volatile private var closed = false
        var exitClean = true
            private set

        internal fun pump() {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!closed && System.currentTimeMillis() < deadline) {
                val frame = try {
                    client.readFrame()
                } catch (_: Exception) {
                    closed = true
                    break
                }
                when (frame.cmd) {
                    A_WRTE -> {
                        buffer.append(String(frame.payload, Charsets.UTF_8))
                        client.writeFrame(A_OKAY, client.localId, client.remoteId, EMPTY)
                    }
                    A_CLSE -> closed = true
                    A_OKAY -> Unit // stream accepted
                    else -> closed = true
                }
            }
            if (!closed) exitClean = false
        }

        fun readAll(timeoutMs: Long): String {
            pump()
            return buffer.toString()
        }

        fun close() {
            closed = true
            runCatching { client.writeFrame(A_CLSE, client.localId, client.remoteId, EMPTY) }
        }
    }

    fun open(destination: String, timeoutMs: Long = 30_000L): AdbStream {
        localId += 1
        remoteId = 0
        writeFrame(A_OPEN, localId, 0, destination.toByteArray(Charsets.UTF_8))
        val reply = readFrame()
        if (reply.cmd != A_OKAY) throw AdbException("open '$destination' rejected (cmd=0x${Integer.toHexString(reply.cmd)})")
        remoteId = reply.arg0
        return AdbStream(this, timeoutMs)
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    // ------------------------------------------------------------- framing

    internal data class Frame(val cmd: Int, val arg0: Int, val arg1: Int, val payload: ByteArray)

    internal fun writeFrame(cmd: Int, arg0: Int, arg1: Int, payload: ByteArray) {
        val out = socket?.getOutputStream() ?: throw AdbException("socket closed")
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(cmd).putInt(arg0).putInt(arg1).putInt(payload.size)
            putInt(payload.sumOf { it.toInt() and 0xff }).putInt(cmd xor 0xffffffffL.toInt())
        }.array()
        synchronized(out) {
            out.write(header)
            if (payload.isNotEmpty()) out.write(payload)
            out.flush()
        }
    }

    internal fun readFrame(): Frame {
        val header = ByteArray(24)
        input.readFully(header)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = buf.int
        val arg0 = buf.int
        val arg1 = buf.int
        val len = buf.int
        val checksum = buf.int
        val magic = buf.int
        if (magic != cmd xor 0xffffffffL.toInt()) throw AdbException("adb frame magic mismatch")
        val payload = if (len > 0) ByteArray(len).also { input.readFully(it) } else EMPTY
        if (len > 0 && payload.sumOf { it.toInt() and 0xff } != checksum) {
            throw AdbException("adb frame checksum mismatch")
        }
        return Frame(cmd, arg0, arg1, payload)
    }

    // ------------------------------------------------- TLS client identity

    private object ClientKeyManager : X509ExtendedKeyManager() {
        private val cert = AdbKeyStore.certificate()

        private val privateKey: PrivateKey by lazy {
            KeyFactory.getInstance("RSA").generatePrivate(
                PKCS8EncodedKeySpec(AdbKeyStore.keyPair().private.encoded),
            )
        }

        override fun chooseClientAlias(keyTypes: Array<out String>?, issuers: Array<out Principal>?, socket: java.net.Socket?): String = "jarvis"

        override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(cert)

        override fun getPrivateKey(alias: String?): PrivateKey = privateKey

        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf("jarvis")
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: java.net.Socket?): String? = null
    }

    companion object {
        const val VERSION = 0x01000001
        const val MAX_PAYLOAD = 256 * 1024
        const val STLS_VERSION = 1

        const val A_CNXN = 0x4e584e43
        const val A_AUTH = 0x48545541
        const val A_STLS = 0x534c5453
        const val A_OKAY = 0x59414b4f
        const val A_CLSE = 0x45534c43
        const val A_WRTE = 0x45545257
        const val A_OPEN = 0x4e45504f

        private val EMPTY = ByteArray(0)

        /** Connect to this device's own adbd (localhost). Retries once on transient IO errors. */
        fun connectSelf(host: String, port: Int, connectTimeoutMs: Int = 4_000): AdbClient {
            var lastError: Exception? = null
            repeat(2) { attempt ->
                try {
                    return AdbClient(host, port, connectTimeoutMs).also { it.connect() }
                } catch (e: javax.net.ssl.SSLProtocolException) {
                    throw AdbException("TLS handshake failed - pairing required first", e)
                } catch (e: Exception) {
                    lastError = e
                    if (attempt == 1) throw e
                }
            }
            throw lastError ?: AdbException("connect failed")
        }
    }
}

class AdbException(message: String, cause: Throwable? = null) : Exception(message, cause)
