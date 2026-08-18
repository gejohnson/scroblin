package com.scroblin.app.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import com.scroblin.app.R
import com.scroblin.app.overlay.AutoScrollState.Companion.MAX_SPEED_STEP
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

/**
 * Floating auto-scroll control.
 *
 * The speed control is an analog-stick interaction: one vertical throw selects
 * the complete signed speed range, quantized to the same 40 discrete notches as
 * the neon ring. The physical stick recenters on release while the selected
 * speed remains visible on the ring.
 *
 * The stick cap artwork is adapted from Kenney's CC0 Onscreen Controls
 * "shadedDark" game-control asset. See res/raw/kenney_onscreen_controls_license.txt.
 */
class SpeedKnobView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var listener: SpeedKnobListener? = null
    private var settingsControlMode = false

    private val density = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val ringBounds = RectF(23.5f, 23.5f, 116.5f, 116.5f)
    private val joystickCap: Drawable? = context.getDrawable(R.drawable.kenney_joystick_cap)

    private val shellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(35, 42, 53)
    }
    private val shellHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.argb(28, 255, 255, 255)
    }
    private val ringTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 11f
        strokeCap = Paint.Cap.BUTT
        color = TRACK_COLOR
    }
    private val ringFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 11f
        strokeCap = Paint.Cap.BUTT
    }
    private val majorNotchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f
        strokeCap = Paint.Cap.BUTT
        color = NOTCH_COLOR
    }
    private val minorNotchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.35f
        strokeCap = Paint.Cap.BUTT
        color = Color.argb(224, 18, 22, 30)
    }
    private val positionDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(223, 228, 234)
    }
    private val positionDotStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.1f
        color = Color.argb(107, 0, 0, 0)
    }
    private val stickWellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(22, 27, 35)
    }
    private val stickWellLipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.argb(60, 190, 200, 215)
    }
    private val stickStemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(44, 49, 57)
    }
    private val stickShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(88, 0, 0, 0)
    }
    private val unavailableEngravingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.6f
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(230, 8, 11, 16)
    }
    private val unavailableEngravingLipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.25f
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(112, 228, 233, 240)
    }

    private var logicalSpeedStep = 0
    private var visualSpeedStep = 0f
    private var paused = true
    private var unavailable = false
    private var pickedUp = false
    private var hapticEnabled = true
    private var positiveColor = DEFAULT_POSITIVE_COLOR
    private var negativeColor = DEFAULT_NEGATIVE_COLOR
    private var ringAnimator: ValueAnimator? = null
    private var stickReturnAnimator: ValueAnimator? = null
    private var colorPreviewAnimator: ValueAnimator? = null
    private var colorPreviewActive = false
    private var colorPreviewMix = 0f
    private var lastPausedResumeTapUptimeMs = 0L

    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var gesture = OverlayGesture.UNDECIDED
    private var startX = 0f
    private var startY = 0f
    private var startRawX = 0f
    private var startRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var moved = false
    private var longPressArmed = false
    private var menuDirection = -1
    private var menuTargetRawX = 0f
    private var menuTargetRawY = 0f
    private var stickVisualOffsetY = 0f
    private var gearHintView: GearHintView? = null
    private var gearHintParams: WindowManager.LayoutParams? = null

    private val moveSlop = 7f * density
    private val repositionJiggleDistance = 11f * density
    private val speedThrowDistance = 96f * density
    private val menuHintOffset = 64f * density
    private val menuHitRadius = 25f * density

    private val longPressRunnable = Runnable {
        if (
            activePointerId != MotionEvent.INVALID_POINTER_ID &&
            gesture == OverlayGesture.UNDECIDED &&
            !moved &&
            !settingsControlMode
        ) {
            longPressArmed = true
            gesture = OverlayGesture.MENU
            menuDirection = if (
                startRawY < resources.displayMetrics.heightPixels * TOP_EDGE_FRACTION
            ) {
                1
            } else {
                -1
            }
            menuTargetRawX = startRawX
            menuTargetRawY = startRawY + menuDirection * menuHintOffset
            showGearHint()
            emitFeedback(1)
        }
    }

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "Auto-scroll analog speed control, paused"
    }

    fun renderState(
        speedStep: Int,
        paused: Boolean,
        face: OverlayFace,
        pickedUp: Boolean,
        unavailable: Boolean = false,
    ) {
        val boundedStep = speedStep.coerceIn(-MAX_SPEED_STEP, MAX_SPEED_STEP)
        if (boundedStep != logicalSpeedStep) {
            logicalSpeedStep = boundedStep
            animateRingTo(boundedStep.toFloat())
        }
        this.paused = paused
        this.unavailable = unavailable
        this.pickedUp = pickedUp
        isEnabled = !unavailable
        applyPickedUpVisual()
        updateAccessibilityDescription()
        invalidate()
    }

    fun updateHapticSetting(hapticEnabled: Boolean) {
        this.hapticEnabled = hapticEnabled
        isHapticFeedbackEnabled = hapticEnabled
        isSoundEffectsEnabled = false
    }

    fun setSettingsControlMode(enabled: Boolean) {
        settingsControlMode = enabled
    }

    fun updateColorHue(hue: Float) {
        val baseHue = normalizeHue(hue)
        positiveColor = Color.HSVToColor(floatArrayOf(baseHue, 0.79f, 1f))
        negativeColor = Color.HSVToColor(
            floatArrayOf(normalizeHue(baseHue + 164f), 0.60f, 1f),
        )
        invalidate()
    }

    fun setColorPreview(active: Boolean) {
        if (colorPreviewActive == active) return
        colorPreviewAnimator?.removeAllListeners()
        colorPreviewAnimator?.cancel()
        colorPreviewActive = active
        if (active) {
            colorPreviewMix = 1f
            animateRingTo(MAX_SPEED_STEP.toFloat())
            invalidate()
            return
        }
        colorPreviewAnimator = ValueAnimator.ofFloat(colorPreviewMix, 0f).apply {
            duration = COLOR_PREVIEW_OUT_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                colorPreviewMix = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!colorPreviewActive) animateRingTo(logicalSpeedStep.toFloat())
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = min(width, height).toFloat()
        if (side <= 0f) return

        val saveCount = canvas.save()
        canvas.translate((width - side) / 2f, (height - side) / 2f)
        canvas.scale(side / DESIGN_SIZE, side / DESIGN_SIZE)
        drawAnalogControl(canvas)
        canvas.restoreToCount(saveCount)
    }

    private fun drawAnalogControl(canvas: Canvas) {
        canvas.drawCircle(CENTER, CENTER, 57f, shellPaint)
        canvas.drawCircle(CENTER, CENTER, 57f, shellHighlightPaint)
        canvas.drawCircle(CENTER, CENTER, RING_RADIUS, ringTrackPaint)

        val absoluteVisualStep = abs(visualSpeedStep)
        if (absoluteVisualStep > 0.001f) {
            val direction = visualSpeedStep.sign
            val ringProgress = absoluteVisualStep / AutoScrollState.STEPS_PER_REVOLUTION
            val normalColor = when {
                paused || unavailable -> PAUSED_COLOR
                visualSpeedStep < 0f -> negativeColor
                else -> positiveColor
            }
            ringFillPaint.color = blendColors(normalColor, positiveColor, colorPreviewMix)
            canvas.drawArc(
                ringBounds,
                START_ANGLE,
                direction * ringProgress.coerceIn(0f, 1f) * 360f,
                false,
                ringFillPaint,
            )
        }

        repeat(NOTCH_COUNT) { index ->
            val angle = START_ANGLE + index * (360f / NOTCH_COUNT)
            val major = index % 5 == 0
            val innerRadius = if (major) 40.7f else 46.2f
            val outerRadius = 52.2f
            val inner = polar(innerRadius, angle)
            val outer = polar(outerRadius, angle)
            canvas.drawLine(
                inner.first,
                inner.second,
                outer.first,
                outer.second,
                if (major) majorNotchPaint else minorNotchPaint,
            )
        }

        if (absoluteVisualStep > 0.001f) {
            val dotAngle = START_ANGLE +
                visualSpeedStep / AutoScrollState.STEPS_PER_REVOLUTION * 360f
            val dot = polar(31f, dotAngle)
            canvas.drawCircle(dot.first, dot.second, 3.1f, positionDotPaint)
            canvas.drawCircle(dot.first, dot.second, 3.1f, positionDotStrokePaint)
        }

        canvas.drawCircle(CENTER, CENTER, 29f, stickWellPaint)
        canvas.drawCircle(CENTER, CENTER, 29f, stickWellLipPaint)

        if (unavailable) {
            drawUnavailableEngraving(canvas)
            return
        }

        val visualScale = width.coerceAtMost(height).toFloat() / DESIGN_SIZE
        val designOffsetY = if (visualScale > 0f) stickVisualOffsetY / visualScale else 0f
        val clampedDesignOffset = designOffsetY.coerceIn(-MAX_STICK_VISUAL_THROW, MAX_STICK_VISUAL_THROW)
        val capY = CENTER + clampedDesignOffset

        if (abs(clampedDesignOffset) > 0.2f) {
            canvas.drawLine(CENTER, CENTER, CENTER, capY, stickStemPaint)
        }
        canvas.drawCircle(CENTER + 1.2f, capY + 3.2f, STICK_CAP_RADIUS + 1.5f, stickShadowPaint)
        drawJoystickCap(canvas, CENTER, capY)
    }

    private fun drawJoystickCap(canvas: Canvas, centerX: Float, centerY: Float) {
        val cap = joystickCap
        if (cap == null) {
            canvas.drawCircle(centerX, centerY, STICK_CAP_RADIUS, shellHighlightPaint)
            return
        }
        cap.setBounds(
            (centerX - STICK_CAP_RADIUS).roundToInt(),
            (centerY - STICK_CAP_RADIUS).roundToInt(),
            (centerX + STICK_CAP_RADIUS).roundToInt(),
            (centerY + STICK_CAP_RADIUS).roundToInt(),
        )
        cap.draw(canvas)
    }

    private fun drawUnavailableEngraving(canvas: Canvas) {
        canvas.drawLine(58f, 58f, 82f, 82f, unavailableEngravingPaint)
        canvas.drawLine(82f, 58f, 58f, 82f, unavailableEngravingPaint)
        canvas.drawLine(59.2f, 59.2f, 83.2f, 83.2f, unavailableEngravingLipPaint)
        canvas.drawLine(83.2f, 59.2f, 59.2f, 83.2f, unavailableEngravingLipPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            val generatedByAccessibility = event.flags and ACCESSIBILITY_EVENT_FLAG != 0
            if (!generatedByAccessibility) listener?.onOutsideTouch()
            return true
        }
        if (!isEnabled) return false

        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> beginGesture(event)
            MotionEvent.ACTION_MOVE -> moveGesture(event)
            MotionEvent.ACTION_UP -> endGesture(event)
            MotionEvent.ACTION_CANCEL -> cancelGesture()
            else -> true
        }
    }

    private fun beginGesture(event: MotionEvent): Boolean {
        activePointerId = event.getPointerId(0)
        startX = event.x
        startY = event.y
        startRawX = event.rawX
        startRawY = event.rawY
        lastRawX = startRawX
        lastRawY = startRawY
        gesture = OverlayGesture.UNDECIDED
        moved = false
        longPressArmed = false
        stickReturnAnimator?.cancel()
        stickVisualOffsetY = 0f

        parent?.requestDisallowInterceptTouchEvent(true)
        listener?.onOverlayTouchStarted()
        if (!settingsControlMode) {
            handler.postDelayed(longPressRunnable, LONG_PRESS_DURATION_MS)
        }
        return true
    }

    private fun moveGesture(event: MotionEvent): Boolean {
        val index = event.findPointerIndex(activePointerId)
        if (index < 0) return false

        val x = event.getX(index)
        val y = event.getY(index)
        val rawOffsetX = event.rawX - event.x
        val rawOffsetY = event.rawY - event.y
        val rawX = x + rawOffsetX
        val rawY = y + rawOffsetY
        val dx = x - startX
        val dy = y - startY
        val rawDx = rawX - startRawX
        val rawDy = rawY - startRawY
        val distance = hypot(dx, dy)

        lastRawX = rawX
        lastRawY = rawY

        when (gesture) {
            OverlayGesture.MENU -> moveMenuGesture(rawX, rawY, rawDx, rawDy)
            OverlayGesture.MOVE -> listener?.onPositionDragged(rawX, rawY)
            OverlayGesture.SPEED -> updateSpeedFromThrow(dy)
            OverlayGesture.CANCELLED -> Unit
            else -> {
                if (distance > moveSlop) {
                    moved = true
                    handler.removeCallbacks(longPressRunnable)
                    gesture = OverlayGesture.SPEED
                    updateSpeedFromThrow(dy)
                }
            }
        }
        return true
    }

    private fun updateSpeedFromThrow(dy: Float) {
        moved = true
        val normalized = (-dy / speedThrowDistance).coerceIn(-1f, 1f)
        val nextStep = (normalized * MAX_SPEED_STEP).roundToInt()
            .coerceIn(-MAX_SPEED_STEP, MAX_SPEED_STEP)
        stickVisualOffsetY = (-normalized * MAX_STICK_VISUAL_THROW * currentDesignScale())
        invalidate()

        if (nextStep != logicalSpeedStep) {
            val crossedSteps = abs(nextStep - logicalSpeedStep)
            logicalSpeedStep = nextStep
            paused = false
            animateRingTo(nextStep.toFloat())
            emitFeedback(crossedSteps)
            listener?.onSpeedStepChanged(nextStep)
            updateAccessibilityDescription()
        }
    }

    private fun moveMenuGesture(
        rawX: Float,
        rawY: Float,
        rawDx: Float,
        rawDy: Float,
    ) {
        moved = true
        if (hypot(rawX - menuTargetRawX, rawY - menuTargetRawY) <= menuHitRadius) {
            dismissGearHint()
            emitFeedback(1)
            gesture = OverlayGesture.CANCELLED
            listener?.onGearTapped()
            return
        }

        val travel = hypot(rawDx, rawDy)
        val signedMenuTravel = rawDy * menuDirection
        val movingTowardMenu = signedMenuTravel > 0f && abs(rawDy) >= abs(rawDx) * MENU_DIRECTION_BIAS

        if (travel >= repositionJiggleDistance && !movingTowardMenu) {
            dismissGearHint()
            gesture = OverlayGesture.MOVE
            pickedUp = true
            applyPickedUpVisual()
            emitFeedback(1)
            listener?.onPickupStarted(startRawX, startRawY)
            listener?.onPositionDragged(rawX, rawY)
        }
    }

    private fun endGesture(event: MotionEvent): Boolean {
        if (event.getPointerId(event.actionIndex) != activePointerId) return false
        handler.removeCallbacks(longPressRunnable)

        when (gesture) {
            OverlayGesture.MOVE -> {
                pickedUp = false
                applyPickedUpVisual()
                emitFeedback(1)
                listener?.onPositionPlaced()
            }

            OverlayGesture.MENU -> dismissGearHint()
            OverlayGesture.UNDECIDED -> if (!moved) performClick()
            else -> Unit
        }

        animateStickToCenter()
        listener?.onOverlayTouchEnded()
        clearGestureState()
        return true
    }

    private fun cancelGesture(): Boolean {
        handler.removeCallbacks(longPressRunnable)
        dismissGearHint()
        if (gesture == OverlayGesture.MOVE) {
            pickedUp = false
            applyPickedUpVisual()
            listener?.onPositionPlaced()
        }
        animateStickToCenter()
        listener?.onOverlayTouchEnded()
        clearGestureState()
        return true
    }

    private fun clearGestureState() {
        activePointerId = MotionEvent.INVALID_POINTER_ID
        gesture = OverlayGesture.UNDECIDED
        moved = false
        longPressArmed = false
    }

    private fun animateStickToCenter() {
        stickReturnAnimator?.cancel()
        if (abs(stickVisualOffsetY) < 0.5f) {
            stickVisualOffsetY = 0f
            invalidate()
            return
        }
        stickReturnAnimator = ValueAnimator.ofFloat(stickVisualOffsetY, 0f).apply {
            duration = STICK_RETURN_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                stickVisualOffsetY = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun showGearHint() {
        if (gearHintView != null) return
        val size = (GEAR_HINT_SIZE_DP * density).roundToInt()
        val displayWidth = resources.displayMetrics.widthPixels
        val displayHeight = resources.displayMetrics.heightPixels
        val x = (menuTargetRawX - size / 2f).roundToInt().coerceIn(0, (displayWidth - size).coerceAtLeast(0))
        val y = (menuTargetRawY - size / 2f).roundToInt().coerceIn(0, (displayHeight - size).coerceAtLeast(0))

        val hint = GearHintView(context).apply {
            alpha = 0f
            scaleX = 0.62f
            scaleY = 0.62f
            translationY = -menuDirection * 12f * density
        }
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
            title = "Scroblin settings target"
        }

        try {
            windowManager.addView(hint, params)
            gearHintView = hint
            gearHintParams = params
            hint.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(GEAR_HINT_POP_DURATION_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } catch (_: RuntimeException) {
            gearHintView = null
            gearHintParams = null
        }
    }

    private fun dismissGearHint() {
        val hint = gearHintView ?: return
        gearHintView = null
        gearHintParams = null
        try {
            hint.animate().cancel()
            windowManager.removeViewImmediate(hint)
        } catch (_: RuntimeException) {
            // Window may already have been removed with the accessibility overlay.
        }
    }

    private fun animateRingTo(targetStep: Float) {
        ringAnimator?.cancel()
        val startingVisualStep = visualSpeedStep
        if (abs(startingVisualStep - targetStep) < 0.001f) {
            visualSpeedStep = targetStep
            invalidate()
            return
        }
        ringAnimator = ValueAnimator.ofFloat(startingVisualStep, targetStep).apply {
            duration = if (targetStep == 0f && abs(startingVisualStep) >= 1f) {
                RESET_RING_ANIMATION_DURATION_MS
            } else {
                RING_ANIMATION_DURATION_MS
            }
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                visualSpeedStep = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun emitFeedback(count: Int) {
        repeat(count.coerceAtMost(MAX_FEEDBACK_BURST)) {
            if (hapticEnabled) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    private fun applyPickedUpVisual() {
        val scale = if (pickedUp) PICKED_UP_SCALE else 1f
        scaleX = scale
        scaleY = scale
        elevation = (if (pickedUp) 18f else 8f) * density
        invalidate()
    }

    private fun updateAccessibilityDescription() {
        if (unavailable) {
            contentDescription = "Auto-scroll unavailable on Home screen"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                stateDescription = "Unavailable on Home screen"
            }
            return
        }
        val percentage = (
            logicalSpeedStep / AutoScrollState.MAX_SPEED_STEP.toFloat() * 100f
            ).roundToInt()
        val direction = when {
            logicalSpeedStep > 0 -> "scrolling down"
            logicalSpeedStep < 0 -> "scrolling up"
            else -> "stopped"
        }
        val status = if (paused) "paused" else "active"
        contentDescription = "Auto-scroll analog stick, $percentage percent, $status, $direction"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            stateDescription = "$percentage percent, $status"
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (settingsControlMode) {
            emitFeedback(1)
            return true
        }
        val now = SystemClock.uptimeMillis()
        emitFeedback(1)
        when {
            paused -> {
                lastPausedResumeTapUptimeMs = now
                listener?.onResumeRequested()
            }

            now - lastPausedResumeTapUptimeMs <= DOUBLE_TAP_RESET_TIMEOUT_MS -> {
                lastPausedResumeTapUptimeMs = 0L
                listener?.onResetToZeroRequested()
            }

            else -> {
                lastPausedResumeTapUptimeMs = 0L
                listener?.onPauseRequested()
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        ringAnimator?.cancel()
        stickReturnAnimator?.cancel()
        colorPreviewAnimator?.cancel()
        handler.removeCallbacksAndMessages(null)
        dismissGearHint()
        super.onDetachedFromWindow()
    }

    private fun currentDesignScale(): Float =
        width.coerceAtMost(height).toFloat().coerceAtLeast(1f) / DESIGN_SIZE

    private fun polar(radius: Float, angleDegrees: Float): Pair<Float, Float> {
        val radians = Math.toRadians(angleDegrees.toDouble())
        return Pair(
            CENTER + cos(radians).toFloat() * radius,
            CENTER + sin(radians).toFloat() * radius,
        )
    }

    private fun normalizeHue(hue: Float): Float = ((hue % 360f) + 360f) % 360f

    private fun blendColors(from: Int, to: Int, amount: Float): Int {
        val fraction = amount.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * fraction).roundToInt(),
            (Color.red(from) + (Color.red(to) - Color.red(from)) * fraction).roundToInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * fraction).roundToInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * fraction).roundToInt(),
        )
    }

    companion object {
        private const val DESIGN_SIZE = 140f
        private const val CENTER = 70f
        private const val RING_RADIUS = 46.5f
        private const val START_ANGLE = -90f
        private const val NOTCH_COUNT = 40
        private const val STICK_CAP_RADIUS = 21.5f
        private const val MAX_STICK_VISUAL_THROW = 20f
        private const val TOP_EDGE_FRACTION = 0.20f
        private const val MENU_DIRECTION_BIAS = 0.65f
        private const val LONG_PRESS_DURATION_MS = 430L
        private const val GEAR_HINT_SIZE_DP = 44f
        private const val GEAR_HINT_POP_DURATION_MS = 140L
        private const val STICK_RETURN_DURATION_MS = 135L
        private const val RING_ANIMATION_DURATION_MS = 95L
        private const val RESET_RING_ANIMATION_DURATION_MS = 360L
        private const val COLOR_PREVIEW_OUT_DURATION_MS = 180L
        private const val DOUBLE_TAP_RESET_TIMEOUT_MS = 420L
        private const val MAX_FEEDBACK_BURST = 40
        private const val PICKED_UP_SCALE = 1.09f
        private const val ACCESSIBILITY_EVENT_FLAG = 0x800

        private val TRACK_COLOR = Color.rgb(52, 59, 73)
        private val PAUSED_COLOR = Color.rgb(91, 97, 108)
        private val DEFAULT_POSITIVE_COLOR = Color.rgb(53, 185, 255)
        private val DEFAULT_NEGATIVE_COLOR = Color.rgb(255, 102, 93)
        private val NOTCH_COLOR = Color.rgb(18, 22, 30)
    }
}

/** A tiny non-touchable target shown during the long-press radial menu gesture. */
private class GearHintView(context: Context) : View(context) {
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(37, 44, 55)
        setShadowLayer(10f, 0f, 3f, Color.argb(110, 0, 0, 0))
    }
    private val gearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.1f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(210, 217, 226)
    }
    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(210, 217, 226)
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) * 0.46f
        canvas.drawCircle(cx, cy, radius, bubblePaint)

        val gearRadius = radius * 0.48f
        val toothInner = gearRadius * 0.80f
        repeat(8) { index ->
            val angle = Math.toRadians((index * 45.0) - 90.0)
            val x1 = cx + cos(angle).toFloat() * toothInner
            val y1 = cy + sin(angle).toFloat() * toothInner
            val x2 = cx + cos(angle).toFloat() * gearRadius
            val y2 = cy + sin(angle).toFloat() * gearRadius
            canvas.drawLine(x1, y1, x2, y2, gearPaint)
        }
        canvas.drawCircle(cx, cy, gearRadius * 0.70f, gearPaint)
        canvas.drawCircle(cx, cy, gearRadius * 0.20f, hubPaint)
    }
}
