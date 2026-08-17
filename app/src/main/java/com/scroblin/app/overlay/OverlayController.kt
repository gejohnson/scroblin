package com.scroblin.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import com.scroblin.app.settings.AutoScrollSettings
import kotlin.math.max
import kotlin.math.roundToInt

class OverlayController(context: Context) {
    private val overlayContext = context
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density

    private var knobView: SpeedKnobView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var attached = false
    private var dragging = false
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var xFraction = AutoScrollState.DEFAULT_X_FRACTION
    private var yFraction = AutoScrollState.DEFAULT_Y_FRACTION
    private var widgetSizeDp = AutoScrollSettings.DEFAULT_WIDGET_SIZE_DP
    private var widgetOpacity = AutoScrollSettings.DEFAULT_WIDGET_OPACITY
    private var fadeGeneration = 0
    private var hiddenForKeyboard = false
    private var unavailableOnHomeScreen = false

    val isShowing: Boolean
        get() = attached

    fun show(
        state: AutoScrollState,
        settings: AutoScrollSettings,
        listener: SpeedKnobListener,
    ): Boolean {
        if (attached) {
            updateSettings(settings)
            render(state)
            return true
        }

        xFraction = state.xFraction
        yFraction = state.yFraction
        widgetSizeDp = settings.widgetSizeDp
        widgetOpacity = settings.widgetOpacity
        hiddenForKeyboard = false
        unavailableOnHomeScreen = state.homeScreenVisible
        val sizePx = dpToPx(widgetSizeDp)
        val view = SpeedKnobView(overlayContext).apply {
            this.listener = listener
            updateHapticSetting(settings.hapticEnabled)
            updateColorHue(settings.colorHue)
            alpha = effectiveOpacity()
            renderState(
                state.speedStep,
                state.paused,
                state.face,
                state.pickedUp,
                state.homeScreenVisible,
            )
        }
        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            if (state.homeScreenVisible) {
                BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                BASE_FLAGS
            },
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "Scroblin auto-scroll control"
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
        }
        applyFractionsToLayout(params, xFraction, yFraction)

