package com.scroblin.app.scroll

interface ScrollEngine {
    val syntheticGestureInFlight: Boolean

    fun setSpeedStep(step: Int)
    fun updatePixelsPerNotch(pixelsPerNotch: Float)
    fun resume()
    fun pause()
    fun stop()
}
