package com.jarvis.mobile.core.shizuku

/**
 * Pure-JVM decoding of the Android raw input stream (`getevent -t`) into touch
 * primitives - the AutoX/root-recorder technique made root-free through Shizuku.
 *
 * Every call is deterministic (no clocks, no Android classes) so the whole
 * pipeline is unit-testable: feed it real getevent transcripts.
 *
 * Protocol coverage:
 *  - Protocol B (multi-slot): ABS_MT_SLOT selects the slot, ABS_MT_TRACKING_ID
 *    -1 lifts it. Multi-touch: only the FIRST active slot is recorded (same
 *    decision AutoX makes) - two-finger gestures are not skill material.
 *  - Protocol A (legacy): no slots; all positions between SYN_REPORTs belong to
 *    the single active touch.
 *  - BTN_TOUCH (0001 014a) marks contact down/up on both protocols.
 *  - Type B stylus/tools (ABS_MT_TOOL_TYPE) are ignored: source is filtered by
 *    the probe picking the device with ABS_MT_POSITION_X/Y.
 */
object TouchStreamDecoder {

    // Linux input event types/codes (uapi/linux/input-event-codes.h).
    const val EV_SYN = 0x0000
    const val EV_KEY = 0x0001
    const val EV_ABS = 0x0003
    const val SYN_REPORT = 0
    const val BTN_TOUCH = 0x14A
    const val ABS_MT_SLOT = 0x2F
    const val ABS_MT_TRACKING_ID = 0x39
    const val ABS_MT_POSITION_X = 0x35
    const val ABS_MT_POSITION_Y = 0x36

    /** Minimum travel (px) between two emitted Move primitives (ink overlay). */
    const val MOVE_EMIT_PX = 6

    /** One parsed input line: `type code value` triple. */
    data class Event(val type: Int, val code: Int, val value: Long)

    sealed interface TouchEvent {
        data class Down(val x: Int, val y: Int) : TouchEvent
        data class Move(val x: Int, val y: Int) : TouchEvent
        data class Up(val x: Int, val y: Int, val durationMs: Long, val movedPx: Int) : TouchEvent
    }

    /** A touchscreen discovered by [parseDeviceProbe]. */
    data class TouchDevice(val path: String, val name: String, val maxX: Int, val maxY: Int)

    /**
     * Parse one getevent line. Returns null for non-event lines (device headers,
     * blanks). Handles both `[ ts] /dev/input/eventN: 0003 0035 000002d0` and the
     * bare `0003 0035 000002d0` continuation form. Values are 32-bit fields; hex
     * strings are interpreted as SIGNED 32-bit (getevent prints -1 as ffffffff,
     * and ABS_MT_TRACKING_ID -1 = "contact lifted" is the critical case).
     */
    fun parseLine(line: String): Event? {
        val body = line.substringAfter(": ").trim()
        val parts = body.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.size < 3) return null
        val type = parts[0].toIntOrNull(16) ?: parts[0].toIntOrNull() ?: return null
        val code = parts[1].toIntOrNull(16) ?: parts[1].toIntOrNull() ?: return null
        val raw = parts[2].toLongOrNull(16) ?: parts[2].toLongOrNull() ?: return null
        val value = if (raw > Int.MAX_VALUE) raw - 0x1_0000_0000L else raw
        return Event(type, code, value)
    }

    /**
     * Parse `getevent -pl` output and pick the touchscreen: the device whose
     * ABS block declares BOTH ABS_MT_POSITION_X and ABS_MT_POSITION_Y. Multiple
     * candidates -> the first with the largest X range (real digitizer).
     */
    fun parseDeviceProbe(output: String): TouchDevice? {
        data class Candidate(val path: String, var name: String, var maxX: Int = -1, var maxY: Int = -1)

        val candidates = ArrayList<Candidate>()
        var current: Candidate? = null
        var inAbs = false
        output.lines().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("add device ") -> {
                    val path = line.substringAfter(":").trim()
                    current = Candidate(path, "").also { candidates.add(it) }
                    inAbs = false
                }
                line.startsWith("name:") -> current?.let { c ->
                    c.name = line.substringAfter("name:").trim().trim('"')
                }
                line.startsWith("events:") -> inAbs = true
                line.startsWith("KEY(") || line.startsWith("REL(") || line.startsWith("MSC(") || line.startsWith("SW(") -> inAbs = false
            }
            if (inAbs && current != null) {
                when {
                    line.startsWith("ABS_MT_POSITION_X") -> current!!.maxX = rangeMax(line)
                    line.startsWith("ABS_MT_POSITION_Y") -> current!!.maxY = rangeMax(line)
                }
            }
        }
        return candidates
            .filter { it.maxX > 0 && it.maxY > 0 }
            .maxByOrNull { it.maxX }
            ?.let { TouchDevice(it.path, it.name, it.maxX, it.maxY) }
    }

    private fun rangeMax(line: String): Int {
        // "... : value 0, min 0, max 1079"  (max may be absent = single-value event)
        val m = Regex("max\\s+(\\d+)").find(line) ?: return -1
        return m.groupValues[1].toIntOrNull() ?: -1
    }
}

/**
 * Stateful state-machine converting decoded events to screen-space touch
 * primitives. ONE instance per recording session, fed in stream order.
 *
 * Raw digitizer units are scaled to real pixels using the device's ABS ranges
 * and the current display size (scrcpy's ScreenMetrics approach): the same
 * physical tap maps to the same pixel coordinates regardless of panel/resolution.
 */
