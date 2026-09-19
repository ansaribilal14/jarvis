package com.jarvis.mobile

import com.jarvis.mobile.core.shizuku.TouchStreamAnalyzer
import com.jarvis.mobile.core.shizuku.TouchStreamDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Raw touch-stream decoding - the precision layer of the v2.0 recorder.
 * Inputs are real-world `getevent -t` transcripts (Protocol B multi-slot).
 */
class TouchStreamDecoderTest {

    private val W = 1080
    private val H = 2400
    private val MAXX = 1079
    private val MAXY = 2399

    private fun decoder() = TouchStreamAnalyzer(W, H, MAXX, MAXY)

    // ------------------------------------------------------------- line parsing

    @Test
    fun `parses getevent transcript line`() {
        val ev = TouchStreamDecoder.parseLine("[ 12345.678901] /dev/input/event5: 0003 0035 000002d0")
        assertNotNull(ev)
        assertEquals(TouchStreamDecoder.EV_ABS, ev!!.type)
        assertEquals(TouchStreamDecoder.ABS_MT_POSITION_X, ev.code)
        assertEquals(0x2d0L, ev.value)
    }

    @Test
    fun `ignores non-event lines`() {
        assertNull(TouchStreamDecoder.parseLine("add device 3: /dev/input/event5"))
        assertNull(TouchStreamDecoder.parseLine(""))
        assertNull(TouchStreamDecoder.parseLine("  name: \"goodix-ts\"  "))
    }

    @Test
    fun `ffffffff parses as -1 (tracking id lift)`() {
        val ev = TouchStreamDecoder.parseLine("0003 0039 ffffffff")
        assertNotNull(ev)
        assertEquals(-1L, ev!!.value)
        // Large-but-positive 32-bit values stay positive
        assertEquals(0x7ffffffL, TouchStreamDecoder.parseLine("0003 0035 07ffffff")!!.value)
    }

    // ------------------------------------------------------------ device probe

    @Test
    fun `picks the touchscreen from getevent -pl output`() {
        val transcript = """
            add device 1: /dev/input/event0
              name:     "qpnp_pon"
              events:
                KEY (0001): KEY_POWER
            add device 2: /dev/input/event1
              name:     "flash_led"
              events:
                KEY (0001): KEY_CAMERA
            add device 3: /dev/input/event4
              name:     "goodix-ts"
              events:
                ABS (0003): ABS_MT_SLOT         : value 0, min 0, max 9
                            ABS_MT_POSITION_X   : value 0, min 0, max 1079
                            ABS_MT_POSITION_Y   : value 0, min 0, max 2399
                            ABS_MT_TRACKING_ID  : value 0, min 0, max 65535
        """.trimIndent()
        val dev = TouchStreamDecoder.parseDeviceProbe(transcript)
        assertNotNull(dev)
        assertEquals("/dev/input/event4", dev!!.path)
        assertEquals("goodix-ts", dev.name)
        assertEquals(1079, dev.maxX)
        assertEquals(2399, dev.maxY)
    }

    // --------------------------------------------------------- tap classification

    private val tapTranscript = listOf(
        "0003 002f 00000000", // ABS_MT_SLOT 0
        "0003 0039 0000007b", // ABS_MT_TRACKING_ID 123
        "0003 0035 000002d0", // X raw 720
        "0003 0036 000004b0", // Y raw 1200
        "0001 014a 00000001", // BTN_TOUCH 1
        "0000 0000 00000000", // SYN_REPORT
        "0003 0039 ffffffff", // TRACKING_ID -1 (lift)
        "0001 014a 00000000", // BTN_TOUCH 0
        "0000 0000 00000000", // SYN_REPORT
    )

    @Test
    fun `a short contact becomes Down then Up`() {
        val d = decoder()
        val downOut = tapTranscript.take(5).flatMap { d.onLine(it, 1000L) }
        assertEquals(1, downOut.size)
        val down = downOut[0] as TouchStreamDecoder.TouchEvent.Down
        assertEquals(720, down.x) // raw 0x2d0=720 on a 1079-range 1080px screen -> identity
        assertEquals(1200, down.y)
        val upOut = tapTranscript.drop(5).flatMap { d.onLine(it, 1150L) }
        assertEquals(1, upOut.size)
        val up = upOut[0] as TouchStreamDecoder.TouchEvent.Up
        assertEquals(150L, up.durationMs)
        assertEquals(0, up.movedPx)
    }

    @Test
    fun `raw units are scaled to screen pixels`() {
        // Device range 0..1079 on a 1080px screen: identity. Now a W=540 screen:
        val d = TouchStreamAnalyzer(540, 1200, 1079, 2399)
        val out = d.onLine("0003 0035 000002d0", 1L) + d.onLine("0003 0036 000004b0", 1L) +
            d.onLine("0003 0039 00000001", 1L) + d.onLine("0001 014a 00000001", 1L)
        val down = out.filterIsInstance<TouchStreamDecoder.TouchEvent.Down>().first()
        assertEquals(359, down.x) // floor(720 * (540-1) / 1079)
        assertEquals(599, down.y) // floor(1200 * (1200-1) / 2399)
    }

    // --------------------------------------------------- multi-touch: first finger

