package com.jarvis.mobile.core.model

import com.jarvis.mobile.util.Logx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * llama.cpp-backed provider. One model loaded at a time; completions are
 * stateless (context managed by the agent layer) and cancellable.
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

    fun isModelFilePresent(model: ModelCatalog.CatalogModel, dir: java.io.File): Boolean =
        java.io.File(dir, model.fileName).let { it.exists() && it.length() == model.sizeBytes }

    suspend fun load(model: ModelCatalog.CatalogModel, file: java.io.File, contextSize: Int, threads: Int): Boolean =
        withContext(Dispatchers.IO) {
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

    override suspend fun generate(prompt: String, maxTokens: Int): Result<String> = withContext(Dispatchers.Default) {
        if (!isReady()) return@withContext Result.failure(IllegalStateException("No local model loaded"))
        if (!generating.compareAndSet(false, true)) {
            return@withContext Result.failure(IllegalStateException("Inference already in progress"))
        }
        lastUsed.set(System.currentTimeMillis())
        try {
            val bytes = LlamaBridge.nativeComplete(prompt, maxTokens)
            if (bytes == null) Result.failure(IllegalStateException("Generation returned nothing (model error or cancelled)"))
            else Result.success(String(bytes, Charsets.UTF_8))
        } catch (t: Throwable) {
            Logx.e(TAG, "Generation crashed: ${t.message}")
            Result.failure(t)
        } finally {
            generating.set(false)
        }
    }

    fun cancel() {
        LlamaBridge.nativeCancel()
    }

    override fun unload() {
        if (isReady()) {
            LlamaBridge.nativeFree()
            Logx.i(TAG, "Model unloaded (idle)")
        }
        loadedFile = null
        loadedModel = null
    }

    /** Benchmark: tokens/sec over a fixed tool-planning style prompt. */
    suspend fun benchmark(): Result<Double> = withContext(Dispatchers.Default) {
        if (!isReady()) return@withContext Result.failure(IllegalStateException("No model loaded"))
        val prompt = "USER TASK: open chrome and search for local ai. Respond with one JSON action only."
        val start = System.currentTimeMillis()
        val res = LlamaBridge.nativeComplete(prompt, 64)
        val ms = System.currentTimeMillis() - start
        if (res == null) Result.failure(IllegalStateException("Benchmark generation failed"))
        else Result.success(64_000.0 / ms.coerceAtLeast(1))
    }

    companion object {
        const val TAG = "llama"
    }
}
