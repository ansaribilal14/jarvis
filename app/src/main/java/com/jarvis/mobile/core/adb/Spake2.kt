package com.jarvis.mobile.core.adb

import androidx.annotation.Keep

/**
 * SPAKE2 (BoringSSL) JNI bridge for the Android 11+ wireless-debugging
 * pairing handshake. Native side lives in spake2_jni.cpp and links the
 * boringssl prefab crypto library - the same proven stack Shizuku uses for
 * its own pairing. The derived 16-byte key material is the AES-128-GCM key
 * for the pairing PeerInfo exchange (HKDF-SHA256, AOSP pairing_auth info
 * string).
 *
 * Thread-safety: one instance per pairing attempt; not reusable after
 * [processMessage].
 */
@Keep
class Spake2(password: ByteArray) {

    companion object {
        init {
            runCatching { System.loadLibrary("jarvis_spake2") }
                .onFailure { throw IllegalStateException("jarvis_spake2 native library missing", it) }
        }

        /** BoringSSL SPAKE2_MAX_MSG_SIZE for P-256. */
        const val MAX_MSG_SIZE = 97
        const val KEY_SIZE = 16
    }

    @Keep
    private class Ctx(val ptr: Long)

    private val ctx: Ctx
    /** Our SPAKE2 public message (32 bytes) - sent first in the handshake. */
    val message: ByteArray = ByteArray(32)

    init {
        val ptr = nativeCreate(password, message)
        if (ptr == 0L) error("spake2 init failed")
        ctx = Ctx(ptr)
    }

    /** Feed the peer's SPAKE2 message; returns the 16-byte AES key material. */
    fun processMessage(theirMessage: ByteArray): ByteArray {
        val out = nativeProcessMessage(ctx.ptr, theirMessage)
        if (out == null || out.size != KEY_SIZE) error("spake2 key derivation failed")
        return out
    }

    fun destroy() {
        runCatching { nativeDestroy(ctx.ptr) }
    }

    private external fun nativeCreate(password: ByteArray, messageOut: ByteArray): Long
    private external fun nativeProcessMessage(ptr: Long, theirMessage: ByteArray): ByteArray?
    private external fun nativeDestroy(ptr: Long)
}
