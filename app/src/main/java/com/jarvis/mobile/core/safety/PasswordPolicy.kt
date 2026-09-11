package com.jarvis.mobile.core.safety

import com.jarvis.mobile.core.observer.ScreenElement

/**
 * Credential protection (spec: PASSWORD SAFETY / OTP SAFETY).
 * The agent never types into, copies from, or stores content of:
 *  - password fields (accessibility flags them)
 *  - OTP / verification-code fields (heuristic match)
 */
object PasswordPolicy {

    private val otpHints = listOf(
        "otp", "one time", "one-time", "verification code", "confirm code", "security code", "enter code", "passcode",
    )

    fun isProtected(el: ScreenElement): Boolean {
        if (el.isPassword) return true
        val label = ((el.text ?: "") + " " + (el.desc ?: "") + " " + (el.viewId ?: "")).lowercase()
        return otpHints.any { label.contains(it) }
    }

    /** Redacts anything credential-like before it can reach long-term memory. */
    fun redactForMemory(text: String): String = text
        .replace(Regex("(?i)(password|passwd|pin|otp)\\s*[:=]\\s*\\S+"), "$1=[REDACTED]")
        .replace(Regex("\\b\\d{13,19}\\b"), "[NUMBER-REDACTED]") // long numeric sequences (cards)
}
