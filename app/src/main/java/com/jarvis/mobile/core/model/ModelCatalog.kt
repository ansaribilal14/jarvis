package com.jarvis.mobile.core.model

/**
 * Curated local model catalog. URLs and SHA-256 checksums are real values taken
 * from the HuggingFace Hub API (verified at build time). Models are downloaded
 * separately from the APK (spec: APK STRATEGY) and verified before activation.
 */
object ModelCatalog {

    enum class ChatTemplate { CHATML, LLAMA3, PLAIN }

    data class CatalogModel(
        val id: String,
        val repo: String,
        val fileName: String,
        val url: String,
        val sizeBytes: Long,
        val sha256: String,
        val params: String,
        val quant: String,
        val contextTrain: Int,
        val ramNeededGb: Double,
        val minDeviceClass: DeviceProfiler.DeviceClass,
        val template: ChatTemplate,
        val strengths: String,
    ) {
        val sizeMb: Int get() = (sizeBytes / (1024 * 1024)).toInt()
        val downloadUrl: String get() = "https://huggingface.co/$repo/resolve/main/$fileName"
    }

    val MODELS = listOf(
        CatalogModel(
            id = "qwen25-05b",
            repo = "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
            fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            sizeBytes = 491400032,
            sha256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
            params = "0.5B",
            quant = "Q4_K_M",
            contextTrain = 32768,
            ramNeededGb = 1.0,
            minDeviceClass = DeviceProfiler.DeviceClass.BASIC,
            template = ChatTemplate.CHATML,
            strengths = "Fastest; reliable JSON tool output for its size; ideal on low-RAM phones.",
        ),
        CatalogModel(
            id = "smollm2-17b",
            repo = "HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF",
            fileName = "smollm2-1.7b-instruct-q4_k_m.gguf",
            url = "https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF/resolve/main/smollm2-1.7b-instruct-q4_k_m.gguf",
            sizeBytes = 1055609536,
            sha256 = "decd2598bc2c8ed08c19adc3c8fdd461ee19ed5708679d1c54ef54a5a30d4f33",
            params = "1.7B",
            quant = "Q4_K_M",
            contextTrain = 8192,
            ramNeededGb = 2.2,
            minDeviceClass = DeviceProfiler.DeviceClass.STANDARD,
            template = ChatTemplate.CHATML,
            strengths = "Best speed/quality balance on mid-range devices; strong instruction following.",
        ),
        CatalogModel(
            id = "qwen25-15b",
            repo = "Qwen/Qwen2.5-1.5B-Instruct-GGUF",
            fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            url = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            sizeBytes = 1117320736,
            sha256 = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e",
            params = "1.5B",
            quant = "Q4_K_M",
            contextTrain = 32768,
            ramNeededGb = 2.4,
            minDeviceClass = DeviceProfiler.DeviceClass.STANDARD,
            template = ChatTemplate.CHATML,
            strengths = "Very good planner for single-step device actions; long context support.",
        ),
        CatalogModel(
            id = "llama32-3b",
            repo = "bartowski/Llama-3.2-3B-Instruct-GGUF",
            fileName = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            url = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            sizeBytes = 2019377696,
            sha256 = "6c1a2b41161032677be168d354123594c0e6e67d2b9227c84f296ad037c728ff",
            params = "3B",
            quant = "Q4_K_M",
            contextTrain = 131072,
            ramNeededGb = 3.6,
            minDeviceClass = DeviceProfiler.DeviceClass.POWER,
            template = ChatTemplate.LLAMA3,
            strengths = "Stronger reasoning for multi-step plans and recovery decisions.",
        ),
        CatalogModel(
            id = "qwen25-3b",
            repo = "Qwen/Qwen2.5-3B-Instruct-GGUF",
            fileName = "qwen2.5-3b-instruct-q4_k_m.gguf",
            url = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
            sizeBytes = 2104932768,
            sha256 = "626b4a6678b86442240e33df819e00132d3ba7dddfe1cdc4fbb18e0a9615c62d",
            params = "3B",
            quant = "Q4_K_M",
            contextTrain = 32768,
            ramNeededGb = 3.8,
            minDeviceClass = DeviceProfiler.DeviceClass.POWER,
            template = ChatTemplate.CHATML,
            strengths = "Best tool-calling reliability in the 3B class; long context.",
        ),
    )

    fun byId(id: String?): CatalogModel? = MODELS.firstOrNull { it.id == id }

    /** Recommend the strongest model the device can actually run (fallback ladder rung 1). */
    fun recommendFor(profile: DeviceProfiler.Profile): CatalogModel {
        val usable = MODELS.filter {
            it.minDeviceClass <= profile.deviceClass && it.ramNeededGb <= (profile.totalRamGb * 0.45)
        }
        // Pick the largest usable model; prefer Qwen 3B over Llama 3B on ties for tool calling.
        return usable.maxByOrNull { it.ramNeededGb * 10 + if (it.id.startsWith("qwen")) 0.5 else 0.0 }
            ?: MODELS.minByOrNull { it.ramNeededGb }!!
    }

    fun lighterThan(m: CatalogModel): CatalogModel? =
        MODELS.filter { it.ramNeededGb < m.ramNeededGb }.maxByOrNull { it.ramNeededGb }
}
