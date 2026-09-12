package com.jarvis.mobile

import com.jarvis.mobile.core.model.DeviceProfiler
import com.jarvis.mobile.core.model.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRecommenderTest {

    private fun profile(totalRamGb: Double, cores: Int = 8): DeviceProfiler.Profile =
        DeviceProfiler.Profile(
            model = "Test", androidVersion = "14", sdkInt = 34, abi = "arm64-v8a",
            cores = cores, totalRamGb = totalRamGb, availRamGb = totalRamGb / 2,
            appHeapGb = 0.5, vulkan = true, storageFreeGb = 50.0,
            deviceClass = when {
                totalRamGb >= 8 -> DeviceProfiler.DeviceClass.HIGH_END
                totalRamGb >= 6 -> DeviceProfiler.DeviceClass.POWER
                totalRamGb >= 4 -> DeviceProfiler.DeviceClass.STANDARD
                else -> DeviceProfiler.DeviceClass.BASIC
            },
            thermalStatus = "none",
        )

    @Test
    fun `basic device gets smallest model`() {
        val rec = ModelCatalog.recommendFor(profile(2.0))
        assertEquals("smollm2-360m", rec.id)
    }

    @Test
    fun `low ram device gets the 1b speed sweet spot`() {
        val rec = ModelCatalog.recommendFor(profile(4.0))
        assertEquals("llama32-1b", rec.id)
    }

    @Test
    fun `standard device gets a mid model`() {
        val rec = ModelCatalog.recommendFor(profile(5.0))
        assertTrue(listOf("smollm2-17b", "qwen25-15b").contains(rec.id))
    }

    @Test
    fun `high end device gets a 3b model`() {
        val rec = ModelCatalog.recommendFor(profile(12.0))
        assertTrue(listOf("qwen25-3b", "llama32-3b").contains(rec.id))
        // Prefer Qwen 3B for tool calling on ties.
        assertEquals("qwen25-3b", rec.id)
    }

    @Test
    fun `lighter-than ladder descends`() {
        val m = ModelCatalog.byId("qwen25-3b")!!
        val next = ModelCatalog.lighterThan(m)!!
        assertTrue(next.ramNeededGb < m.ramNeededGb)
    }
}
