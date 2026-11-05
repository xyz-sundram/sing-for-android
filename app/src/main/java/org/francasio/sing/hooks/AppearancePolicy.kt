package org.francasio.sing.hooks

/** Resource-role selection stays narrow: never recolor artwork or neutral labels. */
internal object AppearancePolicy {
    const val APPLE_RED = 0xFFFA233B.toInt()
    private val stockAccentRgb = setOf(0x0088FF, 0x0091FF, 0x007AFF, 0x0A84FF)

    fun tint(color: Int): Int = if ((color and 0x00FFFFFF) in stockAccentRgb) {
        (color and 0xFF000000.toInt()) or (APPLE_RED and 0x00FFFFFF)
    } else color

    fun isGlassBackground(name: String, childCount: Int): Boolean =
        childCount == 0 && name in setOf("bottom_nav_blur", "mini_player_blur")
}
