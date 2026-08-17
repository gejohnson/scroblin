package com.scroblin.app.scroll

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.scroblin.app.overlay.AutoScrollState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

class GestureScrollEngine(
    private val service: AccessibilityService,
    private val profile: GestureProfile = GestureProfile(),
    private val onGestureCancelled: () -> Unit,
) : ScrollEngine {
    private val mainHandler = Handler(Looper.getMainLooper())

    private var speedStep = 0
    private var pixelsPerNotch = 0f
    private var running = false
    private var stopped = false

    @Volatile
    override var syntheticGestureInFlight: Boolean = false
        private set

    override fun setSpeedStep(step: Int) {
        runOnMain {
            speedStep = step.coerceIn(
                -AutoScrollState.MAX_SPEED_STEP,
                AutoScrollState.MAX_SPEED_STEP,
            )
            if (speedStep == 0) {
                running = false
            }
        }
    }

    override fun updatePixelsPerNotch(pixelsPerNotch: Float) {
        runOnMain {
            this.pixelsPerNotch = pixelsPerNotch.coerceAtLeast(0f)
            if (this.pixelsPerNotch == 0f) {
                running = false
            }
        }
    }

    override fun resume() {
        runOnMain {
            if (stopped || speedStep == 0 || pixelsPerNotch == 0f) return@runOnMain
            running = true
            if (!syntheticGestureInFlight) {
                dispatchScrollCycle()
            }
        }
    }

    override fun pause() {
        runOnMain {
            running = false
        }
    }

    override fun stop() {
        runOnMain {
            running = false
            stopped = true
        }
    }

    private fun dispatchScrollCycle() {
        if (!running || stopped || speedStep == 0 || syntheticGestureInFlight) return

        val geometry = calculateGeometry() ?: run {
            running = false
            onGestureCancelled()
            return
        }

        syntheticGestureInFlight = true
        Log.d(
            TAG,
            "synthetic gesture start step=$speedStep target=${geometry.targetPixelsPerSecond.toInt()}px/s",
        )

        val primePath = Path().apply {
            moveTo(geometry.start.x, geometry.start.y)
            lineTo(geometry.escaped.x, geometry.escaped.y)
        }
        val primeStroke = GestureDescription.StrokeDescription(
            primePath,
            0L,
            profile.initialEscapeDurationMs,
            true,
        )

        val dispatched = service.dispatchGesture(
            GestureDescription.Builder().addStroke(primeStroke).build(),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    dispatchContinuation(primeStroke, geometry)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    finishCancelled("prime cancelled")
                }
            },
            mainHandler,
        )

        if (!dispatched) {
            finishCancelled("prime rejected")
        }
    }

    private fun dispatchContinuation(
        primeStroke: GestureDescription.StrokeDescription,
        geometry: ScrollGeometry,
    ) {
        val shouldContinueScrolling = running && !stopped && speedStep != 0
        val end = if (shouldContinueScrolling) {
            geometry.end
        } else {
            PointF(
                geometry.escaped.x,
                geometry.escaped.y + geometry.direction * RELEASE_DISTANCE_PX,
            )
        }
        val duration = if (shouldContinueScrolling) geometry.durationMs else RELEASE_DURATION_MS

        val steadyPath = Path().apply {
            moveTo(geometry.escaped.x, geometry.escaped.y)
            lineTo(end.x, end.y)
        }
        val continuation = primeStroke.continueStroke(
            steadyPath,
            0L,
            duration,
            false,
        )

        val dispatched = service.dispatchGesture(
            GestureDescription.Builder().addStroke(continuation).build(),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    syntheticGestureInFlight = false
                    Log.d(TAG, "synthetic gesture completed")
                    if (running && !stopped && speedStep != 0) {
                        dispatchScrollCycle()
                    }
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    finishCancelled("continuation cancelled")
                }
            },
            mainHandler,
        )

        if (!dispatched) {
            finishCancelled("continuation rejected")
        }
    }

    private fun finishCancelled(reason: String) {
        val wasRunning = running
        syntheticGestureInFlight = false
        running = false
        Log.d(TAG, "synthetic gesture stopped: $reason")
        if (wasRunning && !stopped) {
            onGestureCancelled()
        }
    }

    private fun calculateGeometry(): ScrollGeometry? {
        val metrics = currentDisplayMetrics()
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return null

        val direction = if (speedStep > 0) -1f else 1f
        val x = metrics.widthPixels * 0.5f
        val startY = if (direction < 0f) {
            metrics.heightPixels * profile.startYFraction
        } else {
            metrics.heightPixels * profile.endYFraction
        }
        val limitY = if (direction < 0f) {
            metrics.heightPixels * profile.endYFraction
        } else {
            metrics.heightPixels * profile.startYFraction
        }
        val escapeDistance = profile.initialEscapeDistanceDp * metrics.density
        val escapedY = startY + direction * escapeDistance
        val availableDistance = abs(limitY - escapedY)
        if (availableDistance <= 1f) return null

        val targetPixelsPerSecond = targetPixelsPerSecond()
        val desiredDistance = targetPixelsPerSecond * profile.targetCycleDurationMs / 1_000f
        val minimumDistance = profile.minStrokeDistanceDp * metrics.density
        val distance = min(availableDistance, max(minimumDistance, desiredDistance))
        val durationMs = (distance / targetPixelsPerSecond * 1_000f)
            .roundToLong()
            .coerceIn(MINIMUM_STROKE_DURATION_MS, profile.maxStrokeDurationMs)

        return ScrollGeometry(
            start = PointF(x, startY),
            escaped = PointF(x, escapedY),
            end = PointF(x, escapedY + direction * distance),
            direction = direction,
            durationMs = durationMs,
            targetPixelsPerSecond = targetPixelsPerSecond,
        )
    }

    private fun targetPixelsPerSecond(): Float {
        val absoluteStep = abs(speedStep).coerceIn(1, AutoScrollState.MAX_SPEED_STEP)
        return absoluteStep * pixelsPerNotch
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayMetrics(): DisplayMetrics {
        val windowManager = service.getSystemService(WindowManager::class.java)
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            DisplayMetrics().apply {
                widthPixels = bounds.width()
                heightPixels = bounds.height()
                density = service.resources.displayMetrics.density
                densityDpi = service.resources.displayMetrics.densityDpi
            }
        } else {
            DisplayMetrics().also(windowManager.defaultDisplay::getRealMetrics)
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private data class ScrollGeometry(
        val start: PointF,
        val escaped: PointF,
        val end: PointF,
        val direction: Float,
        val durationMs: Long,
        val targetPixelsPerSecond: Float,
    )

    companion object {
        private const val TAG = "AutoScroll"
        private const val MINIMUM_STROKE_DURATION_MS = 80L
        private const val RELEASE_DURATION_MS = 10L
        private const val RELEASE_DISTANCE_PX = 1f
    }
}
