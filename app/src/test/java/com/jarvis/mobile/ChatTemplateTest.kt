package com.jarvis.mobile

import com.jarvis.mobile.core.model.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTemplateTest {

    @Test
    fun `chatml template renders control tokens`() {
        val p = render(ModelCatalog.ChatTemplate.CHATML, "sys rules", "user task")
        assertTrue(p.startsWith("<|im_start|>system"))
        assertTrue(p.contains("sys rules"))
        assertTrue(p.contains("<|im_start|>user\nuser task<|im_end|>"))
        assertTrue(p.endsWith("<|im_start|>assistant\n"))
    }

    @Test
    fun `llama3 template renders headers`() {
        val p = render(ModelCatalog.ChatTemplate.LLAMA3, "sys", "task")
        assertTrue(p.contains("<|start_header_id|>system<|end_header_id|>"))
        assertTrue(p.contains("<|eot_id|>"))
        assertTrue(p.endsWith("<|start_header_id|>assistant<|end_header_id|>\n\n"))
    }

    @Test
    fun `all catalog models have valid metadata`() {
        ModelCatalog.MODELS.forEach { m ->
            assertTrue(m.sha256.length == 64)
            assertFalse(m.sha256.equals("0".repeat(64)))
            assertTrue(m.sizeBytes > 100_000_000)
            assertTrue(m.downloadUrl.startsWith("https://huggingface.co/"))
            assertTrue(m.ramNeededGb > 0)
        }
    }

    private fun render(t: ModelCatalog.ChatTemplate, system: String, user: String): String = when (t) {
        ModelCatalog.ChatTemplate.CHATML ->
            "<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"
        ModelCatalog.ChatTemplate.LLAMA3 ->
            "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n$system<|eot_id|><|start_header_id|>user<|end_header_id|>\n\n$user<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n"
        ModelCatalog.ChatTemplate.PLAIN ->
            "$system\n\nUSER: $user\nASSISTANT:"
    }
}
