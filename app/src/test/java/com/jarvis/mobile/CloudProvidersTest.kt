package com.jarvis.mobile

import com.jarvis.mobile.core.model.CloudProviders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.2.0 regression guards for the cloud provider catalog.
 * Backstory: the v1.9-era presets shipped NVIDIA NIM slugs that NVIDIA later
 * retired (the whole llama-3.1/3.3 family) - the app then failed with HTTP 410
 * on a "working" config. These tests pin the VERIFIED-LIVE replacements and
 * forbid the dead slugs from ever coming back through a preset.
 */
class CloudProvidersTest {

    @Test
    fun `preset ids are unique and non-blank`() {
        val ids = CloudProviders.PRESETS.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.none { it.isBlank() })
    }

    @Test
    fun `every preset has a key label and note`() {
        CloudProviders.PRESETS.forEach { p ->
            assertTrue("missing keyLabel: ${p.id}", p.keyLabel.isNotBlank())
            assertTrue("missing note: ${p.id}", p.note.isNotBlank())
        }
    }

    @Test
    fun `cloud presets use https - only the LAN Ollama preset is http`() {
        CloudProviders.PRESETS.forEach { p ->
            if (p.baseUrl.isBlank()) return@forEach // custom: user-provided
            if (p.id == "ollama") {
                assertTrue(p.baseUrl.startsWith("http://"))
            } else {
                assertTrue("preset ${p.id} must be https", p.baseUrl.startsWith("https://"))
            }
        }
    }

    @Test
    fun `NIM preset uses verified-live models - retired slugs are gone`() {
        val models = CloudProviders.NIM_MODELS
        assertTrue("gpt-oss-20b verified live 2026-09-20", models.contains("openai/gpt-oss-20b"))
        assertTrue(models.contains("deepseek-ai/deepseek-v4-flash-0731"))
        // NVIDIA retired these on 2026-08-26 (HTTP 410):
        assertFalse(models.any { it.startsWith("meta/llama-3.1-8b") })
        assertFalse(models.any { it.startsWith("meta/llama-3.3-70b") })
        assertFalse(models.any { it.startsWith("mistralai/mistral-nemo-12b") })
        assertFalse(models.any { it.startsWith("qwen/qwen2.5-7b") })
        assertFalse(models.any { it.startsWith("deepseek-ai/deepseek-r1-distill") })
    }

    @Test
    fun `OpenRouter preset is free-tier only`() {
        val models = CloudProviders.OPENROUTER_MODELS
        assertTrue(models.isNotEmpty())
        models.forEach { assertTrue("non-free slug in preset: $it", it.endsWith(":free")) }
        assertTrue(models.contains("deepseek/deepseek-v4-flash-0731:free"))
        // dead free slugs that shipped before:
        assertFalse(models.contains("openai/gpt-oss-120b:free"))
        assertFalse(models.any { it.startsWith("meta-llama/llama-3.3-70b") })
    }

    @Test
    fun `guessId maps stored base urls back to providers`() {
        assertEquals("nim", CloudProviders.guessId("https://integrate.api.nvidia.com/v1"))
        assertEquals("openrouter", CloudProviders.guessId("https://openrouter.ai/api/v1"))
        assertEquals("novita", CloudProviders.guessId("https://api.novita.ai/v3/openai"))
        assertEquals("sambanova", CloudProviders.guessId("https://api.sambanova.ai/v1"))
        assertEquals("groq", CloudProviders.guessId("https://api.groq.com/openai/v1"))
        assertEquals("deepseek", CloudProviders.guessId("https://api.deepseek.com/v1"))
        assertEquals("ollama", CloudProviders.guessId("http://192.168.1.10:11434/v1"))
        assertEquals("custom", CloudProviders.guessId("https://my-box.example.com/v1"))
        assertNull(CloudProviders.guessId(""))
    }

    @Test
    fun `byId round-trips every preset`() {
        CloudProviders.PRESETS.forEach { p ->
            assertEquals(p, CloudProviders.byId(p.id))
        }
        assertEquals(null, CloudProviders.byId("does-not-exist"))
    }
}
