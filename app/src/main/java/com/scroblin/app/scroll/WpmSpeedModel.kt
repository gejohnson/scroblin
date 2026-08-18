package com.scroblin.app.scroll

import com.scroblin.app.overlay.AutoScrollState
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign

/**
 * Converts the knob's discrete signed steps into a reading-speed estimate.
 *
 * WPM is necessarily approximate because arbitrary apps have different type
 * sizes, line lengths, media, and spacing. Scroblin models one phone screen of
 * ordinary body text as roughly 90 words, then converts WPM into screen-heights
 * per minute. This produces a useful human-scale setting while keeping the
 * scrolling engine in physical pixels per second.
 */
object WpmSpeedModel {
    const val WORDS_PER_SCREEN = 90f
    private const val CURVE_STEEPNESS = 2.4f

    fun speedFraction(step: Int): Float {
        val magnitude = abs(step)
            .coerceIn(0, AutoScrollState.MAX_SPEED_STEP)
        if (magnitude == 0) return 0f

        val normalized = magnitude / AutoScrollState.MAX_SPEED_STEP.toFloat()
        val numerator = exp((CURVE_STEEPNESS * normalized).toDouble()) - 1.0
        val denominator = exp(CURVE_STEEPNESS.toDouble()) - 1.0
        return (numerator / denominator).toFloat().coerceIn(0f, 1f)
    }

    fun estimatedWpm(step: Int, maxWpm: Float): Float {
        if (step == 0 || maxWpm <= 0f) return 0f
        return step.sign.toFloat() * maxWpm * speedFraction(step)
    }

    fun targetPixelsPerSecond(
        step: Int,
        maxWpm: Float,
        screenHeightPixels: Int,
    ): Float {
        if (screenHeightPixels <= 0) return 0f
        val wordsPerSecond = estimatedWpm(step, maxWpm) / 60f
        val pixelsPerWord = screenHeightPixels / WORDS_PER_SCREEN
        return wordsPerSecond * pixelsPerWord
    }
}
