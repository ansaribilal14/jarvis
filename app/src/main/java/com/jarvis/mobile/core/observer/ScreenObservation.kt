package com.jarvis.mobile.core.observer

/**
 * A single interactive/informative element extracted from the accessibility tree.
 * Numeric indices ([idx]) are the semantic handles the agent uses instead of
 * raw coordinates (spec: SEMANTIC UI HANDLES). v1.3 adds visible center
 * coordinates so the agent can act on elements the tree under-describes
 * (video surfaces, custom canvases) exactly like a human finger would.
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

    /** True if this element can absorb a tap (interactive or has an on-screen rect). */
    val interactive: Boolean get() = clickable || editable || scrollable
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
    /**
     * Grounded, MobileAgent-style listing: every line carries the element's real
     * center coordinates, so the model can only ever reference things that ARE
     * on screen (anti-hallucination by construction).
     *
     * compact=true (sub-1B local models): drops decorative containers with no
     * text/description, caps text length and element count so the whole prompt
     * stays inside what a tiny model can actually reason over.
     */
    fun toCompact(maxElements: Int = 70, maxTextChars: Int = 60, compact: Boolean = false): String {
        val sb = StringBuilder()
        sb.append("APP: ").append(packageName ?: "unknown").append('\n')
        activityName?.let { sb.append("SCREEN: ").append(it.substringAfterLast('.')).append('\n') }
        val pool = if (compact) {
            elements.filter { it.interactive || !it.text.isNullOrBlank() || !it.desc.isNullOrBlank() }
        } else elements
        val shown = pool.take(maxElements)
        for (e in shown) {
            val bits = mutableListOf<String>()
            bits.add("role=${e.role}")
            e.text?.let { bits.add("text=\"${it.take(maxTextChars).replace("\n", " ")}\"") }
            if (e.desc != null && e.text == null) bits.add("desc=\"${e.desc.take(if (compact) 20 else 40)}\"")
            if (e.viewId != null && !compact) bits.add("id=${e.viewId.substringAfterLast('/').take(24)}")
            if (e.isPassword) bits.add("password=true")
            if (e.editable) bits.add("editable=true")
            if (e.scrollable) bits.add("scrollable=true")
            if (e.selected && !compact) bits.add("selected=true")
            sb.append("[").append(e.idx).append("] ").append(bits.joinToString(" "))
            sb.append(" center=(").append(e.centerX).append(",").append(e.centerY).append(")")
            sb.append('\n')
        }
        val hidden = if (compact) pool.size - shown.size else elements.size - shown.size
        if (hidden > 0) {
            sb.append("(+").append(hidden).append(" more elements hidden)\n")
        }
        return sb.toString()
    }

    /** Cheap change-detector: package + activity + first labels. Used to prove actions changed something. */
    fun fingerprint(): String = buildString {
        append(packageName ?: "?")
        activityName?.let { append('|').append(it.substringAfterLast('.')) }
        append('|')
        elements.take(14).forEach { append((it.text ?: it.desc ?: "").take(18)); append(',') }
    }

    fun findText(simple: String): ScreenElement? =
        elements.firstOrNull { it.text?.equals(simple, ignoreCase = true) == true || it.desc?.equals(simple, ignoreCase = true) == true }

    fun findContains(s: String): ScreenElement? =
        elements.firstOrNull { it.text?.contains(s, ignoreCase = true) == true || it.desc?.contains(s, ignoreCase = true) == true }
}
