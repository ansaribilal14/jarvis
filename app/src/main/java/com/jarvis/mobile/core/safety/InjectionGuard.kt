package com.jarvis.mobile.core.safety

import com.jarvis.mobile.core.observer.ScreenObservation

/**
 * Screen content is UNTRUSTED INPUT (spec: PROMPT INJECTION DEFENSE).
 * Text rendered by apps must never be treated as user instructions. This guard
 * detects classic injection attempts so the engine can add hardening and force
 * confirmations while a suspicious screen is visible.
 */
object InjectionGuard {

    enum class Level { CLEAN, SUSPICIOUS }

    data class Verdict(val level: Level, val reasons: List<String>)

    private val patterns = listOf(
        Regex("ignore\\s+(all\\s+)?(previous|prior|above)\\s+instructions", RegexOption.IGNORE_CASE),
        Regex("disregard\\s+(all\\s+)?(previous|prior|your)\\s+instructions", RegexOption.IGNORE_CASE),
        Regex("you\\s+are\\s+now\\s+(a|an|the)", RegexOption.IGNORE_CASE),
        Regex("new\\s+instructions?\\s*:", RegexOption.IGNORE_CASE),
        Regex("system\\s+prompt", RegexOption.IGNORE_CASE),
        Regex("reveal\\s+your\\s+(instructions|prompt|rules)", RegexOption.IGNORE_CASE),
        Regex("\\b(send|forward|transfer|share)\\b[^\\n]{0,60}\\b(password|otp|pin|code|money)\\b", RegexOption.IGNORE_CASE),
        Regex("assistant\\s*:\\s*send", RegexOption.IGNORE_CASE),
        Regex("jarvis,?\\s+(ignore|forget)\\b", RegexOption.IGNORE_CASE),
    )

    fun inspect(obs: ScreenObservation?): Verdict {
        if (obs == null) return Verdict(Level.CLEAN, emptyList())
        val corpus = obs.elements.joinToString("\n") { (it.text ?: "") + " " + (it.desc ?: "") }
        val hits = patterns.mapNotNull { p ->
            p.find(corpus)?.let { p.pattern }
        }
        return Verdict(if (hits.isEmpty()) Level.CLEAN else Level.SUSPICIOUS, hits.distinct().take(4))
    }
}
