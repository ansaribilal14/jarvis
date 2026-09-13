package com.jarvis.mobile.core.model

/**
 * JNI boundary to the native llama.cpp runtime (libjarvis_llama.so).
 * Kept minimal and isolated per the native inference boundary requirement.
 */
object LlamaBridge {
    init {
        System.loadLibrary("jarvis_llama")
    }

    /** Live generation progress pushed from the native decode loop. */
    fun interface ProgressListener {
        /**
         * Called on the thread that invoked [LlamaBridge.nativeComplete].
         * @param phase 0 = reading/prompt-eval, 1 = writing/decoding
         * @param promptDone prompt tokens processed so far
         * @param promptTotal prompt tokens total
         * @param outTokens tokens generated so far
         * @param partial UTF-8 bytes of the text generated so far (null-safe)
         */
        fun onProgress(phase: Int, promptDone: Int, promptTotal: Int, outTokens: Int, partial: ByteArray?)
    }

    external fun nativeLoadModel(path: String, contextSize: Int, threads: Int): Boolean
    external fun nativeIsLoaded(): Boolean

    /**
     * One completion. [stopSequences] cut generation early the moment any of
     * them appears in the output (native-side, so tokens/battery are saved);
     * the matched marker itself is trimmed off the returned text.
     */
    external fun nativeComplete(
        prompt: String,
        maxTokens: Int,
        listener: ProgressListener?,
        stopSequences: Array<String>,
    ): ByteArray?

    external fun nativeCancel()
    external fun nativeFree()
    external fun nativeContextSize(): Int
}
