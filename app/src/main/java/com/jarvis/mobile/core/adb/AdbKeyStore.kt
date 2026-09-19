package com.jarvis.mobile.core.adb

import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Per-install ADB keypair for the built-in (in-app) wireless-debugging path -
 * the "no other app" replacement for an external Shizuku install. Replaces
 * the hardcoded keys other ports of this technique ship: keys are generated
 * on first use and kept in the app's private storage, never bundled.
 *
 * Provides the three artifacts the ADB/TLS flow needs:
 *  - the Android public-key authorization line ("base64 spki name\0") carried
 *    inside the pairing PeerInfo - adbd stores it as an authorized key, which
 *    is exactly what makes every later TLS connect password-free;
 *  - the PKCS8 private key for the TLS 1.3 client handshake;
 *  - a self-signed X509 certificate for that key (adbd demands a client cert
 *    during the TLS handshake).
 */
object AdbKeyStore {

    private const val PRIV_FILE = "adb_key"
    private const val PUB_FILE = "adb_key.pub"
    private const val CERT_FILE = "adb_cert.der"
    private const val KEY_NAME = "jarvis@localhost"

    @Volatile private var cached: KeyPair? = null
    @Volatile private var cachedCert: X509Certificate? = null

    /** Load (or lazily create) the device-scoped keypair. Thread-safe. */
    @Synchronized
    fun keyPair(): KeyPair {
        cached?.let { return it }
        val dir = baseDir().apply { mkdirs() }
        val privFile = File(dir, PRIV_FILE)
        val pubFile = File(dir, PUB_FILE)
        val pair = if (privFile.isFile && pubFile.isFile) {
            try {
                val kf = KeyFactory.getInstance("RSA")
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(privFile.readText(), Base64.NO_WRAP)))
                val pub = kf.generatePublic(X509EncodedKeySpec(Base64.decode(pubFile.readText(), Base64.NO_WRAP)))
                KeyPair(pub, priv)
            } catch (_: Exception) {
                generateAndStore(dir, privFile, pubFile)
            }
        } else {
            generateAndStore(dir, privFile, pubFile)
        }
        cached = pair
        return pair
    }

    private fun generateAndStore(dir: File, privFile: File, pubFile: File): KeyPair {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        val pair = gen.generateKeyPair()
        privFile.writeText(Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP))
        pubFile.writeText(Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP))
        runCatching { privFile.setReadable(false, false); privFile.setReadable(true, true) }
        return pair
    }

    /**
     * The authorization line adbd must receive inside the pairing PeerInfo:
     * `base64(X509 SPKI) name\0` - identical format to a ~/.android/adbkey.pub
     * line. After a successful pairing, adbd trusts this key.
     */
    fun publicKeyLine(): String =
        Base64.encodeToString(keyPair().public.encoded, Base64.NO_WRAP) + " " + KEY_NAME + "\u0000"

    /** Self-signed X509 (DER) for the TLS client handshake - generated once per key. */
    @Synchronized
    fun certificate(): X509Certificate {
        cachedCert?.let { return it }
        val dir = baseDir().apply { mkdirs() }
        val certFile = File(dir, CERT_FILE)
        val cert = if (certFile.isFile) {
            try {
                CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(certFile.readBytes())) as X509Certificate
            } catch (_: Exception) {
                buildAndStore(certFile)
            }
        } else {
            buildAndStore(certFile)
        }
        cachedCert = cert
        return cert
    }

    private fun buildAndStore(certFile: File): X509Certificate {
        val pair = keyPair()
        val cert = SelfSignedCertBuilder.build(pair, "CN=jarvis")
        certFile.writeBytes(cert.encoded)
        return cert
    }

    private fun baseDir(): File {
        val dir = AdbKeyStoreDirs.dir ?: throw IllegalStateException("AdbKeyStoreDirs not initialized (app not booted?)")
        return File(dir, "adb")
    }

    fun resetForTests() {
        cached = null
        cachedCert = null
    }
}

/** Wiring point for the app's private storage; JarvisApp sets it at boot, tests may inject a temp dir. */
object AdbKeyStoreDirs {
    @Volatile var dir: java.io.File? = null
}

/**
 * Minimal DER builder + self-signed X509 v3 construction using only the
 * platform JDK (no BouncyCastle): TBSCertificate is hand-assembled and signed
 * with SHA256withRSA. The output must parse through the standard
 * CertificateFactory - which the unit tests verify.
 */
object SelfSignedCertBuilder {

    fun build(pair: KeyPair, cn: String): X509Certificate {
        val notBefore = utcTimeField(2024)
        val notAfter = utcTimeField(2150)

        val spki = pair.public.encoded // X509 SubjectPublicKeyInfo, already DER
        val cnDer = derSequence(derOid(2, 5, 4, 3) + derSet(derSequence(derUtf8(cn))))

        val tbs = derSequence(
            byteArrayOf(0xA0.toByte(), 0x03, 0x02, 0x01, 0x02), // [0] EXPLICIT version = v3 (INTEGER 2)
            derInteger(BigInteger.ONE), // serial
            derSequence(derOid(1, 2, 840, 113549, 1, 1, 11) + derNull()), // sha256WithRSA
            cnDer, // issuer
            derSequence(notBefore, notAfter), // validity
            cnDer, // subject
            spki,
        )
        val sig = Signature.getInstance("SHA256withRSA").apply {
            initSign(pair.private)
            update(tbs)
        }.sign()

        val cert = derSequence(
            tbs,
            derSequence(derOid(1, 2, 840, 113549, 1, 1, 11) + derNull()),
            derBitString(sig),
        )
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(cert)) as X509Certificate
    }

    private fun utcTimeField(year: Int): ByteArray {
        // UTCTime (two-digit year) covers 1950-2049; GeneralizedTime beyond.
        return if (year in 1950..2049) {
            tlv(0x17, String.format("%02d0101000000Z", year % 100).toByteArray(Charsets.US_ASCII))
        } else {
            tlv(0x18, String.format("%04d0101000000Z", year).toByteArray(Charsets.US_ASCII))
        }
    }

    // ------------------------------------------------------------------ DER

    private fun len(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), (n and 0xff).toByte())
    }

    private fun tlv(tag: Int, body: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + len(body.size) + body

    private fun derSequence(vararg parts: ByteArray): ByteArray {
        val body = parts.reduce { a, b -> a + b }
        return tlv(0x30, body)
    }

    private fun derSet(body: ByteArray): ByteArray = tlv(0x31, body)

    private fun derInteger(v: BigInteger): ByteArray = tlv(0x02, v.toByteArray())

    private fun derNull(): ByteArray = byteArrayOf(0x05, 0x00)

    private fun derBitString(body: ByteArray): ByteArray =
        tlv(0x03, byteArrayOf(0) + body)

    private fun derUtf8(s: String): ByteArray = tlv(0x0c, s.toByteArray(Charsets.UTF_8))

    private fun derOid(vararg arcs: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.write(arcs[0] * 40 + arcs[1])
        for (i in 2 until arcs.size) {
            var v = arcs[i]
            val tmp = ArrayList<Byte>(5)
            tmp.add((v and 0x7f).toByte())
            v = v shr 7
            while (v > 0) {
                tmp.add(0, ((v and 0x7f) or 0x80).toByte())
                v = v shr 7
            }
            tmp.forEach { d.write(it.toInt()) }
        }
        return tlv(0x06, out.toByteArray())
    }
}
