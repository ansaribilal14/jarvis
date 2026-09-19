/*
 * SPAKE2 + pairing_auth key derivation over BoringSSL - the native half of
 * the in-app wireless-debugging pairing (Android 11+).
 *
 * Adapted from wuyr/jdwp-injector-for-android (Apache-2.0) and RikkaApps/
 * Shizuku manager adb_pairing.cpp (Apache-2.0): SPAKE2_CTX over the pairing
 * code, then HKDF-SHA256 with the AOSP pairing_auth info string to derive
 * the AES-128-GCM key used for the PeerInfo exchange.
 */
#include <jni.h>
#include <cstring>
#include <openssl/curve25519.h>
#include <openssl/hkdf.h>
#include <openssl/evp.h>

static const uint8_t kClientName[] = "adb pair client";
static const uint8_t kServerName[] = "adb pair server";
static const uint8_t kHkdfInfo[] = "adb pairing_auth aes-128-gcm key";

extern "C" JNIEXPORT jlong JNICALL
Java_com_jarvis_mobile_core_adb_Spake2_nativeCreate(JNIEnv *env, jobject, jbyteArray jPassword, jbyteArray jMessageOut) {
    jbyte *password = env->GetByteArrayElements(jPassword, nullptr);
    jsize passwordLen = env->GetArrayLength(jPassword);
    SPAKE2_CTX *ctx = SPAKE2_CTX_new(spake2_role_alice,
                                     kClientName, sizeof(kClientName) - 1,
                                     kServerName, sizeof(kServerName) - 1);
    if (ctx == nullptr) {
        env->ReleaseByteArrayElements(jPassword, password, 0);
        return 0;
    }
    uint8_t key[SPAKE2_MAX_MSG_SIZE];
    size_t keyLen = 0;
    int ok = SPAKE2_generate_msg(ctx, key, &keyLen, sizeof(key),
                                 reinterpret_cast<const uint8_t *>(password), static_cast<size_t>(passwordLen));
    env->ReleaseByteArrayElements(jPassword, password, 0);
    if (ok != 1 || keyLen == 0 || keyLen > sizeof(key)) {
        SPAKE2_CTX_free(ctx);
        return 0;
    }
    jsize outLen = static_cast<jsize>(keyLen);
    if (outLen != 32) { // adbd expects the 32-byte P-256 message
        SPAKE2_CTX_free(ctx);
        return 0;
    }
    env->SetByteArrayRegion(jMessageOut, 0, outLen, reinterpret_cast<const jbyte *>(key));
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_jarvis_mobile_core_adb_Spake2_nativeProcessMessage(JNIEnv *env, jobject, jlong ctxPtr, jbyteArray jTheirMessage) {
    if (ctxPtr == 0) return nullptr;
    jbyte *their = env->GetByteArrayElements(jTheirMessage, nullptr);
    jsize theirLen = env->GetArrayLength(jTheirMessage);
    if (theirLen <= 0 || static_cast<size_t>(theirLen) > SPAKE2_MAX_MSG_SIZE) {
        env->ReleaseByteArrayElements(jTheirMessage, their, 0);
        return nullptr;
    }
    SPAKE2_CTX *ctx = reinterpret_cast<SPAKE2_CTX *>(ctxPtr);
    uint8_t keyMaterial[SPAKE2_MAX_KEY_SIZE];
    size_t keyMaterialLen = 0;
    int ok = SPAKE2_process_msg(ctx, keyMaterial, &keyMaterialLen, sizeof(keyMaterial),
                                reinterpret_cast<const uint8_t *>(their), static_cast<size_t>(theirLen));
    env->ReleaseByteArrayElements(jTheirMessage, their, 0);
    if (ok != 1 || keyMaterialLen == 0) {
        return nullptr;
    }
    uint8_t aesKey[16];
    if (!HKDF(aesKey, sizeof(aesKey), EVP_sha256(), keyMaterial, keyMaterialLen,
              nullptr, 0, kHkdfInfo, sizeof(kHkdfInfo) - 1)) {
        return nullptr;
    }
    jbyteArray out = env->NewByteArray(16);
    env->SetByteArrayRegion(out, 0, 16, reinterpret_cast<const jbyte *>(aesKey));
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_com_jarvis_mobile_core_adb_Spake2_nativeDestroy(JNIEnv *, jobject, jlong ctxPtr) {
    if (ctxPtr != 0) {
        SPAKE2_CTX_free(reinterpret_cast<SPAKE2_CTX *>(ctxPtr));
    }
}
