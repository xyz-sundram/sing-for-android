package org.francasio.sing

import org.francasio.sing.provider.PatchDefaults
import org.junit.Assert.assertEquals
import org.junit.Test

class PatchDefaultsTest {
    @Test
    fun parsesPatcherSettingsWithoutLosingTypes() {
        val settings = PatchDefaults.parseSettings("""{"schema_version":1,"settings":{"selected_model":"kim_inst_onnx_fp32","thread_count":4,"preserve_side":false,"hardware_target":"CPU"}}""")
        assertEquals(4, settings["thread_count"])
        assertEquals(false, settings["preserve_side"])
        assertEquals("kim_inst_onnx_fp32", settings["selected_model"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedSchema() {
        PatchDefaults.parseSettings("""{"schema_version":2,"settings":{}}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsStringBooleans() {
        PatchDefaults.parseSettings("""{"schema_version":1,"settings":{"preserve_side":"false"}}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownKeys() {
        PatchDefaults.parseSettings("""{"schema_version":1,"settings":{"arbitrary":true}}""")
    }
}
