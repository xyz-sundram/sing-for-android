package org.francasio.sing

import org.francasio.sing.hooks.AppearancePolicy
import org.junit.Assert.*
import org.junit.Test

class AppearancePolicyTest {
    @Test fun recolorsKnownAppleAccentsAndPreservesDisabledAlpha() {
        assertEquals(AppearancePolicy.APPLE_RED, AppearancePolicy.tint(0xFF0088FF.toInt()))
        assertEquals(AppearancePolicy.APPLE_RED, AppearancePolicy.tint(0xFF0091FF.toInt()))
        assertEquals(0x40FA233B, AppearancePolicy.tint(0x400091FF))
    }
    @Test fun neutralLabelsAndOtherColorsRemainIntact() {
        for (color in intArrayOf(0xFFFFFFFF.toInt(), 0xFF808080.toInt(), 0xFF000000.toInt(), 0xFF2266AA.toInt(), 0xFFFA233B.toInt())) {
            assertEquals(color, AppearancePolicy.tint(color))
        }
    }
    @Test fun glassTargetsOnlySeparateChildlessNativeBackgrounds() {
        assertTrue(AppearancePolicy.isGlassBackground("bottom_nav_blur", 0))
        assertTrue(AppearancePolicy.isGlassBackground("mini_player_blur", 0))
        assertFalse(AppearancePolicy.isGlassBackground("bottom_nav_blur", 4))
        assertFalse(AppearancePolicy.isGlassBackground("mini_player_content", 0))
        assertFalse(AppearancePolicy.isGlassBackground("lyrics_main_content", 0))
    }
}
