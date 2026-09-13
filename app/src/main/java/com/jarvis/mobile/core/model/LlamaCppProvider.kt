package com.jarvis.mobile.core.model

import com.jarvis.mobile.util.Logx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/** Live snapshot of an in-flight (or last) local generation. Drives the UI "Thinking…" detail. */
data class GenState(
    val generating: Boolean = false,
    val phase: Int = 0, // 0 = reading prompt, 1 = writing tokens
    val promptDone: Int = 0,
    val promptTotal: Int = 0,
    val outTokens: Int = 0,
    val partialText: String = "",
    val startedAtMs: Long = 0L,
    val lastUpdateAtMs: Long = 0L,
) {
    val elapsedMs: Long get() = if (startedAtMs == 0L) 0 else (if (generating) System.currentTimeMillis() else lastUpdateAtMs) - startedAtMs
    val tokensPerSec: Float
        get() {
            val secs = elapsedMs / 1000f
            return if (secs > 0.4f && outTokens > 0) outTokens / secs else 0f
        }
}

/**
 * llama.cpp-backed provider. One model loaded at a time; completions are
 * stateless (context managed by the agent layer) and cancellable.
 * Streams native decode progress into [genState] so the UI can show live feedback.
 */
class LlamaCppProvider(
    private val settings: com.jarvis.mobile.data.settings.SettingsRepository,
) : LlmProvider {

    override val id = "llamacpp"
    override val displayName = "llama.cpp (on-device)"
    override val isLocal = true

    @Volatile private var loadedFile: String? = null
    @Volatile private var loadedModel: ModelCatalog.CatalogModel? = null
    private val lastUsed = AtomicLong(0)
    private val generating = java.util.concurrent.atomic.AtomicBoolean(false)

    val activeModel: ModelCatalog.CatalogModel? get() = loadedModel

    /** True while a native completion is in flight (decode/prefill). Watchdog must never free under it. */
    fun isGenerating(): Boolean = generating.get()

    /** Wall clock of the last generation/load activity - drives the idle-unload decision. */
    fun lastUsedAt(): Long = lastUsed.get()

    private val _genState = MutableStateFlow(GenState())
    val genState: StateFlow<GenState> = _genState

    fun isModelFilePresent(model: ModelCatalog.CatalogModel, dir: java.io.File): Boolean =
        java.io.File(dir, model.fileName).let { it.exists() && it.length() == model.sizeBytes }

    suspend fun load(model: ModelCatalog.CatalogModel, file: java.io.File, contextSize: Int, threads: Int): Boolean =
        withContext(Dispatchers.IO) {
            // HARD GUARD: nativeLoadModel frees the old model/context first
            // (free_all). Loading while a completion is decoding = same
            // use-after-free class as the v1.3.0 watchdog bug. Never swap models
            // under a live inference.
            if (generating.get()) {
                Logx.e(TAG, "Refusing to load ${model.id}: a completion is in flight")
                return@withContext false
            }
            val ok = LlamaBridge.nativeLoadModel(file.absolutePath, contextSize, threads)
            if (ok) {
                loadedFile = file.absolutePath
                loadedModel = model
                lastUsed.set(System.currentTimeMillis())
                Logx.i(TAG, "Loaded ${model.id} (ctx=$contextSize)")
            } else {
                Logx.e(TAG, "Failed to load ${model.id}")
            }
            ok
        }

    override fun isReady(): Boolean = LlamaBridge.nativeIsLoaded()

    override suspend fun generate(
        prompt: String,
        maxTokens: Int,
        stopSequences: List<String>,
        grammar: String?,
    ): Result<String> = withContext(Dispatchers.Default) {
        if (!isReady()) return@withContext Result.failure(IllegalStateException("No local model loaded"))
        if (!generating.compareAndSet(false, true)) {
            return@withContext Result.failure(IllegalStateException("Inference already in progress"))
        }
        lastUsed.set(System.currentTimeMillis())
        val started = System.currentTimeMillis()
        _genState.value = GenState(generating = true, startedAtMs = started, lastUpdateAtMs = started)
        val listener = LlamaBridge.ProgressListener { phase, promptDone, promptTotal, outTokens, partial ->
            _genState.value = GenState(
                generating = true,
                phase = phase,
                promptDone = promptDone,
                promptTotal = promptTotal,
                outTokens = outTokens,
                partialText = partial?.let { String(it, Charsets.UTF_8) } ?: "",
                startedAtMs = started,
                lastUpdateAtMs = System.currentTimeMillis(),
            )
        }
        try {
            val bytes = LlamaBridge.nativeComplete(prompt, maxTokens, listener, stopSequences.toTypedArray(), grammar)
            if (bytes == null) Result.failure(IllegalStateException("Generation returned nothing (model error or cancelled)"))
            else Result.success(String(bytes, Charsets.UTF_8))
        } catch (t: Throwable) {
            Logx.e(TAG, "Generation crashed: ${t.message}")
            Result.failure(t)
        } finally {
            val done = _genState.value
            _genState.value = done.copy(generating = false, lastUpdateAtMs = System.currentTimeMillis())
            generating.set(false)
            // Idle timer restarts after every generation, so the watchdog can never
            // consider an active/in-flight session "idle" (root cause of the v1.3.0
            // "stuck at Waking the model" hang: unload mid-decode = use-after-free).
            lastUsed.set(System.currentTimeMillis())
        }
    }

    fun cancel() {
        LlamaBridge.nativeCancel()
    }

    override fun unload() {
        // HARD GUARD: freeing the native model/context while nativeComplete is
        // decoding on another thread is use-after-free (hangs or corrupts and the
        // generating flag never clears -> UI frozen at "Waking the model").
        if (generating.get()) {
            Logx.w(TAG, "Unload requested while generating - cancelled inference, deferring free")
            cancel()
            return
        }
        if (isReady()) {
            LlamaBridge.nativeFree()
            Logx.i(TAG, "Model unloaded (idle)")
        }
        loadedFile = null
        loadedModel = null
        _genState.value = GenState()
    }

    /** Benchmark: tokens/sec over a fixed tool-planning style prompt. */
    suspend fun benchmark(): Result<Double> = withContext(Dispatchers.Default) {
        if (!isReady()) return@withContext Result.failure(IllegalStateException("No model loaded"))
        // Expert-review fix: benchmark runs nativeComplete directly - it MUST hold
        // the same single-flight guard as generate(), or "Benchmark" tapped during
        // a task = two threads on one llama_context = SIGSEGV.
        if (!generating.compareAndSet(false, true)) {
            return@withContext Result.failure(IllegalStateException("A task is using the model right now"))
        }
        try {
            val prompt = "USER TASK: open chrome and search for local ai. Respond with one JSON action only."
            val start = System.currentTimeMillis()
            val res = LlamaBridge.nativeComplete(prompt, 64, null, emptyArray(), null)
            val ms = System.currentTimeMillis() - start
            if (res == null) Result.failure(IllegalStateException("Benchmark generation failed"))
            else Result.success(64_000.0 / ms.coerceAtLeast(1))
        } finally {
            generating.set(false)
            lastUsed.set(System.currentTimeMillis())
        }
    }

    companion object {
        const val TAG = "llama"
    }
}
