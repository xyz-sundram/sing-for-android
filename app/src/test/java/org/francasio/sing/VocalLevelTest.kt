package org.francasio.sing

import org.francasio.sing.audio.VocalLevel
import org.junit.Assert.*
import org.junit.Test

class VocalLevelTest {
    @Test fun smallSliderMovementCannotBecomeFullVocalGain() {
        var gain = 0f
        repeat(100) {
            gain = VocalLevel.smooth(gain, 0.01f, 1024)
            assertTrue(gain in 0f..0.01f)
        }
        assertEquals(0.01f, gain, 0.000001f)
    }

    @Test fun smoothingDependsOnOutputDurationRatherThanInputBatching() {
        val single = VocalLevel.smooth(0f, 0.6f, 4096)
        var split = 0f
        repeat(4) { split = VocalLevel.smooth(split, 0.6f, 1024) }
        assertEquals(single, split, 0.000001f)
        assertEquals(split, VocalLevel.smooth(split, 1f, 0), 0f)
    }

    @Test fun invalidAndOutOfRangeLevelsAreBounded() {
        assertEquals(0f, VocalLevel.normalize(Float.NaN), 0f)
        assertEquals(0f, VocalLevel.normalize(Float.POSITIVE_INFINITY), 0f)
        assertEquals(0f, VocalLevel.normalize(-0.5f), 0f)
        assertEquals(1f, VocalLevel.normalize(1.5f), 0f)
        assertEquals(0.25f, VocalLevel.normalize(0.25f), 0f)
    }
}