        return try {
            windowManager.addView(view, params)
            knobView = view
            layoutParams = params
            attached = true
            Log.d(TAG, "overlay added at x=${params.x} y=${params.y} size=${settings.widgetSizeDp}dp")
            true
        } catch (error: RuntimeException) {
            Log.e(TAG, "unable to add accessibility overlay", error)
            false
        }
    }

    fun render(state: AutoScrollState) {
        unavailableOnHomeScreen = state.homeScreenVisible
        knobView?.renderState(
            speedStep = state.speedStep,
            paused = state.paused,
            face = state.face,
            pickedUp = state.pickedUp,
            unavailable = state.homeScreenVisible,
        )
        if (!hiddenForKeyboard) {
            knobView?.alpha = effectiveOpacity()
        }
        setTouchable(!hiddenForKeyboard && !unavailableOnHomeScreen)
    }

    fun updateSettings(settings: AutoScrollSettings) {
        widgetOpacity = settings.widgetOpacity
        knobView?.apply {
            updateHapticSetting(settings.hapticEnabled)
            updateColorHue(settings.colorHue)
            if (!hiddenForKeyboard) {
                alpha = effectiveOpacity()
            }
        }
        if (settings.widgetSizeDp == widgetSizeDp) return

        widgetSizeDp = settings.widgetSizeDp
        val params = layoutParams ?: return
        val sizePx = dpToPx(widgetSizeDp)
        params.width = sizePx
        params.height = sizePx
        applyFractionsToLayout(params, xFraction, yFraction)
        updateViewLayout()
        Log.d(TAG, "overlay resized to ${settings.widgetSizeDp}dp")
    }

    fun setPositionFractions(xFraction: Float, yFraction: Float) {
        this.xFraction = xFraction.coerceIn(0f, 1f)
        this.yFraction = yFraction.coerceIn(0f, 1f)
        val params = layoutParams ?: return
        applyFractionsToLayout(params, this.xFraction, this.yFraction)
        updateViewLayout()
    }

    fun onDisplayConfigurationChanged() {
        val params = layoutParams ?: return
        applyFractionsToLayout(params, xFraction, yFraction)
        updateViewLayout()
    }

    fun beginDrag(rawX: Float, rawY: Float) {
        val params = layoutParams ?: return
        dragging = true
        dragOffsetX = rawX - params.x
        dragOffsetY = rawY - params.y
    }

    fun updatePosition(rawX: Float, rawY: Float) {
        if (!dragging) return
        val params = layoutParams ?: return
        val limits = positionLimits(params.width, params.height)
        params.x = (rawX - dragOffsetX).roundToInt().coerceIn(limits.minX, limits.maxX)
        params.y = (rawY - dragOffsetY).roundToInt().coerceIn(limits.minY, limits.maxY)
        updateFractionsFromLayout(params, limits)
        updateViewLayout()
    }

    fun finishDrag(): Pair<Float, Float> {
        dragging = false
        layoutParams?.let { params ->
            updateFractionsFromLayout(params, positionLimits(params.width, params.height))
            Log.d(TAG, "overlay placed xFraction=$xFraction yFraction=$yFraction")
        }
        return xFraction to yFraction
    }

    fun fadeOutForKeyboard() {
        val view = knobView ?: return
        hiddenForKeyboard = true
        fadeGeneration += 1
        setTouchable(false)
        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .scaleX(0.84f)
            .scaleY(0.84f)
            .setDuration(FADE_DURATION_MS)
            .start()
    }

    fun fadeInAfterKeyboard() {
        val view = knobView ?: return
        hiddenForKeyboard = false
        val generation = ++fadeGeneration
        view.animate().cancel()
        view.animate()
            .alpha(effectiveOpacity())
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(FADE_DURATION_MS)
            .withEndAction {
                if (generation == fadeGeneration) {
                    setTouchable(!unavailableOnHomeScreen)
                }
            }
            .start()
    }

    fun setTouchable(touchable: Boolean) {
        val params = layoutParams ?: return
        val updatedFlags = if (touchable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (updatedFlags != params.flags) {
            params.flags = updatedFlags
            updateViewLayout()
        }
    }

    fun destroy() {
        fadeGeneration += 1
        dragging = false
        val view = knobView
        if (view != null && attached) {
            try {
                view.animate().cancel()
                windowManager.removeViewImmediate(view)
                Log.d(TAG, "overlay removed")
            } catch (error: RuntimeException) {
                Log.w(TAG, "overlay removal failed", error)
            }
        }
        attached = false
        hiddenForKeyboard = false
        unavailableOnHomeScreen = false
        knobView = null
        layoutParams = null
    }

    private fun updateViewLayout() {
        val view = knobView ?: return
        val params = layoutParams ?: return
        if (!attached) return
        try {
            windowManager.updateViewLayout(view, params)
        } catch (error: RuntimeException) {
            Log.w(TAG, "overlay layout update failed", error)
        }
    }

    private fun effectiveOpacity(): Float = if (unavailableOnHomeScreen) {
        AutoScrollSettings.WIDGET_OPACITY_RANGE.start
    } else {
        widgetOpacity
    }

    private fun applyFractionsToLayout(
        params: WindowManager.LayoutParams,
        xFraction: Float,
        yFraction: Float,
    ) {
        val limits = positionLimits(params.width, params.height)
        params.x = (limits.minX + limits.xRange * xFraction.coerceIn(0f, 1f)).roundToInt()
        params.y = (limits.minY + limits.yRange * yFraction.coerceIn(0f, 1f)).roundToInt()
    }

    private fun updateFractionsFromLayout(
        params: WindowManager.LayoutParams,
        limits: PositionLimits,
    ) {
        xFraction = if (limits.xRange > 0) {
            (params.x - limits.minX).toFloat() / limits.xRange
        } else {
            0f
        }.coerceIn(0f, 1f)
        yFraction = if (limits.yRange > 0) {
            (params.y - limits.minY).toFloat() / limits.yRange
        } else {
            0f
        }.coerceIn(0f, 1f)
    }

    private fun positionLimits(widgetWidth: Int, widgetHeight: Int): PositionLimits {
        val availableArea = availableArea()
        val margin = dpToPx(EDGE_MARGIN_DP)
        val minX = availableArea.left + margin
        val minY = availableArea.top + margin
        val maxX = max(minX, availableArea.right - widgetWidth - margin)
        val maxY = max(minY, availableArea.bottom - widgetHeight - margin)
        return PositionLimits(minX, minY, maxX, maxY)
    }

    @Suppress("DEPRECATION")
    private fun availableArea(): Rect {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val bounds = Rect(metrics.bounds)
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            Rect(
                bounds.left + insets.left,
                bounds.top + insets.top,
                bounds.right - insets.right,
                bounds.bottom - insets.bottom,
            )
        } else {
            Rect().also(windowManager.defaultDisplay::getRectSize)
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * density).roundToInt()

    private data class PositionLimits(
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int,
    ) {
        val xRange: Int get() = maxX - minX
        val yRange: Int get() = maxY - minY
    }

    companion object {
        private const val TAG = "AutoScroll"
        private const val EDGE_MARGIN_DP = 6
        private const val FADE_DURATION_MS = 180L
        private const val BASE_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
    }
}
