package org.francasio.sing.audio

import kotlin.math.exp

internal object VocalLevel {
    fun normalize(value: Float): Float = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f

    /** Apply a 30 ms smoothing constant to audio actually emitted, including drains. */
    fun smooth(previous: Float, target: Float, frames: Int): Float {
        val current = normalize(previous)
        if (frames <= 0) return current
        val gain = (1.0 - exp(-frames.toDouble() / (44100.0 * 0.030))).toFloat()
        return current + gain * (normalize(target) - current)
    }
}
