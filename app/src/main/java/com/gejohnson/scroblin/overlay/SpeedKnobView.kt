package com.gejohnson.scroblin.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import com.gejohnson.scroblin.overlay.AutoScrollState.Companion.MAX_SPEED_STEP
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

class SpeedKnobView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var listener: SpeedKnobListener? = null
    private var settingsControlMode = false

    private val density = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val ringBounds = RectF(23.5f, 23.5f, 116.5f, 116.5f)
    private val gearPath = buildGearPath()

    private val knobBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        shader = RadialGradient(
            47.6f,
            39.2f,
            78f,
            intArrayOf(Color.rgb(88, 98, 116), Color.rgb(59, 68, 84), Color.rgb(35, 42, 53)),
            floatArrayOf(0f, 0.38f, 1f),
            Shader.TileMode.CLAMP,
        )
    }
    private val knobBodyStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.argb(26, 255, 255, 255)
    }
    private val knobInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        shader = RadialGradient(
            50.4f,
            42f,
            52f,
            intArrayOf(Color.rgb(68, 78, 96), Color.rgb(48, 56, 70), Color.rgb(34, 41, 52)),
            floatArrayOf(0f, 0.50f, 1f),
            Shader.TileMode.CLAMP,
        )
    }
    private val knobInnerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.argb(16, 255, 255, 255)
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
    private val gearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        shader = LinearGradient(
            28f,
            25f,
            112f,
            115f,
            intArrayOf(Color.rgb(192, 199, 209), Color.rgb(146, 155, 168), Color.rgb(105, 115, 129)),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP,
        )
    }
    private val gearStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
        strokeJoin = Paint.Join.ROUND
        color = Color.argb(28, 255, 255, 255)
    }
    private val gearShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(87, 12, 15, 20)
    }
    private val gearHolePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(23, 28, 37)
    }
    private val gearHoleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.3f
        color = Color.argb(20, 255, 255, 255)
    }
    private val gearInnerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7.5f
        color = Color.argb(184, 133, 142, 156)
    }

    private var logicalSpeedStep = 0
    private var visualSpeedStep = 0f
    private var paused = true
    private var unavailable = false
    private var displayFace = OverlayFace.KNOB
    private var pickedUp = false
    private var hapticEnabled = true
    private var positiveColor = DEFAULT_POSITIVE_COLOR
    private var negativeColor = DEFAULT_NEGATIVE_COLOR
    private var ringAnimator: ValueAnimator? = null
    private var colorPreviewAnimator: ValueAnimator? = null
    private var colorPreviewActive = false
    private var colorPreviewMix = 0f
    private var isFlipping = false
    private var lastPausedResumeTapUptimeMs = 0L

    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var gesture = OverlayGesture.UNDECIDED
    private var downFace = OverlayFace.KNOB
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var startingSpeedStep = 0
    private var moved = false
    private val moveSlop = 11f * density
    private val speedTravelPerStep = 16f * density
    private val flipThresholdMaximum = 48f * density
    private val longPressRunnable = Runnable {
        if (
            activePointerId != MotionEvent.INVALID_POINTER_ID &&
            downFace == OverlayFace.GEAR &&
            gesture == OverlayGesture.UNDECIDED &&
            !moved
        ) {
            gesture = OverlayGesture.MOVE
            pickedUp = true
            applyPickedUpVisual()
            emitFeedback(1)
            listener?.onPickupStarted(lastRawX, lastRawY)
        }
    }

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "Auto-scroll speed control, paused"
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
        if (!isFlipping) {
            displayFace = face
        }
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
        colorPreviewAnimator = ValueAnimator.ofFloat(
            colorPreviewMix,
            0f,
        ).apply {
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
        if (unavailable) {
            drawKnob(canvas)
        } else {
            when (displayFace) {
                OverlayFace.KNOB -> drawKnob(canvas)
                OverlayFace.GEAR -> drawGear(canvas)
            }
        }
        canvas.restoreToCount(saveCount)
    }

    private fun drawKnob(canvas: Canvas) {
        canvas.drawCircle(CENTER, CENTER, 57f, knobBodyPaint)
        canvas.drawCircle(CENTER, CENTER, 57f, knobBodyStrokePaint)
        canvas.drawCircle(CENTER, CENTER, RING_RADIUS, ringTrackPaint)

        val absoluteVisualStep = abs(visualSpeedStep)
        if (absoluteVisualStep > 0.001f) {
            val direction = visualSpeedStep.sign
            val ringProgress =
                absoluteVisualStep / AutoScrollState.STEPS_PER_REVOLUTION
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

        canvas.drawCircle(CENTER, CENTER, 34f, knobInnerPaint)
        canvas.drawCircle(CENTER, CENTER, 34f, knobInnerStrokePaint)

        if (unavailable) {
            drawUnavailableEngraving(canvas)
        } else {
            val dotAngle = START_ANGLE +
                visualSpeedStep / AutoScrollState.STEPS_PER_REVOLUTION * 360f
            val dot = polar(31f, dotAngle)
            canvas.drawCircle(dot.first, dot.second, 3.1f, positionDotPaint)
            canvas.drawCircle(dot.first, dot.second, 3.1f, positionDotStrokePaint)
        }
    }

    private fun drawUnavailableEngraving(canvas: Canvas) {
        canvas.drawLine(58f, 58f, 82f, 82f, unavailableEngravingPaint)
        canvas.drawLine(82f, 58f, 58f, 82f, unavailableEngravingPaint)
        canvas.drawLine(59.2f, 59.2f, 83.2f, 83.2f, unavailableEngravingLipPaint)
        canvas.drawLine(83.2f, 59.2f, 59.2f, 83.2f, unavailableEngravingLipPaint)
    }

    private fun drawGear(canvas: Canvas) {
        val shadowSaveCount = canvas.save()
        canvas.translate(0f, 4f)
        canvas.drawPath(gearPath, gearShadowPaint)
        canvas.restoreToCount(shadowSaveCount)

        canvas.drawPath(gearPath, gearPaint)
        canvas.drawPath(gearPath, gearStrokePaint)
        canvas.drawCircle(CENTER, CENTER, 28f, gearInnerRingPaint)
        canvas.drawCircle(CENTER, CENTER, 21f, gearHolePaint)
        canvas.drawCircle(CENTER, CENTER, 21f, gearHoleStrokePaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            val generatedByAccessibility =
                event.flags and ACCESSIBILITY_EVENT_FLAG != 0
            if (!generatedByAccessibility) {
                listener?.onOutsideTouch()
            }
            return true
        }

        if (!isEnabled || isFlipping) return false

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
        lastX = startX
        lastY = startY
        lastRawX = event.rawX
        lastRawY = event.rawY
        startingSpeedStep = logicalSpeedStep
        downFace = displayFace
        gesture = OverlayGesture.UNDECIDED
        moved = false

        parent?.requestDisallowInterceptTouchEvent(true)
        listener?.onOverlayTouchStarted()
        if (downFace == OverlayFace.GEAR) {
            handler.postDelayed(longPressRunnable, LONG_PRESS_DURATION_MS)
        }
        return true
    }

    private fun moveGesture(event: MotionEvent): Boolean {
        val index = event.findPointerIndex(activePointerId)
        if (index < 0) return false

        val rawOffsetX = event.rawX - event.x
        val rawOffsetY = event.rawY - event.y
        val x = event.getX(index)
        val y = event.getY(index)
        val rawX = x + rawOffsetX
        val rawY = y + rawOffsetY
        val dx = x - startX
        val dy = y - startY
        val absoluteX = abs(dx)
        val absoluteY = abs(dy)

        lastX = x
        lastY = y
        lastRawX = rawX
        lastRawY = rawY
        if (hypot(dx, dy) > moveSlop) moved = true

        when (downFace) {
            OverlayFace.KNOB -> moveKnobGesture(dx, dy, absoluteX, absoluteY)
            OverlayFace.GEAR -> moveGearGesture(dx, dy, absoluteX, absoluteY, rawX, rawY)
        }
        return true
    }

    private fun moveKnobGesture(
        dx: Float,
        dy: Float,
        absoluteX: Float,
        absoluteY: Float,
    ) {
        if (gesture == OverlayGesture.UNDECIDED && max(absoluteX, absoluteY) > moveSlop) {
            gesture = if (!settingsControlMode && absoluteX > absoluteY * HORIZONTAL_DOMINANCE) {
                OverlayGesture.FLIP
            } else {
                OverlayGesture.SPEED
            }
        }

        when (gesture) {
            OverlayGesture.SPEED -> {
                val deltaSteps = (-dy / speedTravelPerStep).roundToInt()
                val nextStep = (startingSpeedStep + deltaSteps)
                    .coerceIn(-MAX_SPEED_STEP, MAX_SPEED_STEP)
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

            OverlayGesture.FLIP -> {
                val progress = (abs(dx) / flipThreshold()).coerceIn(0f, 1f)
                rotationY = -dx.sign * progress * 90f
            }

            else -> Unit
        }
    }

    private fun moveGearGesture(
        dx: Float,
        dy: Float,
        absoluteX: Float,
        absoluteY: Float,
        rawX: Float,
        rawY: Float,
    ) {
        if (gesture == OverlayGesture.UNDECIDED && hypot(dx, dy) > moveSlop) {
            handler.removeCallbacks(longPressRunnable)
            gesture = if (absoluteX > absoluteY * HORIZONTAL_DOMINANCE) {
                OverlayGesture.FLIP
            } else {
                OverlayGesture.CANCELLED
            }
        }

        when (gesture) {
            OverlayGesture.FLIP -> {
                val progress = (abs(dx) / flipThreshold()).coerceIn(0f, 1f)
                rotationY = -dx.sign * progress * 90f
            }

            OverlayGesture.MOVE -> listener?.onPositionDragged(rawX, rawY)
            else -> Unit
        }
    }

    private fun endGesture(event: MotionEvent): Boolean {
        if (event.getPointerId(event.actionIndex) != activePointerId) return false
        handler.removeCallbacks(longPressRunnable)

        val dx = lastX - startX
        when (downFace) {
            OverlayFace.KNOB -> finishKnobGesture(dx)
            OverlayFace.GEAR -> finishGearGesture(dx)
        }

        listener?.onOverlayTouchEnded()
        clearGestureState()
        return true
    }

    private fun finishKnobGesture(dx: Float) {
        if (gesture == OverlayGesture.FLIP && abs(dx) >= flipThreshold()) {
            startFlip(OverlayFace.GEAR, -dx.sign)
        } else {
            restoreRotation()
            if (gesture == OverlayGesture.UNDECIDED && !moved) {
                performClick()
            }
        }
    }

    private fun finishGearGesture(dx: Float) {
        when {
            gesture == OverlayGesture.FLIP && abs(dx) >= flipThreshold() -> {
                startFlip(OverlayFace.KNOB, -dx.sign)
            }

            gesture == OverlayGesture.MOVE -> {
                pickedUp = false
                applyPickedUpVisual()
                emitFeedback(1)
                listener?.onPositionPlaced()
            }

            gesture == OverlayGesture.UNDECIDED && !moved -> {
                performClick()
            }

            else -> restoreRotation()
        }
    }

    private fun cancelGesture(): Boolean {
        handler.removeCallbacks(longPressRunnable)
        if (gesture == OverlayGesture.MOVE) {
            pickedUp = false
            applyPickedUpVisual()
            listener?.onPositionPlaced()
        }
        restoreRotation()
        listener?.onOverlayTouchEnded()
        clearGestureState()
        return true
    }

    private fun clearGestureState() {
        activePointerId = MotionEvent.INVALID_POINTER_ID
        gesture = OverlayGesture.UNDECIDED
        moved = false
    }

    private fun startFlip(targetFace: OverlayFace, direction: Float) {
        if (targetFace == displayFace || isFlipping) {
            restoreRotation()
            return
        }

        isFlipping = true
        if (targetFace == OverlayFace.GEAR) {
            listener?.onFlipToGear()
        } else {
            listener?.onFlipToKnob()
        }
        animate().cancel()
        animate()
            .rotationY(direction * 90f)
            .setDuration(FLIP_HALF_DURATION_MS)
            .setInterpolator(FLIP_INTERPOLATOR)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    displayFace = targetFace
                    rotationY = -direction * 90f
                    invalidate()
                    animate()
                        .rotationY(0f)
                        .setDuration(FLIP_HALF_DURATION_MS)
                        .setInterpolator(FLIP_INTERPOLATOR)
                        .setListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                isFlipping = false
                                animate().setListener(null)
                                updateAccessibilityDescription()
                            }
                        })
                        .start()
                }
            })
            .start()
    }

    private fun restoreRotation() {
        animate().cancel()
        animate()
            .rotationY(0f)
            .setDuration(110L)
            .setInterpolator(DecelerateInterpolator())
            .setListener(null)
            .start()
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
            if (hapticEnabled) {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
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
            logicalSpeedStep /
                AutoScrollState.MAX_SPEED_STEP.toFloat() *
                100f
            ).roundToInt()
        val direction = when {
            logicalSpeedStep > 0 -> "scrolling down"
            logicalSpeedStep < 0 -> "scrolling up"
            else -> "stopped"
        }
        val status = if (paused) "paused" else "active"
        val face = if (displayFace == OverlayFace.KNOB) "speed knob" else "settings gear"
        contentDescription = "Auto-scroll $face, $percentage percent, $status, $direction"
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
        when (displayFace) {
            OverlayFace.KNOB -> {
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
            }

            OverlayFace.GEAR -> {
                emitFeedback(1)
                listener?.onGearTapped()
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        ringAnimator?.cancel()
        colorPreviewAnimator?.cancel()
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    private fun flipThreshold(): Float = min(flipThresholdMaximum, width * 0.52f)

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

    private fun buildGearPath(): Path {
        val points = mutableListOf<Pair<Float, Float>>()
        val toothArc = 360f / GEAR_TEETH
        repeat(GEAR_TEETH) { tooth ->
            val centerAngle = START_ANGLE + tooth * toothArc
            GEAR_TOOTH_PROFILE.forEach { (fraction, radius) ->
                points += polar(radius, centerAngle + fraction * toothArc)
            }
        }
        return Path().apply {
            points.firstOrNull()?.let { moveTo(it.first, it.second) }
            points.drop(1).forEach { lineTo(it.first, it.second) }
            close()
        }
    }

    companion object {
        private const val DESIGN_SIZE = 140f
        private const val CENTER = 70f
        private const val RING_RADIUS = 46.5f
        private const val START_ANGLE = -90f
        private const val NOTCH_COUNT = 40
        private const val GEAR_TEETH = 8
        private const val HORIZONTAL_DOMINANCE = 1.25f
        private const val LONG_PRESS_DURATION_MS = 430L
        private const val FLIP_HALF_DURATION_MS = 150L
        private const val RING_ANIMATION_DURATION_MS = 125L
        private const val RESET_RING_ANIMATION_DURATION_MS = 460L
        private const val COLOR_PREVIEW_OUT_DURATION_MS = 180L
        private const val DOUBLE_TAP_RESET_TIMEOUT_MS = 420L
        private const val MAX_FEEDBACK_BURST = 40
        private const val PICKED_UP_SCALE = 1.09f
        // Hidden platform flag documented by MotionEvent as 0x800.
        private const val ACCESSIBILITY_EVENT_FLAG = 0x800

        private val TRACK_COLOR = Color.rgb(52, 59, 73)
        private val PAUSED_COLOR = Color.rgb(91, 97, 108)
        private val DEFAULT_POSITIVE_COLOR = Color.rgb(53, 185, 255)
        private val DEFAULT_NEGATIVE_COLOR = Color.rgb(255, 102, 93)
        private val NOTCH_COLOR = Color.rgb(18, 22, 30)
        private val FLIP_INTERPOLATOR = PathInterpolator(0.2f, 0.86f, 0.24f, 1f)
        private val GEAR_TOOTH_PROFILE = listOf(
            -0.50f to 40.5f,
            -0.38f to 40.5f,
            -0.30f to 48.5f,
            -0.20f to 61f,
            0.20f to 61f,
            0.30f to 48.5f,
            0.38f to 40.5f,
            0.50f to 40.5f,
        )
    }
}