    @Test
    fun `second finger is ignored while the first is down`() {
        val d = decoder()
        val first = listOf(
            "0003 002f 00000000", // SLOT 0
            "0003 0039 00000001", // TRACKING_ID
            "0003 0035 00000064", // x=100
            "0003 0036 000000c8", // y=200
            "0001 014a 00000001", // BTN_TOUCH 1
        ).flatMap { d.onLine(it, 1L) }
        assertEquals(1, first.size) // exactly one Down for slot 0
        assertEquals(100, (first[0] as TouchStreamDecoder.TouchEvent.Down).x)

        // second finger: slot 1 gets its own tracking id + position
        d.onLine("0003 002f 00000001", 2L)
        d.onLine("0003 0039 00000002", 2L)
        d.onLine("0003 0035 00000190", 2L)
        d.onLine("0003 0036 000001f4", 2L)
        assertEquals(0, d.onLine("0000 0000 00000000", 2L).size)

        // second finger lifts: tracking id -1 on slot 1 -> NOT our primary
        d.onLine("0003 002f 00000001", 3L)
        val lift2 = d.onLine("0003 0039 ffffffff", 3L)
        assertTrue(lift2.isEmpty())

        // first finger lifts
        d.onLine("0003 002f 00000000", 4L)
        val lift1 = d.onLine("0003 0039 ffffffff", 4L)
        assertEquals(1, lift1.size)
        val up = lift1[0] as TouchStreamDecoder.TouchEvent.Up
        assertEquals(100, up.x)
    }

    // ------------------------------------------------------------------- flush

    @Test
    fun `flush emits the pending Up for a contact still down`() {
        val d = decoder()
        d.onLine("0003 0039 00000001", 1L)
        d.onLine("0003 0035 00000040", 1L)
        d.onLine("0003 0036 00000080", 1L)
        d.onLine("0001 014a 00000001", 1L)
        val flushed = d.flush(900L)
        assertEquals(1, flushed.size)
        val up = flushed[0] as TouchStreamDecoder.TouchEvent.Up
        assertEquals(899L, up.durationMs)
    }

    // ------------------------------------------------- swipe leaves a moved Up

    @Test
    fun `a swipe reports real movement`() {
        val d = decoder()
        val out = listOf(
            "0003 002f 00000000", // SLOT 0
            "0003 0039 00000005", // TRACKING_ID
            "0003 0035 000002bc", // x 700
            "0003 0036 00000898", // y 2200
            "0001 014a 00000001", // BTN_TOUCH 1
            "0003 0035 00000064", // x 100 (moved far left)
            "0003 0036 00000384", // y 900
            "0003 0039 ffffffff", // lift
            "0001 014a 00000000", // BTN_TOUCH 0
        ).flatMap { d.onLine(it, 1L) }
        val up = out.filterIsInstance<TouchStreamDecoder.TouchEvent.Up>().first()
        assertTrue(up.movedPx > 400)
    }

    // ------------------------------------------- live drag visualization (v2.1)

    @Test
    fun `primary contact movement emits Move primitives`() {
        val d = decoder()
        val down = listOf(
            "0003 002f 00000000",
            "0003 0039 00000001",
            "0003 0035 000002d0", // x 720
            "0003 0036 000004b0", // y 1200
            "0001 014a 00000001",
        ).flatMap { d.onLine(it, 1L) }
        assertEquals(1, down.size) // exactly the Down

        // tiny jitter (2px): below the 6px threshold -> no Move
        val jitter = d.onLine("0003 0035 000002d2", 2L) // x 722
        assertTrue(jitter.isEmpty())

        // real drag: x 720->300, y 1200->1180 -> Moves carrying the new position
        val moves = d.onLine("0003 0035 0000012c", 3L) + d.onLine("0003 0036 0000049c", 3L) // x 300, y 1180
        assertTrue(moves.isNotEmpty())
        val move = moves.filterIsInstance<TouchStreamDecoder.TouchEvent.Move>().last()
        assertEquals(300, move.x)
        assertEquals(1180, move.y)
    }

    @Test
    fun `move re-arms on every new contact`() {
        val d = decoder()
        // contact 1: down + drag + lift
        listOf(
            "0003 002f 00000000",
            "0003 0039 00000001",
            "0003 0035 00000190", // x 400
            "0003 0036 00000190", // y 400
            "0001 014a 00000001",
            "0003 0035 000000fa", // x 250
            "0003 0039 ffffffff",
            "0001 014a 00000000",
        ).forEach { d.onLine(it, 1L) }
        // contact 2: down at the same point - Move state must have reset, no stale emits
        val second = listOf(
            "0003 0039 00000002",
            "0003 0035 00000190",
            "0003 0036 00000190",
            "0001 014a 00000001",
        ).flatMap { d.onLine(it, 50L) }
        assertTrue(second.none { it is TouchStreamDecoder.TouchEvent.Move })
        assertEquals(1, second.size)
    }

    @Test
    fun `second-finger movement emits nothing`() {
        val d = decoder()
        // primary finger down (slot 0)
        listOf(
            "0003 002f 00000000",
            "0003 0039 00000001",
            "0003 0035 00000064",
            "0003 0036 000000c8",
            "0001 014a 00000001",
        ).forEach { d.onLine(it, 1L) }
        // second finger (slot 1) moves
        d.onLine("0003 002f 00000001", 2L)
        val moves = d.onLine("0003 0035 00000190", 2L) + d.onLine("0003 0036 000001f4", 2L)
        assertTrue(moves.filterIsInstance<TouchStreamDecoder.TouchEvent.Move>().isEmpty())
    }
}
