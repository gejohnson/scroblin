package com.scroblin.app.scroll

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.scroblin.app.overlay.AutoScrollState
import kotlin.math.abs

/**
 * Scrolls the foreground app through its accessibility node rather than by
 * synthesizing a finger swipe. Android 15 / API 35 added granular scroll
 * amounts, which gives Scroblin a direct content-distance control when the
 * target view advertises support for them.
 */
class NodeScrollEngine(
    private val service: AccessibilityService,
    private val onUnavailable: () -> Unit,
) : ScrollEngine {
    private val mainHandler = Handler(Looper.getMainLooper())

    private var speedStep = 0
    private var pixelsPerNotch = 0f
    private var running = false
    private var stopped = false
    private var lastTickUptimeMs = 0L
    private var pendingPixels = 0f
    private var consecutiveFailures = 0

    override val syntheticGestureInFlight: Boolean
        get() = false

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!running || stopped || speedStep == 0 || pixelsPerNotch <= 0f) return

            val now = SystemClock.uptimeMillis()
            val elapsedMs = if (lastTickUptimeMs == 0L) TICK_INTERVAL_MS else {
                (now - lastTickUptimeMs).coerceIn(1L, MAX_ELAPSED_MS)
            }
            lastTickUptimeMs = now
            pendingPixels += targetPixelsPerSecond() * elapsedMs / 1_000f

            val result = dispatchGranularScroll(pendingPixels)
            when (result) {
                DispatchResult.SUCCESS -> {
                    pendingPixels = 0f
                    consecutiveFailures = 0
                }

                DispatchResult.WAIT_FOR_MORE_DISTANCE -> Unit

                DispatchResult.UNAVAILABLE -> {
                    consecutiveFailures += 1
                    if (consecutiveFailures >= FAILURE_LIMIT) {
                        running = false
                        onUnavailable()
                        return
                    }
                }
            }

            mainHandler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    override fun setSpeedStep(step: Int) {
        runOnMain {
            speedStep = step.coerceIn(
                -AutoScrollState.MAX_SPEED_STEP,
                AutoScrollState.MAX_SPEED_STEP,
            )
            if (speedStep == 0) {
                running = false
                cancelTicks()
            }
        }
    }

    override fun updatePixelsPerNotch(pixelsPerNotch: Float) {
        runOnMain {
            this.pixelsPerNotch = pixelsPerNotch.coerceAtLeast(0f)
            if (this.pixelsPerNotch == 0f) {
                running = false
                cancelTicks()
            }
        }
    }

    override fun resume() {
        runOnMain {
            if (stopped || speedStep == 0 || pixelsPerNotch <= 0f) return@runOnMain
            if (!canHandleCurrentWindow()) {
                onUnavailable()
                return@runOnMain
            }
            running = true
            pendingPixels = 0f
            consecutiveFailures = 0
            lastTickUptimeMs = 0L
            mainHandler.removeCallbacks(tickRunnable)
            mainHandler.post(tickRunnable)
        }
    }

    override fun pause() {
        runOnMain {
            running = false
            cancelTicks()
        }
    }

    override fun stop() {
        runOnMain {
            running = false
            stopped = true
            cancelTicks()
        }
    }

    fun canHandleCurrentWindow(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return false
        return findBestGranularTarget(speedStep > 0) != null
    }

    private fun dispatchGranularScroll(requestedPixels: Float): DispatchResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return DispatchResult.UNAVAILABLE
        }

        val target = findBestGranularTarget(speedStep > 0) ?: return DispatchResult.UNAVAILABLE
        val visibleHeight = target.bounds.height().coerceAtLeast(1)
        val amount = requestedPixels / visibleHeight.toFloat()
        if (amount < MIN_SCROLL_FRACTION) {
            return DispatchResult.WAIT_FOR_MORE_DISTANCE
        }

        val dispatchedAmount = amount.coerceAtMost(MAX_SCROLL_FRACTION_PER_TICK)
        val arguments = Bundle().apply {
            putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_SCROLL_AMOUNT_FLOAT, dispatchedAmount)
        }
        val accepted = target.node.performAction(target.actionId, arguments)
        return if (accepted) DispatchResult.SUCCESS else DispatchResult.UNAVAILABLE
    }

    private fun findBestGranularTarget(forward: Boolean): ScrollTarget? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return null
        val root = service.rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var best: ScrollTarget? = null
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_TREE_NODES) {
            val node = queue.removeFirst()
            visited += 1

            if (node.isVisibleToUser && node.isGranularScrollingSupported) {
                val actionId = scrollActionId(node, forward)
                if (actionId != null) {
                    val bounds = Rect().also(node::getBoundsInScreen)
                    if (bounds.width() > 0 && bounds.height() > 0) {
                        val candidate = ScrollTarget(node, bounds, actionId)
                        if (best == null || candidate.score > best.score) {
                            best = candidate
                        }
                    }
                }
            }

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }

        return best
    }

    private fun scrollActionId(node: AccessibilityNodeInfo, forward: Boolean): Int? {
        val actionIds = node.actionList.asSequence().map { it.id }.toSet()
        val directional = if (forward) {
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
        } else {
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
        }
        if (directional in actionIds) return directional

        val relative = if (forward) {
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id
        } else {
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.id
        }
        return relative.takeIf { it in actionIds }
    }

    private fun targetPixelsPerSecond(): Float =
        abs(speedStep).coerceAtLeast(1) * pixelsPerNotch

    private fun cancelTicks() {
        mainHandler.removeCallbacks(tickRunnable)
        lastTickUptimeMs = 0L
        pendingPixels = 0f
        consecutiveFailures = 0
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private data class ScrollTarget(
        val node: AccessibilityNodeInfo,
        val bounds: Rect,
        val actionId: Int,
    ) {
        val score: Long
            get() = bounds.width().toLong() * bounds.height().toLong()
    }

    private enum class DispatchResult {
        SUCCESS,
        WAIT_FOR_MORE_DISTANCE,
        UNAVAILABLE,
    }

    companion object {
        private const val TICK_INTERVAL_MS = 50L
        private const val MAX_ELAPSED_MS = 150L
        private const val FAILURE_LIMIT = 2
        private const val MAX_TREE_NODES = 500
        private const val MIN_SCROLL_FRACTION = 0.001f
        private const val MAX_SCROLL_FRACTION_PER_TICK = 0.25f
    }
}
