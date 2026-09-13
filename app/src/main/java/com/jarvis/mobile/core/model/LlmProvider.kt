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
     */
    suspend fun generate(prompt: String, maxTokens: Int, stopSequences: List<String> = emptyList()): Result<String>
    fun unload() {}
}
