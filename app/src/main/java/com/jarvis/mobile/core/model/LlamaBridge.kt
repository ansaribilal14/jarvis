package com.jarvis.mobile.core.model

/**
 * JNI boundary to the native llama.cpp runtime (libjarvis_llama.so).
 * Kept minimal and isolated per the native inference boundary requirement.
 */
object LlamaBridge {
    init {
        System.loadLibrary("jarvis_llama")
    }

    external fun nativeLoadModel(path: String, contextSize: Int, threads: Int): Boolean
    external fun nativeIsLoaded(): Boolean
    external fun nativeComplete(prompt: String, maxTokens: Int): ByteArray?
    external fun nativeCancel()
    external fun nativeFree()
    external fun nativeContextSize(): Int
}
