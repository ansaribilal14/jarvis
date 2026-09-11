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

    fun activeId(): String? = llama.activeModel?.id

    fun isModelActive(m: ModelCatalog.CatalogModel): Boolean = llama.activeModel?.fileName == m.fileName

    suspend fun selectAndLoad(m: ModelCatalog.CatalogModel): Boolean {
        val snap = settings.snapshot()
        val file = fileFor(m)
        if (!file.exists()) return false
        if (m.sha256.isNotBlank() && !verifyChecksum(file, m.sha256)) {
            Logx.e(TAG, "Checksum mismatch for ${m.id} - refusing to load")
            _states.value = _states.value + (m.id to DownloadState.Failed("Checksum mismatch - file may be corrupted. Delete and re-download."))
            return false
        }
        val ok = llama.load(m, file, snap.contextSize.coerceAtMost(4096), snap.inferenceThreads)
        if (ok) settings.setActiveModelId(m.id)
        return ok
    }

    fun unload() = llama.unload()

    /** Idle unloader: battery management (spec: BATTERY MANAGEMENT). */
    fun startIdleWatchdog() {
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(60_000)
                val min = settings.unloadIdleMin.first()
                if (min <= 0) continue
                if (llama.isReady()) {
                    // LlamaCppProvider tracks lastUsed internally via generation calls.
                    if (System.currentTimeMillis() - lastGenerationAt > min * 60_000L) {
                        llama.unload()
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
        if (llama.activeModel?.id == m.id) llama.unload()
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
        }.recoverCatching { throw it }
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
