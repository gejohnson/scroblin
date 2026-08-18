package com.scroblin.app.scroll

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import kotlin.math.abs

/**
 * Adaptive scroll engine used by the accessibility service.
 *
 * On API 35+ it prefers accessibility-node scrolling when the foreground view
 * advertises granular scrolling support. Otherwise it falls back to synthetic
 * touch gestures targeted at the largest visible scrollable node.
 */
class GestureScrollEngine(
    private val service: AccessibilityService,
    onGestureCancelled: () -> Unit,
) : ScrollEngine {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val touchEngine = TouchGestureScrollEngine(
        service = service,
        onGestureCancelled = onGestureCancelled,
    )
    private val nodeEngine = NodeScrollEngine(service) {
        mainHandler.post { switchToTouchFallback() }
    }

    private var speedStep = 0
    private var maxWpm = 0f
    private var desiredRunning = false
    private var stopped = false
    private var strategy: Strategy? = null

    override val syntheticGestureInFlight: Boolean
        get() = touchEngine.syntheticGestureInFlight

    override fun setSpeedStep(step: Int) {
        runOnMain {
            speedStep = step
            nodeEngine.setSpeedStep(step)
            touchEngine.setSpeedStep(step)
            updateChildSpeedScale()
            if (desiredRunning) chooseStrategyAndResume()
        }
    }

    override fun updatePixelsPerNotch(pixelsPerNotch: Float) {
        runOnMain {
            maxWpm = pixelsPerNotch.coerceAtLeast(0f)
            updateChildSpeedScale()
            if (desiredRunning) chooseStrategyAndResume()
        }
    }

    override fun resume() {
        runOnMain {
            if (stopped || speedStep == 0 || maxWpm <= 0f) return@runOnMain
            desiredRunning = true
            updateChildSpeedScale()
            chooseStrategyAndResume()
        }
    }

    override fun pause() {
        runOnMain {
            desiredRunning = false
            nodeEngine.pause()
            touchEngine.pause()
        }
    }

    override fun stop() {
        runOnMain {
            desiredRunning = false
            stopped = true
            nodeEngine.stop()
            touchEngine.stop()
        }
    }

    private fun chooseStrategyAndResume() {
        if (!desiredRunning || stopped || speedStep == 0 || maxWpm <= 0f) return

        val desiredStrategy = if (nodeEngine.canHandleCurrentWindow()) {
            Strategy.NODE
        } else {
            Strategy.TOUCH
        }

        if (strategy != desiredStrategy) {
            nodeEngine.pause()
            touchEngine.pause()
            strategy = desiredStrategy
        }

        when (desiredStrategy) {
            Strategy.NODE -> nodeEngine.resume()
            Strategy.TOUCH -> touchEngine.resume()
        }
    }

    private fun switchToTouchFallback() {
        if (!desiredRunning || stopped) return
        nodeEngine.pause()
        strategy = Strategy.TOUCH
        touchEngine.resume()
    }

    private fun updateChildSpeedScale() {
        val magnitude = abs(speedStep)
        val targetPixelsPerSecond = abs(
            WpmSpeedModel.targetPixelsPerSecond(
                step = speedStep,
                maxWpm = maxWpm,
                screenHeightPixels = service.resources.displayMetrics.heightPixels,
            ),
        )
        val effectivePixelsPerNotch = if (magnitude > 0) {
            targetPixelsPerSecond / magnitude
        } else {
            0f
        }
        nodeEngine.updatePixelsPerNotch(effectivePixelsPerNotch)
        touchEngine.updatePixelsPerNotch(effectivePixelsPerNotch)
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private enum class Strategy {
        NODE,
        TOUCH,
    }
}
