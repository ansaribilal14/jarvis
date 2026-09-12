package com.jarvis.mobile.core.model

import android.content.Context
import com.jarvis.mobile.data.settings.SettingsRepository
import com.jarvis.mobile.util.Logx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Model manager (spec: MODEL MANAGER / MODEL DOWNLOAD SECURITY):
 * discover (catalog) / download (resumable) / pause / resume / cancel /
 * verify (SHA-256) / import / delete / select / unload / benchmark.
 */
class ModelManager(
    private val context: Context,
    private val settings: SettingsRepository,
    val profiler: DeviceProfiler,
) {
    sealed class DownloadState {
        data object Idle : DownloadState()
        data class Downloading(val downloaded: Long, val total: Long, val speedKbs: Long) : DownloadState()
        data class Verifying(val downloaded: Long, val total: Long) : DownloadState()
        data object Done : DownloadState()
        data class Failed(val reason: String) : DownloadState()
    }

    /**
     * Reactive model-load lifecycle. The UI collects [loadState] so activation
     * is always visible: Loading (with feedback), Loaded (chip switches to
     * ACTIVE) or Failed (reason shown). Fixes the "Activate button does
     * nothing" bug - the previous UI read a non-reactive volatile field.
     */
    sealed class LoadState {
        data object Idle : LoadState()
        data class Loading(val modelId: String) : LoadState()
        data class Loaded(val modelId: String) : LoadState()
        data class Failed(val modelId: String?, val reason: String) : LoadState()
        val loadedModelId: String? get() = (this as? Loaded)?.modelId
    }

    private val _loadState = MutableStateFlow<LoadState>(LoadState.Idle)
    val loadState: StateFlow<LoadState> = _loadState

    /** Serializes every activation; also rejects re-entrant taps while a load is running. */
    private val loadMutex = Mutex()
    @Volatile private var loadingModelId: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()
    private val pauseFlags = HashMap<String, java.util.concurrent.atomic.AtomicBoolean>()

    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states

    val llama: LlamaCppProvider = LlamaCppProvider(settings)

    fun modelsDir(): File = File(context.filesDir, "models").apply { mkdirs() }

    fun fileFor(m: ModelCatalog.CatalogModel): File = File(modelsDir(), m.fileName)

    fun isDownloaded(m: ModelCatalog.CatalogModel): Boolean = fileFor(m).let { it.exists() && it.length() == m.sizeBytes }

    fun importedFiles(): List<File> = modelsDir().listFiles { f -> f.extension.equals("gguf", true) }?.toList() ?: emptyList()

    /**
     * Imported .gguf files as activatable catalog entries (no pinned checksum,
     * conservative context). Lets users run their own models end-to-end.
     */
    fun importedModels(): List<ModelCatalog.CatalogModel> = importedFiles()
        .filter { it.length() > 0 }
        .map { f ->
            ModelCatalog.CatalogModel(
                id = "imported:${f.name}",
                repo = "local import",
                fileName = f.name,
                url = "",
                sizeBytes = f.length(),
                sha256 = "",
                params = "?",
                quant = "imported",
                contextTrain = 4096,
                ramNeededGb = f.length() / 1024.0 / 1024.0 / 1024.0 * 1.3 + 0.5,
                minDeviceClass = DeviceProfiler.DeviceClass.BASIC,
                template = ModelCatalog.ChatTemplate.PLAIN,
                strengths = "User-imported GGUF model stored on this device.",
            )
        }

    fun activeId(): String? = _loadState.value.loadedModelId ?: llama.activeModel?.id

    fun isModelActive(m: ModelCatalog.CatalogModel): Boolean =
        _loadState.value.loadedModelId == m.id || llama.activeModel?.fileName == m.fileName

    /** True while a model is being loaded into the native runtime (UI shows progress + disables buttons). */
    fun isLoading(): Boolean = _loadState.value is LoadState.Loading

    /**
     * Activate a downloaded model. Fully IO-bound (checksum hashing of multi-GB
     * files used to run on the caller's Main dispatcher and froze the app),
     * mutex-guarded against double taps, and always reports its outcome via
     * [loadState] so the UI can react.
     */
    suspend fun selectAndLoad(m: ModelCatalog.CatalogModel): Boolean = withContext(Dispatchers.IO) {
        loadMutex.withLock {
            if (_loadState.value is LoadState.Loading) {
                Logx.w(TAG, "Load already in progress (${loadingModelId}) - ignoring request for ${m.id}")
                return@withLock false
            }
            _loadState.value = LoadState.Loading(m.id)
            loadingModelId = m.id
            try {
                val file = fileFor(m)
                if (!file.exists() || file.length() == 0L) {
                    Logx.e(TAG, "Activate ${m.id}: file missing")
                    _loadState.value = LoadState.Failed(m.id, "Model file not found on device - download it first.")
                    return@withLock false
                }
                if (m.sha256.isNotBlank() && !verifyChecksum(file, m.sha256)) {
                    Logx.e(TAG, "Checksum mismatch for ${m.id} - refusing to load")
                    _states.value = _states.value + (m.id to DownloadState.Failed("Checksum mismatch - file may be corrupted. Delete and re-download."))
                    _loadState.value = LoadState.Failed(m.id, "Checksum mismatch - file may be corrupted. Delete and re-download.")
                    return@withLock false
                }
                val snap = settings.snapshot()
                val ok = llama.load(m, file, snap.contextSize.coerceAtMost(4096), snap.inferenceThreads)
                if (ok) {
                    settings.setActiveModelId(m.id)
                    lastGenerationAt = System.currentTimeMillis()
                    _loadState.value = LoadState.Loaded(m.id)
                    Logx.i(TAG, "Activated ${m.id}")
                } else {
                    _loadState.value = LoadState.Failed(
                        m.id,
                        "Could not load this model (not enough free RAM or unsupported file). Try a smaller model, or lower the context size in Settings.",
                    )
                }
                ok
            } catch (t: Throwable) {
                Logx.e(TAG, "Activate ${m.id} crashed: ${t.message}")
                _loadState.value = LoadState.Failed(m.id, t.message ?: "Activation failed unexpectedly.")
                false
            } finally {
                loadingModelId = null
            }
        }
    }

    fun unload() {
        llama.unload()
        if (_loadState.value !is LoadState.Loading) _loadState.value = LoadState.Idle
    }

    /** Idle unloader: battery management (spec: BATTERY MANAGEMENT). */
    fun startIdleWatchdog() {
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(60_000)
                val min = settings.unloadIdleMin.first()
                if (min <= 0) continue
                // NEVER unload while a completion is in flight: nativeFree() under a
                // live decode is use-after-free and froze tasks at "Waking the model…"
                // (v1.3.0 bug). The provider's lastUsedAt() also now refreshes on every
                // generate(), not only at activation.
                if (llama.isGenerating()) continue
                if (llama.isReady()) {
                    if (System.currentTimeMillis() - llama.lastUsedAt() > min * 60_000L) {
                        unload()
                        Logx.i(TAG, "Idle watchdog unloaded the model")
                    }
                }
            }
        }
    }

    @Volatile var lastGenerationAt: Long = System.currentTimeMillis()

    // ------------------------------------------------------------ download

    fun download(m: ModelCatalog.CatalogModel) {
        if (jobs[m.id]?.isActive == true) return
        val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
        if (stat.availableBytes < m.sizeBytes + 200L * 1024 * 1024) {
            _states.value = _states.value + (m.id to DownloadState.Failed("Not enough storage (${m.sizeMb} MB needed). Free up space and retry."))
            return
        }
        val pause = java.util.concurrent.atomic.AtomicBoolean(false)
        pauseFlags[m.id] = pause
        jobs[m.id] = scope.launch {
            _states.value = _states.value + (m.id to DownloadState.Downloading(0, m.sizeBytes, 0))
            try {
                val target = fileFor(m)
                val partial = File(target.absolutePath + ".part")
                var downloaded = if (partial.exists()) partial.length() else 0L
                var lastTick = System.currentTimeMillis()
                var lastBytes = downloaded

                while (downloaded < m.sizeBytes) {
                    if (pause.get()) {
                        _states.value = _states.value + (m.id to DownloadState.Idle)
                        return@launch
                    }
                    val conn = URL(m.downloadUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 30000
                    conn.instanceFollowRedirects = true
                    if (downloaded > 0) conn.setRequestProperty("Range", "bytes=$downloaded-")
                    val code = conn.responseCode
                    if (code !in 200..299) throw IOException("HTTP $code")
                    val resumeSupported = code == 206
                    if (!resumeSupported) { downloaded = 0; partial.outputStream().use { }; }

                    conn.inputStream.use { input ->
                        java.io.FileOutputStream(partial, resumeSupported && downloaded > 0).use { out ->
                            val buf = ByteArray(256 * 1024)
                            while (true) {
                                if (pause.get()) return@launch
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                downloaded += n
                                val now = System.currentTimeMillis()
                                if (now - lastTick > 800) {
                                    val speed = ((downloaded - lastBytes) * 1000 / (now - lastTick).coerceAtLeast(1)) / 1024
                                    _states.value = _states.value + (m.id to DownloadState.Downloading(downloaded, m.sizeBytes, speed))
                                    lastTick = now
                                    lastBytes = downloaded
                                }
                            }
                        }
                    }
                    conn.disconnect()
                    if (downloaded < m.sizeBytes) {
                        // Server closed early; loop to resume.
                        kotlinx.coroutines.delay(800)
                    }
                }

                _states.value = _states.value + (m.id to DownloadState.Verifying(m.sizeBytes, m.sizeBytes))
                partial.renameTo(target)
                if (!verifyChecksum(target, m.sha256)) {
                    target.delete()
                    throw IOException("SHA-256 verification failed - download corrupted, deleted")
                }
                _states.value = _states.value + (m.id to DownloadState.Done)
                Logx.i(TAG, "Downloaded+verified ${m.id}")
                // One-tap flow: activate right away so the user never has to
                // find a second button after waiting for a multi-GB download.
                // Failures are surfaced through loadState, not thrown.
                selectAndLoad(m)
            } catch (t: Throwable) {
                Logx.e(TAG, "Download ${m.id} failed: ${t.message}")
                _states.value = _states.value + (m.id to DownloadState.Failed(t.message ?: "Download failed"))
            } finally {
                jobs.remove(m.id)
            }
        }
    }

    fun pause(m: ModelCatalog.CatalogModel) {
        pauseFlags[m.id]?.set(true)
        _states.value = _states.value + (m.id to DownloadState.Idle)
    }

    fun resume(m: ModelCatalog.CatalogModel) = download(m)

    fun cancel(m: ModelCatalog.CatalogModel) {
        pauseFlags[m.id]?.set(true)
        jobs[m.id]?.cancel()
        File(fileFor(m).absolutePath + ".part").delete()
        _states.value = _states.value + (m.id to DownloadState.Idle)
    }

    fun delete(m: ModelCatalog.CatalogModel) {
        cancel(m)
        if (llama.activeModel?.id == m.id || _loadState.value.loadedModelId == m.id) {
            llama.unload()
            if (_loadState.value !is LoadState.Loading) _loadState.value = LoadState.Idle
        }
        fileFor(m).delete()
        _states.value = _states.value + (m.id to DownloadState.Idle)
    }

    /** Import a .gguf from a SAF uri (copied into models dir; checksum pinned after first verified run). */
    suspend fun import(uri: android.net.Uri): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val name = queryDisplayName(uri) ?: "imported-${System.currentTimeMillis()}.gguf"
            val target = File(modelsDir(), name)
            context.contentResolver.openInputStream(uri)!!.use { input ->
                target.outputStream().use { output -> input.copyTo(output, 512 * 1024) }
            }
            // Basic GGUF magic check: "GGUF"
            target.inputStream().use { ins ->
                val magic = ByteArray(4)
                ins.read(magic)
                if (!magic.toString(Charsets.US_ASCII).startsWith("GGUF")) {
                    target.delete()
                    throw IOException("Not a GGUF model file")
                }
            }
            Logx.i(TAG, "Imported model $name (${target.length() / 1024 / 1024} MB)")
            target
        }.onFailure { Logx.e(TAG, "Import failed: ${it.message}") }
    }

    private fun queryDisplayName(uri: android.net.Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun verifyChecksum(file: File, expected: String): Boolean {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        val got = md.digest().joinToString("") { "%02x".format(it) }
        return got.equals(expected, ignoreCase = true)
    }

    /** Load + measure; stores nothing fake - the value is what the device produced. */
    suspend fun benchmarkActive(): Result<Double> = llama.benchmark()

    fun thermalDegraded(): Boolean {
        val pm = context.getSystemService(android.os.PowerManager::class.java) ?: return false
        return if (android.os.Build.VERSION.SDK_INT >= 30) pm.currentThermalStatus >= 3 else false
    }

    companion object {
        const val TAG = "models"
    }
}