class TouchStreamAnalyzer(
    private val screenWidthPx: Int,
    private val screenHeightPx: Int,
    private val deviceMaxX: Int,
    private val deviceMaxY: Int,
) {
    private var activeSlot = 0
    private val slotX = HashMap<Int, Int>()
    private val slotY = HashMap<Int, Int>()
    private val slotTracking = HashMap<Int, Long>()
    private var touchDown = false

    // First-active-slot tracking (multi-touch: ignore later fingers).
    private var primarySlot = -1
    private var downAt = 0L
    private var downX = 0
    private var downY = 0

    // Last emitted Move (drag visualization); re-arms on every new contact.
    private var lastEmitX = 0
    private var lastEmitY = 0

    /** Feed one line; returns touch primitives the recorder should act on. */
    fun onLine(line: String, nowMs: Long): List<TouchStreamDecoder.TouchEvent> {
        val ev = TouchStreamDecoder.parseLine(line) ?: return emptyList()
        val out = ArrayList<TouchStreamDecoder.TouchEvent>(1)
        when (ev.type) {
            TouchStreamDecoder.EV_ABS -> when (ev.code) {
                TouchStreamDecoder.ABS_MT_SLOT -> activeSlot = ev.value.toInt()
                TouchStreamDecoder.ABS_MT_TRACKING_ID -> {
                    if (ev.value >= 0) {
                        slotTracking[activeSlot] = ev.value
                    } else {
                        slotTracking.remove(activeSlot)
                        maybeContactEnd(activeSlot, nowMs, out)
                    }
                }
                TouchStreamDecoder.ABS_MT_POSITION_X -> {
                    slotX[activeSlot] = scaleX(ev.value)
                    maybeMove(activeSlot, out)
                }
                TouchStreamDecoder.ABS_MT_POSITION_Y -> {
                    slotY[activeSlot] = scaleY(ev.value)
                    maybeMove(activeSlot, out)
                }
            }
            TouchStreamDecoder.EV_KEY -> when (ev.code) {
                TouchStreamDecoder.BTN_TOUCH -> {
                    touchDown = ev.value == 1L
                    if (!touchDown && slotTracking.isNotEmpty()) {
                        // Protocol B lifts usually arrive via tracking id -1, but some
                        // devices only send BTN_TOUCH 0 - end on whichever we see.
                        val s = if (primarySlot >= 0) primarySlot else slotTracking.keys.min()
                        slotTracking.remove(s)
                        maybeContactEnd(s, nowMs, out)
                    }
                }
            }
            TouchStreamDecoder.EV_SYN -> { /* SYN_REPORT needs no extra logic here */ }
        }
        // Order-independent start: whether tracking id, position or BTN_TOUCH came
        // first, begin the contact as soon as ALL of (active contact, x, y) exist.
        tryContactStart(nowMs, out)
        return out
    }

    /** A gesture was in flight when the stream stopped: flush it (tap or cancel). */
    fun flush(nowMs: Long): List<TouchStreamDecoder.TouchEvent> {
        val s = primarySlot
        if (s < 0) return emptyList()
        val out = ArrayList<TouchStreamDecoder.TouchEvent>(1)
        maybeContactEnd(s, nowMs, out)
        return out
    }

    private fun tryContactStart(nowMs: Long, out: MutableList<TouchStreamDecoder.TouchEvent>) {
        if (primarySlot >= 0) return // second finger while one is down: ignored
        val slot = if (slotTracking.isNotEmpty()) slotTracking.keys.min() else if (touchDown) 0 else return
        val x = slotX[slot]
        val y = slotY[slot]
        if (x == null || y == null) return // position not known yet - wait for it
        primarySlot = slot
        downAt = nowMs
        downX = x; downY = y
        lastEmitX = x; lastEmitY = y
        out.add(TouchStreamDecoder.TouchEvent.Down(x, y))
    }

    /**
     * Live drag feedback: while the PRIMARY contact moves far enough, emit a
     * Move primitive (consumed by the on-screen ink overlay and ignored by
     * step classification, which works from Down/Up geometry).
     */
    private fun maybeMove(slot: Int, out: MutableList<TouchStreamDecoder.TouchEvent>) {
        if (slot != primarySlot) return
        val x = slotX[slot] ?: return
        val y = slotY[slot] ?: return
        val dx = x - lastEmitX
        val dy = y - lastEmitY
        if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) >= MOVE_EMIT_PX) {
            lastEmitX = x
            lastEmitY = y
            out.add(TouchStreamDecoder.TouchEvent.Move(x, y))
        }
    }

    private fun maybeContactEnd(slot: Int, nowMs: Long, out: MutableList<TouchStreamDecoder.TouchEvent>) {
        if (slot != primarySlot) return
        val durMs = (nowMs - downAt).coerceAtLeast(0)
        val x = slotX[slot] ?: downX
        val y = slotY[slot] ?: downY
        val moved = kotlin.math.max(kotlin.math.abs(x - downX), kotlin.math.abs(y - downY))
        primarySlot = -1
        // Clear the slot's positions: otherwise, when the lift arrives via
        // tracking-id -1 BEFORE BTN_TOUCH 0 (protocol B), the still-true touchDown
        // state would let tryContactStart re-fire a phantom DOWN with stale coords.
        slotX.remove(slot)
        slotY.remove(slot)
        out.add(TouchStreamDecoder.TouchEvent.Up(x, y, durMs, moved))
    }

    private fun scaleX(v: Long): Int = scale(v, deviceMaxX, screenWidthPx)
    private fun scaleY(v: Long): Int = scale(v, deviceMaxY, screenHeightPx)

    private fun scale(v: Long, rawMax: Int, outMax: Int): Int {
        if (rawMax <= 0 || outMax <= 0) return v.toInt()
        return ((v * (outMax.toLong() - 1)) / rawMax).toInt().coerceIn(0, outMax - 1)
    }
}
