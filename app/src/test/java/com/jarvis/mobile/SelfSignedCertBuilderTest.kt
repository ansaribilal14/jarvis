package com.jarvis.mobile

import com.jarvis.mobile.core.adb.SelfSignedCertBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec

/**
 * The self-signed X509 identity for the built-in (in-app) ADB path: it must
 * parse through the platform CertificateFactory (adbd + Conscrypt will do the
 * same) and carry a valid SHA256withRSA signature over the TBS bytes.
 */
class SelfSignedCertBuilderTest {

    private fun newPair() = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    @Test
    fun `certificate parses through standard CertificateFactory`() {
        val cert = SelfSignedCertBuilder.build(newPair(), "CN=jarvis")
        val reparsed = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(cert.encoded)) as java.security.cert.X509Certificate
        assertEquals(cert.subjectX500Principal, reparsed.subjectX500Principal)
        assertEquals("CN=jarvis", cert.subjectX500Principal.name)
    }

    @Test
    fun `signature verifies against the public key`() {
        val pair = newPair()
        val cert = SelfSignedCertBuilder.build(pair, "CN=jarvis")
        assertNotNull(cert.signature)
        // The TBS bytes are recoverable: re-sign semantics checked via cert.verify.
        cert.verify(pair.public)
    }

    @Test
    fun `certificate is v3 with long validity`() {
        val cert = SelfSignedCertBuilder.build(newPair(), "CN=jarvis")
        assertEquals(3, cert.version)
        assertTrue(cert.notAfter.after(cert.notBefore))
        // usable for decades - a phone's ADB identity never needs rotation
        val endYear = java.util.Calendar.getInstance().apply { time = cert.notAfter }.get(java.util.Calendar.YEAR)
        assertTrue(endYear > 2100)
    }

    @Test
    fun `different keys produce different certificates`() {
        val a = SelfSignedCertBuilder.build(newPair(), "CN=jarvis")
        val b = SelfSignedCertBuilder.build(newPair(), "CN=jarvis")
        assertTrue(!a.publicKey.equals(b.publicKey))
        // same subject, different keys => different certs
        assertTrue(!a.encoded.contentEquals(b.encoded))
    }

    @Test
    fun `private key material round-trips through PKCS8`() {
        val pair = newPair()
        val restored = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pair.private.encoded))
        assertArrayEquals(pair.private.encoded, restored.encoded)
        val data = ByteArray(64) { it.toByte() }
        val sig = Signature.getInstance("SHA256withRSA").apply { initSign(restored); update(data) }.sign()
        Signature.getInstance("SHA256withRSA").apply { initVerify(pair.public); update(data) }.verify(sig)
    }
}
