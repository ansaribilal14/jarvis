package com.jarvis.mobile.core.model

/** Provider abstraction (spec: PROVIDER ABSTRACTION). The agent never knows which runtime is active. */
interface LlmProvider {
    val id: String
    val displayName: String
    val isLocal: Boolean
    fun isReady(): Boolean

    /**
     * [stopSequences]: generation should halt as soon as one of them appears.
     * Local runtime enforces this natively (token/battery savings); remote
     * providers receive it as the OpenAI-compatible "stop" parameter.
     * [grammar]: optional GBNF for grammar-constrained decoding. Only the local
     * llama.cpp runtime uses it; remote providers ignore it.
     */
    suspend fun generate(
        prompt: String,
        maxTokens: Int,
        stopSequences: List<String> = emptyList(),
        grammar: String? = null,
    ): Result<String>
    fun unload() {}
}
