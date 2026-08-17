package com.gejohnson.scroblin.overlay

import kotlin.math.abs

enum class OverlayFace {
    KNOB,
    GEAR,
}

data class AutoScrollState(
    val speedStep: Int = 0,
    val paused: Boolean = true,
    val face: OverlayFace = OverlayFace.KNOB,
    val pickedUp: Boolean = false,
    val keyboardVisible: Boolean = false,
    val homeScreenVisible: Boolean = false,
    val xFraction: Float = DEFAULT_X_FRACTION,
    val yFraction: Float = DEFAULT_Y_FRACTION,
) {
    val magnitude: Float
        get() = abs(speedStep) / MAX_SPEED_STEP.toFloat()

    val isScrolling: Boolean
        get() = !paused && speedStep != 0 && !keyboardVisible && !homeScreenVisible

    companion object {
        const val STEPS_PER_REVOLUTION = 40
        const val MAX_SPEED_STEP = STEPS_PER_REVOLUTION
        const val DEFAULT_X_FRACTION = 0.90f
        const val DEFAULT_Y_FRACTION = 0.40f
    }
}
