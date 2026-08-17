package com.scroblin.app.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class RotarySettingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var onInteractionStarted: ((Float) -> Unit)? = null
    var onValueChanged: ((Float) -> Unit)? = null
    var onInteractionEnded: ((Float) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val ringBounds = RectF(23.5f, 23.5f, 116.5f, 116.5f)
    private var minimumValue = 0f
    private var maximumValue = 100f
    private var stepSize = 5f
    private var currentValue = 0f
    private var startingValue = 0f
    private var startY = 0f
    private var moved = false
    private var active = false
    private var pixelsPerStep = 9f * density
    private var colorHue = AutoScrollSettings.DEFAULT_COLOR_HUE
    private var configuredFillColor: Int? = null
    private var visualNotchCount = DEFAULT_NOTCH_COUNT
    private var hapticEnabled = true

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            47.6f,
            39.2f,
            78f,
            intArrayOf(Color.rgb(88, 98, 116), Color.rgb(59, 68, 84), Color.rgb(35, 42, 53)),
            floatArrayOf(0f, 0.38f, 1f),
            Shader.TileMode.CLAMP,
        )
    }
    private val bodyStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.argb(30, 255, 255, 255)
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 11f
        strokeCap = Paint.Cap.BUTT
        color = Color.rgb(52, 59, 73)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 11f
        strokeCap = Paint.Cap.BUTT
    }
    private val majorNotchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f
        color = Color.rgb(18, 22, 30)
    }
    private val minorNotchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.35f
        color = Color.argb(224, 18, 22, 30)
    }
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            50.4f,
            42f,
            52f,
            intArrayOf(Color.rgb(68, 78, 96), Color.rgb(48, 56, 70), Color.rgb(34, 41, 52)),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(223, 228, 234)
    }
    private val dotStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.1f
        color = Color.argb(107, 0, 0, 0)
    }

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun configure(
        value: Float,
        minimumValue: Float,
        maximumValue: Float,
        stepSize: Float,
        pixelsPerStepDp: Float = 9f,
        colorHue: Float = AutoScrollSettings.DEFAULT_COLOR_HUE,
        fillColor: Int? = null,
        visualNotchCount: Int = DEFAULT_NOTCH_COUNT,
        hapticEnabled: Boolean = true,
    ) {
        require(maximumValue > minimumValue)
        require(stepSize > 0f)
        this.minimumValue = minimumValue
        this.maximumValue = maximumValue
        this.stepSize = stepSize
        this.pixelsPerStep = pixelsPerStepDp * density
        this.colorHue = normalizeHue(colorHue)
        this.configuredFillColor = fillColor
        this.visualNotchCount = visualNotchCount.coerceAtLeast(2)
        this.hapticEnabled = hapticEnabled
        setValue(value)
        updateDescription()
        invalidate()
    }

    fun setValue(value: Float) {
        val snapped = snap(value)
        if (currentValue != snapped) {
            currentValue = snapped
            updateDescription()
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = min(width, height).toFloat()
        if (side <= 0f) return
        val saveCount = canvas.save()
        canvas.translate((width - side) / 2f, (height - side) / 2f)
        canvas.scale(side / DESIGN_SIZE, side / DESIGN_SIZE)

        canvas.drawCircle(CENTER, CENTER, 57f, bodyPaint)
        canvas.drawCircle(CENTER, CENTER, 57f, bodyStrokePaint)
        canvas.drawCircle(CENTER, CENTER, RING_RADIUS, trackPaint)
        drawProgress(canvas)
        drawNotches(canvas)
        canvas.drawCircle(CENTER, CENTER, 34f, innerPaint)

        val dotAngle = START_ANGLE + normalizedProgress() * 360f
        val dot = polar(31f, dotAngle)
        canvas.drawCircle(dot.first, dot.second, 3.1f, dotPaint)
        canvas.drawCircle(dot.first, dot.second, 3.1f, dotStrokePaint)
        canvas.restoreToCount(saveCount)
    }

    private fun drawProgress(canvas: Canvas) {
        val progress = normalizedProgress()
        fillPaint.color = configuredFillColor
            ?: Color.HSVToColor(floatArrayOf(colorHue, 0.79f, 1f))
        if (progress > 0f) {
            canvas.drawArc(ringBounds, START_ANGLE, progress * 360f, false, fillPaint)
        }
    }

    private fun drawNotches(canvas: Canvas) {
        repeat(visualNotchCount) { index ->
            val angle = START_ANGLE + index * (360f / visualNotchCount)
            val major = index % 5 == 0
            val inner = polar(if (major) 40.7f else 46.2f, angle)
            val outer = polar(52.2f, angle)
            canvas.drawLine(
                inner.first,
                inner.second,
                outer.first,
                outer.second,
                if (major) majorNotchPaint else minorNotchPaint,
            )
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = true
                moved = false
                startY = event.y
                startingValue = currentValue
                parent?.requestDisallowInterceptTouchEvent(true)
                onInteractionStarted?.invoke(currentValue)
                true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!active) return false
                val deltaY = event.y - startY
                if (abs(deltaY) > 4f * density) moved = true
                val deltaSteps = (-deltaY / pixelsPerStep).roundToInt()
                val nextValue = snap(startingValue + deltaSteps * stepSize)
                if (nextValue != currentValue) {
                    val crossed = (abs(nextValue - currentValue) / stepSize).roundToInt()
                    currentValue = nextValue
                    if (hapticEnabled) {
                        repeat(crossed.coerceAtMost(40)) {
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                    }
                    updateDescription()
                    invalidate()
                    onValueChanged?.invoke(currentValue)
                }
                true
            }

            MotionEvent.ACTION_UP -> {
                if (!active) return false
                active = false
                if (!moved) performClick()
                onInteractionEnded?.invoke(currentValue)
                parent?.requestDisallowInterceptTouchEvent(false)
                true
            }

            MotionEvent.ACTION_CANCEL -> {
                active = false
                onInteractionEnded?.invoke(currentValue)
                parent?.requestDisallowInterceptTouchEvent(false)
                true
            }

            else -> true
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun normalizedProgress(): Float =
        ((currentValue - minimumValue) / (maximumValue - minimumValue)).coerceIn(0f, 1f)

    private fun snap(value: Float): Float {
        val steps = ((value - minimumValue) / stepSize).roundToInt()
        return (minimumValue + steps * stepSize).coerceIn(minimumValue, maximumValue)
    }

    private fun updateDescription() {
        contentDescription = "Rotary setting, ${currentValue.roundToInt()}"
    }

    private fun polar(radius: Float, angleDegrees: Float): Pair<Float, Float> {
        val radians = Math.toRadians(angleDegrees.toDouble())
        return Pair(
            CENTER + cos(radians).toFloat() * radius,
            CENTER + sin(radians).toFloat() * radius,
        )
    }

    private fun normalizeHue(hue: Float): Float = ((hue % 360f) + 360f) % 360f

    companion object {
        private const val DESIGN_SIZE = 140f
        private const val CENTER = 70f
        private const val RING_RADIUS = 46.5f
        private const val START_ANGLE = -90f
        private const val DEFAULT_NOTCH_COUNT = 40
    }
}

class HueWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var onInteractionStarted: ((Float) -> Unit)? = null
    var onHueChanged: ((Float) -> Unit)? = null
    var onInteractionEnded: ((Float) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private var hue = AutoScrollSettings.DEFAULT_COLOR_HUE
    private var lastHapticSegment = -1
    private var hapticEnabled = true
    private var wheelShader: Shader? = null
    private val wheelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(35, 42, 53)
    }
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val indicatorStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.WHITE
    }
    private val oppositeIndicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val oppositeIndicatorStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density
        color = Color.BLACK
    }
    private val selectedColorRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        updateDescription()
    }

    fun setHue(hue: Float) {
        this.hue = normalizeHue(hue)
        updateDescription()
        invalidate()
    }

    fun setHapticEnabled(enabled: Boolean) {
        hapticEnabled = enabled
        isHapticFeedbackEnabled = enabled
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        wheelShader = SweepGradient(
            width / 2f,
            height / 2f,
            intArrayOf(
                Color.RED,
                Color.YELLOW,
                Color.GREEN,
                Color.CYAN,
                Color.BLUE,
                Color.MAGENTA,
                Color.RED,
            ),
            null,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = min(width, height).toFloat()
        val centerX = width / 2f
        val centerY = height / 2f
        val stroke = side * 0.19f
        val radius = side / 2f - stroke / 2f - 2f * density
        wheelPaint.strokeWidth = stroke
        wheelPaint.shader = wheelShader
        canvas.drawCircle(centerX, centerY, radius, wheelPaint)
        canvas.drawCircle(centerX, centerY, radius - stroke / 2f - 3f * density, centerPaint)

        selectedColorRingPaint.strokeWidth = side * 0.075f
        selectedColorRingPaint.color = Color.HSVToColor(floatArrayOf(hue, 0.82f, 1f))
        canvas.drawCircle(
            centerX,
            centerY,
            radius - stroke * 0.72f,
            selectedColorRingPaint,
        )

        val radians = Math.toRadians(hue.toDouble())
        val indicatorX = centerX + cos(radians).toFloat() * radius
        val indicatorY = centerY + sin(radians).toFloat() * radius
        val oppositeHue = normalizeHue(hue + 180f)
        val oppositeRadians = Math.toRadians(oppositeHue.toDouble())
        val oppositeX = centerX + cos(oppositeRadians).toFloat() * radius
        val oppositeY = centerY + sin(oppositeRadians).toFloat() * radius
        oppositeIndicatorPaint.color = Color.HSVToColor(floatArrayOf(oppositeHue, 0.79f, 1f))
        canvas.drawCircle(oppositeX, oppositeY, stroke * 0.34f, oppositeIndicatorPaint)
        canvas.drawCircle(oppositeX, oppositeY, stroke * 0.34f, oppositeIndicatorStrokePaint)
        indicatorPaint.color = Color.HSVToColor(floatArrayOf(hue, 0.82f, 1f))
        canvas.drawCircle(indicatorX, indicatorY, stroke * 0.34f, indicatorPaint)
        canvas.drawCircle(indicatorX, indicatorY, stroke * 0.34f, indicatorStrokePaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                onInteractionStarted?.invoke(hue)
                updateHueFromTouch(event.x, event.y)
                true
            }

            MotionEvent.ACTION_MOVE -> {
                updateHueFromTouch(event.x, event.y)
                true
            }

            MotionEvent.ACTION_UP -> {
                performClick()
                onInteractionEnded?.invoke(hue)
                parent?.requestDisallowInterceptTouchEvent(false)
                true
            }

            MotionEvent.ACTION_CANCEL -> {
                onInteractionEnded?.invoke(hue)
                parent?.requestDisallowInterceptTouchEvent(false)
                true
            }

            else -> true
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateHueFromTouch(x: Float, y: Float) {
        if (hypot(x - width / 2f, y - height / 2f) < 4f * density) return
        hue = normalizeHue(Math.toDegrees(atan2(y - height / 2f, x - width / 2f).toDouble()).toFloat())
        val segment = (hue / 15f).roundToInt()
        if (hapticEnabled && segment != lastHapticSegment) {
            lastHapticSegment = segment
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        updateDescription()
        invalidate()
        onHueChanged?.invoke(hue)
    }

    private fun updateDescription() {
        contentDescription = "Widget color wheel, hue ${hue.roundToInt()} degrees"
    }

    private fun normalizeHue(hue: Float): Float = ((hue % 360f) + 360f) % 360f
}
