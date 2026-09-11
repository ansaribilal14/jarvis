package com.jarvis.mobile.core.model

/** Provider abstraction (spec: PROVIDER ABSTRACTION). The agent never knows which runtime is active. */
interface LlmProvider {
    val id: String
    val displayName: String
    val isLocal: Boolean
    fun isReady(): Boolean
    suspend fun generate(prompt: String, maxTokens: Int): Result<String>
    fun unload() {}
}
