package com.scroblin.app.scroll

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import com.scroblin.app.overlay.AutoScrollState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

class TouchGestureScrollEngine(
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
                    finishCancelled()
                }
            },
            mainHandler,
        )

        if (!dispatched) {
            finishCancelled()
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
            shouldContinueScrolling,
        )

        val dispatched = service.dispatchGesture(
            GestureDescription.Builder().addStroke(continuation).build(),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (shouldContinueScrolling) {
                        dispatchBrake(continuation, geometry)
                    } else {
                        finishCompletedCycle()
                    }
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    finishCancelled()
                }
            },
            mainHandler,
        )

        if (!dispatched) {
            finishCancelled()
        }
    }

    private fun dispatchBrake(
        steadyStroke: GestureDescription.StrokeDescription,
        geometry: ScrollGeometry,
    ) {
        val brakeEnd = PointF(
            geometry.end.x,
            geometry.end.y + geometry.direction * BRAKE_DISTANCE_PX,
        )
        val brakePath = Path().apply {
            moveTo(geometry.end.x, geometry.end.y)
            lineTo(brakeEnd.x, brakeEnd.y)
        }
        val brakeStroke = steadyStroke.continueStroke(
            brakePath,
            0L,
            BRAKE_DURATION_MS,
            false,
        )

        val dispatched = service.dispatchGesture(
            GestureDescription.Builder().addStroke(brakeStroke).build(),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    finishCompletedCycle()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    finishCancelled()
                }
            },
            mainHandler,
        )

        if (!dispatched) {
            finishCancelled()
        }
    }

    private fun finishCompletedCycle() {
        syntheticGestureInFlight = false
        if (running && !stopped && speedStep != 0) {
            dispatchScrollCycle()
        }
    }

    private fun finishCancelled() {
        val wasRunning = running
        syntheticGestureInFlight = false
        running = false
        if (wasRunning && !stopped) {
            onGestureCancelled()
        }
    }

    private fun calculateGeometry(): ScrollGeometry? {
        val metrics = currentDisplayMetrics()
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return null

        val screenBounds = Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        val targetBounds = findBestScrollableBounds(screenBounds) ?: screenBounds
        if (targetBounds.width() <= 1 || targetBounds.height() <= 1) return null

        val direction = if (speedStep > 0) -1f else 1f
        val x = targetBounds.exactCenterX()
        val startY = if (direction < 0f) {
            targetBounds.top + targetBounds.height() * profile.startYFraction
        } else {
            targetBounds.top + targetBounds.height() * profile.endYFraction
        }
        val limitY = if (direction < 0f) {
            targetBounds.top + targetBounds.height() * profile.endYFraction
        } else {
            targetBounds.top + targetBounds.height() * profile.startYFraction
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
        )
    }

    private fun findBestScrollableBounds(screenBounds: Rect): Rect? {
        val root = service.rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var bestBounds: Rect? = null
        var bestScore = 0L
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_TREE_NODES) {
            val node = queue.removeFirst()
            visited += 1

            if (node.isVisibleToUser && hasVerticalScrollAction(node)) {
                val bounds = Rect().also(node::getBoundsInScreen)
                if (bounds.intersect(screenBounds) && bounds.width() > 0 && bounds.height() > 0) {
                    val area = bounds.width().toLong() * bounds.height().toLong()
                    val score = area + if (node.isScrollable) SCROLLABLE_BONUS else 0L
                    if (score > bestScore) {
                        bestScore = score
                        bestBounds = Rect(bounds)
                    }
                }
            }

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }

        return bestBounds
    }

    private fun hasVerticalScrollAction(node: AccessibilityNodeInfo): Boolean {
        val ids = node.actionList.asSequence().map { it.id }.toSet()
        return AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id in ids ||
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.id in ids ||
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id in ids ||
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id in ids
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
    )

    companion object {
        private const val MINIMUM_STROKE_DURATION_MS = 80L
        private const val RELEASE_DURATION_MS = 10L
        private const val RELEASE_DISTANCE_PX = 1f
        private const val BRAKE_DURATION_MS = 90L
        private const val BRAKE_DISTANCE_PX = 1f
        private const val MAX_TREE_NODES = 500
        private const val SCROLLABLE_BONUS = 1_000_000_000L
    }
}
