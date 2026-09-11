package com.jarvis.mobile.core.safety

import com.jarvis.mobile.core.tools.Risk
import com.jarvis.mobile.core.tools.ToolSpec
import kotlinx.serialization.json.JsonObject

/**
 * Action risk system (spec: HIGH-RISK ACTIONS).
 * - LOW tools run freely.
 * - MEDIUM tools require confirmation unless the user enabled auto-approve.
 * - HIGH tools ALWAYS require confirmation (cannot be disabled).
 * Context escalation: typing into a messaging app is treated as message sending.
 */
object RiskClassifier {

    private val messagingPackages = listOf(
        "whatsapp", "telegram", "messaging", "messenger", "instagram", "snapchat", "sms", "mail", "gmail", "outlook", "discord", "slack",
    )

    private val highRiskPackages = listOf(
        "bank", "paytm", "phonepe", "gpay", "googlepay", "upi", "paypal", "amazon", "flipkart", "wallet", "broker", "zerodha", "groww", "coinbase", "binance",
    )

    fun classify(spec: ToolSpec, args: JsonObject, currentPackage: String?): Risk {
        var risk = spec.risk
        val pkg = (currentPackage ?: "").lowercase()

        // Typing text inside a messaging app = sending a message.
        if (spec.name == "type_text" && messagingPackages.any { pkg.contains(it) }) {
            risk = Risk.MEDIUM
        }
        // Any interactive action inside a finance/purchase app is high risk.
        if (highRiskPackages.any { pkg.contains(it) } &&
            spec.name in setOf("tap", "type_text", "long_press", "paste_text", "share_content", "share_file", "swipe")
        ) {
            risk = Risk.HIGH
        }
        // Sensitive sharing.
        if (spec.name in setOf("share_file", "share_content")) risk = risk.coerceAtLeast(Risk.MEDIUM)
        return risk
    }

    fun requiresConfirmation(risk: Risk, autoApproveMedium: Boolean): Boolean = when (risk) {
        Risk.LOW -> false
        Risk.MEDIUM -> !autoApproveMedium
        Risk.HIGH -> true
    }

    fun whyConfirmation(spec: ToolSpec, risk: Risk, args: JsonObject): String = when {
        risk == Risk.HIGH -> "This action can spend money, change accounts or share sensitive data inside a sensitive app. JARVIS always asks before high-risk actions."
        spec.name == "type_text" -> "JARVIS is about to enter text on your behalf. Confirm the exact content and destination."
        spec.name in setOf("control_wifi", "control_bluetooth", "control_brightness", "control_volume") -> "JARVIS is about to change a device setting."
        spec.name in setOf("share_file", "share_content") -> "JARVIS is about to share content through another app."
        spec.name == "paste_text" -> "JARVIS is about to paste clipboard content into a field."
        else -> "JARVIS is about to perform: ${spec.name}."
    }
}
