package com.scroblin.app.overlay

enum class OverlayGesture {
    UNDECIDED,
    SPEED,
    MENU,
    FLIP,
    MOVE,
    CANCELLED,
}

interface SpeedKnobListener {
    fun onOutsideTouch()
    fun onOverlayTouchStarted()
    fun onOverlayTouchEnded()
    fun onSpeedStepChanged(step: Int)
    fun onPauseRequested()
    fun onResumeRequested()
    fun onResetToZeroRequested()
    fun onFlipToGear()
    fun onFlipToKnob()
    fun onGearTapped()
    fun onPickupStarted(rawX: Float, rawY: Float)
    fun onPositionDragged(rawX: Float, rawY: Float)
    fun onPositionPlaced()
}
