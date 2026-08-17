package com.scroblin.app.scroll

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Prefers granular accessibility-node scrolling when the foreground app exposes
 * it, and falls back to synthetic touch gestures everywhere else.
 */
class HybridScrollEngine(
    service: AccessibilityService,
    onGestureCancelled: () -> Unit,
) : ScrollEngine {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val gestureEngine = GestureScrollEngine(service, onGestureCancelled = onGestureCancelled)
    private val nodeEngine = NodeScrollEngine(service) {
        mainHandler.post { switchToGestureFallback() }
    }

    private var speedStep = 0
    private var pixelsPerNotch = 0f
    private var desiredRunning = false
    private var stopped = false
    private var strategy: Strategy? = null

    override val syntheticGestureInFlight: Boolean
        get() = gestureEngine.syntheticGestureInFlight

    override fun setSpeedStep(step: Int) {
        runOnMain {
            speedStep = step
            nodeEngine.setSpeedStep(step)
            gestureEngine.setSpeedStep(step)
            if (desiredRunning) chooseStrategyAndResume()
        }
    }

    override fun updatePixelsPerNotch(pixelsPerNotch: Float) {
        runOnMain {
            this.pixelsPerNotch = pixelsPerNotch
            nodeEngine.updatePixelsPerNotch(pixelsPerNotch)
            gestureEngine.updatePixelsPerNotch(pixelsPerNotch)
            if (desiredRunning) chooseStrategyAndResume()
        }
    }

    override fun resume() {
        runOnMain {
            if (stopped || speedStep == 0 || pixelsPerNotch <= 0f) return@runOnMain
            desiredRunning = true
            chooseStrategyAndResume()
        }
    }

    override fun pause() {
        runOnMain {
            desiredRunning = false
            nodeEngine.pause()
            gestureEngine.pause()
        }
    }

    override fun stop() {
        runOnMain {
            desiredRunning = false
            stopped = true
            nodeEngine.stop()
            gestureEngine.stop()
        }
    }

    /** Re-evaluate after app/window changes while preserving the user's run state. */
    fun refreshForForegroundWindow() {
        runOnMain {
            if (desiredRunning && !stopped) chooseStrategyAndResume(forceReevaluate = true)
        }
    }

    private fun chooseStrategyAndResume(forceReevaluate: Boolean = false) {
        if (!desiredRunning || stopped || speedStep == 0 || pixelsPerNotch <= 0f) return

        val desiredStrategy = if (nodeEngine.canHandleCurrentWindow()) {
            Strategy.NODE
        } else {
            Strategy.GESTURE
        }

        if (forceReevaluate || strategy != desiredStrategy) {
            nodeEngine.pause()
            gestureEngine.pause()
            strategy = desiredStrategy
            Log.d(TAG, "scroll strategy=${desiredStrategy.name}")
        }

        when (desiredStrategy) {
            Strategy.NODE -> nodeEngine.resume()
            Strategy.GESTURE -> gestureEngine.resume()
        }
    }

    private fun switchToGestureFallback() {
        if (!desiredRunning || stopped) return
        nodeEngine.pause()
        strategy = Strategy.GESTURE
        Log.d(TAG, "scroll strategy=GESTURE (node fallback)")
        gestureEngine.resume()
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private enum class Strategy {
        NODE,
        GESTURE,
    }

    companion object {
        private const val TAG = "AutoScroll"
    }
}
