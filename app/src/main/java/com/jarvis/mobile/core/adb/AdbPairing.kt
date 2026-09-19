package com.jarvis.mobile.core.adb

import android.net.ssl.SSLSockets
import android.os.Build
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocket

/**
 * Android 11+ wireless-debugging PAIRING handshake (the "Pair device with
 * pairing code" protocol), run in-app so no external Shizuku app is needed.
 *
 * Sequence (AOSP pairing_connection):
 *  1. TLS-connect to the ephemeral pairing port; export 64 bytes of TLS
 *     keying material ("adb-label") - the SPAKE2 password is
 *     `pairingCode + keyingMaterial`;
 *  2. exchange SPAKE2 messages (version-1 header, type 0);
 *  3. exchange PeerInfo packets encrypted with the derived AES-128-GCM key -
 *     ours carries [AdbKeyStore.publicKeyLine], which adbd stores as an
 *     authorized key for every later TLS connection.
 *
 * Adapted from wuyr/jdwp-injector-for-android (Apache-2.0).
 */
class AdbPairing(
    private val host: String,
    private val port: Int,
    private val pairingCode: String,
) : Closeable {

    private lateinit var socket: SSLSocket
    private lateinit var inputStream: DataInputStream
    private lateinit var outputStream: DataOutputStream
    private lateinit var spake2: Spake2

    /** Runs the full handshake; throws with a user-readable message on failure. */
    fun start() {
        setupTlsConnection()
        val secretKey = doExchangeMessages()
        doExchangePeerInfo(secretKey)
    }

    private fun setupTlsConnection() {
        socket = Socket(host, port).run {
            tcpNoDelay = true
            createSslSocket()
        }.apply {
            this@AdbPairing.inputStream = DataInputStream(inputStream)
            this@AdbPairing.outputStream = DataOutputStream(outputStream)
        }
        // Setup the SPAKE2 password: pairing code + TLS exporter output.
        val keyingMaterial = exportKeyingMaterial()
            ?: throw PairingException("unable to export TLS keying material")
        val password = ByteArray(pairingCode.length + keyingMaterial.size).apply {
            pairingCode.toByteArray(Charsets.US_ASCII).copyInto(this)
            keyingMaterial.copyInto(this, pairingCode.length)
        }
        spake2 = Spake2(password)
    }

    private fun exportKeyingMaterial(): ByteArray? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SSLSockets.exportKeyingMaterial(socket, "adb-label\u0000", null, 64)
        } else {
            // API 30: Conscrypt exposes the exporter, but as a hidden API.
            HiddenApiBypass.exemptAll()
            Class.forName("com.android.org.conscrypt.Conscrypt")
                .getMethod(
                    "exportKeyingMaterial",
                    SSLSocket::class.java,
                    String::class.java,
                    ByteArray::class.java,
                    Int::class.java,
                )
                .invoke(null, socket, "adb-label\u0000", null, 64) as? ByteArray
        }
    }.getOrNull()

    private fun doExchangeMessages(): ByteArray {
        // Write our SPAKE2 msg: [version=1][type=0][len]
        outputStream.write(
            ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN).apply {
                put(1).put(0).putInt(spake2.message.size)
            }.array(),
        )
        outputStream.write(spake2.message)
        outputStream.flush()

        val (version, type, payload) = readHeader()
        if (version != 1) throw PairingException("header version mismatch (us=1 them=$version)")
        if (type != 0) throw PairingException("header type mismatch (expected 0, got $type)")
        if (payload == 0 || payload > MAX_PAYLOAD) throw PairingException("unsafe payload size: $payload")

        val theirMessage = ByteArray(payload).apply { inputStream.readFully(this) }
        return spake2.processMessage(theirMessage)
    }

    private fun doExchangePeerInfo(secretKey: ByteArray) {
        // PeerInfo: 8192-byte payload, our adb public-key line at the front.
        val line = AdbKeyStore.publicKeyLine().toByteArray(Charsets.UTF_8)
        val peerInfoPayload = ByteArray(PEER_INFO_SIZE)
        line.copyInto(peerInfoPayload, 0, 0, line.size.coerceAtMost(PEER_INFO_SIZE))

        val encrypted = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(secretKey, "AES"), GCMParameterSpec(128, ByteArray(12)))
            doFinal(peerInfoPayload)
        }
        outputStream.write(
            ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN).apply {
                put(1).put(1).putInt(encrypted.size)
            }.array(),
        )
        outputStream.write(encrypted)
        outputStream.flush()

        val (version, type, payload) = readHeader()
        if (version != 1) throw PairingException("peer header version mismatch (us=1 them=$version)")
        if (type != 1) throw PairingException("peer header type mismatch (expected 1, got $type)")
        if (payload == 0 || payload > MAX_PAYLOAD) throw PairingException("unsafe peer payload size: $payload")

        val theirEncrypted = ByteArray(payload).apply { inputStream.readFully(this) }
        val decrypted = runCatching {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(secretKey, "AES"), GCMParameterSpec(128, ByteArray(12)))
                doFinal(theirEncrypted)
            }
        }.getOrElse { throw PairingException("failed to decrypt the device certificate") }
        if (decrypted == null || decrypted.size != PEER_INFO_SIZE) {
            throw PairingException("device PeerInfo has unexpected size (${decrypted?.size})")
        }
    }

    private fun readHeader(): Triple<Int, Int, Int> {
        val header = ByteArray(6).apply { inputStream.readFully(this) }
        val buf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        return Triple(buf.get().toInt(), buf.get().toInt(), buf.int)
    }

    private fun Socket.createSslSocket(): SSLSocket =
        javax.net.ssl.SSLContext.getInstance("TLSv1.3").run {
            init(null, arrayOf(TrustAllManager), java.security.SecureRandom())
            (socketFactory.createSocket(this@createSslSocket, host, port, true) as SSLSocket).apply {
                tcpNoDelay = true
                startHandshake()
            }
        }

    override fun close() {
        if (::socket.isInitialized) runCatching { socket.close() }
        if (::spake2.isInitialized) runCatching { spake2.destroy() }
    }

    companion object {
        private const val PEER_INFO_SIZE = 8192
        private const val MAX_PAYLOAD = 16384
    }
}

/** adbd presents a self-signed certificate; identity comes from the pairing itself. */
object TrustAllManager : javax.net.ssl.X509TrustManager {
    override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
}

class PairingException(message: String) : Exception(message)

/**
 * Hidden-API exemption helper (meta-reflection on VMRuntime) - only needed on
 * Android 11 (API 30) to reach Conscrypt's TLS keying-material exporter.
 */
object HiddenApiBypass {
    @Volatile private var done = false

    @Synchronized
    fun exemptAll() {
        if (done) return
        runCatching {
            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
            val getRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime")
            getRuntime.isAccessible = true
            val vmRuntime = getRuntime.invoke(null)
            val setExemptions = vmRuntimeClass.getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java)
            setExemptions.isAccessible = true
            setExemptions.invoke(vmRuntime, arrayOf("L"))
            done = true
        }
    }
}
