package com.jarvis.mobile.core.observer

/**
 * A single interactive/informative element extracted from the accessibility tree.
 * Numeric indices ([idx]) are the semantic handles the agent uses instead of
 * raw coordinates (spec: SEMANTIC UI HANDLES).
 */
data class ScreenElement(
    val idx: Int,
    val role: String,          // button, edittext, text, image, checkbox, toggle, dropdown, list, web, other
    val text: String?,         // visible text (masked if password)
    val desc: String?,         // content description
    val viewId: String?,       // viewIdResourceName
    val className: String?,
    val left: Int, val top: Int, val right: Int, val bottom: Int,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val selected: Boolean,
    val isPassword: Boolean,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    fun label(): String = text ?: desc ?: viewId?.substringAfterLast('/') ?: className?.substringAfterLast('.') ?: role
}

/**
 * Compact structured representation of the current screen.
 * This is what the planner sees - never raw node dumps, never screenshots by default.
 */
data class ScreenObservation(
    val packageName: String?,
    val activityName: String?,
    val elements: List<ScreenElement>,
    val capturedAt: Long = System.currentTimeMillis(),
    val truncated: Boolean = false,
    val source: String = "a11y", // a11y | ocr | mixed
) {
    /** Screen content is UNTRUSTED INPUT (spec: PROMPT INJECTION DEFENSE). */
    fun toCompact(maxElements: Int = 60): String {
        val sb = StringBuilder()
        sb.append("APP: ").append(packageName ?: "unknown").append('\n')
        activityName?.let { sb.append("SCREEN: ").append(it.substringAfterLast('.')).append('\n') }
        val shown = elements.take(maxElements)
        for (e in shown) {
            val bits = mutableListOf<String>()
            bits.add("role=${e.role}")
            e.text?.let { bits.add("text=\"${it.take(60).replace("\n", " ")}\"") }
            if (e.desc != null && e.text == null) bits.add("desc=\"${e.desc.take(40)}\"")
            if (e.isPassword) bits.add("password=true")
            if (e.editable) bits.add("editable=true")
            if (e.scrollable) bits.add("scrollable=true")
            if (e.selected) bits.add("selected=true")
            sb.append("[").append(e.idx).append("] ").append(bits.joinToString(" ")).append('\n')
        }
        if (elements.size > maxElements) {
            sb.append("(+").append(elements.size - maxElements).append(" more elements hidden)\n")
        }
        return sb.toString()
    }

    fun findText(simple: String): ScreenElement? =
        elements.firstOrNull { it.text?.equals(simple, ignoreCase = true) == true || it.desc?.equals(simple, ignoreCase = true) == true }

    fun findContains(s: String): ScreenElement? =
        elements.firstOrNull { it.text?.contains(s, ignoreCase = true) == true || it.desc?.contains(s, ignoreCase = true) == true }
}
