package com.jarvis.mobile.core.model

/**
 * Chat-template rendering for local GGUF models (deterministic, no jinja).
 * Templates match each catalog model's training format.
 */
object PromptTemplates {

    fun render(template: ModelCatalog.ChatTemplate, system: String, user: String): String = when (template) {
        ModelCatalog.ChatTemplate.CHATML ->
            "<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"
        ModelCatalog.ChatTemplate.LLAMA3 ->
            "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n$system<|eot_id|><|start_header_id|>user<|end_header_id|>\n\n$user<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n"
        ModelCatalog.ChatTemplate.PLAIN ->
            "$system\n\nUSER: $user\nASSISTANT:"
    }
}
