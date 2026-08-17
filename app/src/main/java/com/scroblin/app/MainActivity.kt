package com.scroblin.app

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.scroblin.app.accessibility.AutoScrollAccessibilityService
import com.scroblin.app.overlay.OverlayFace
import com.scroblin.app.overlay.SpeedKnobListener
import com.scroblin.app.overlay.SpeedKnobView
import com.scroblin.app.settings.AppVisibilityTracker
import com.scroblin.app.settings.AutoScrollSettings
import com.scroblin.app.settings.HueWheelView
import com.scroblin.app.settings.RotarySettingView
import com.scroblin.app.settings.SettingsRepository
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    private lateinit var settingsRepository: SettingsRepository
    private var settingsState by mutableStateOf(AutoScrollSettings())
    private var serviceEnabledState by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepository = SettingsRepository(this)
        refreshState()

        setContent {
            ScroblinTheme {
                SettingsScreen(
                    settings = settingsState,
                    serviceEnabled = serviceEnabledState,
                    onPixelsPerNotchChanged = {
                        settingsRepository.setPixelsPerNotch(it)
                        refreshSettings()
                    },
                    onHapticChanged = {
                        settingsRepository.setHapticEnabled(it)
                        refreshSettings()
                    },
                    onWidgetSizeChanged = {
                        settingsRepository.setWidgetSizeDp(it)
                        refreshSettings()
                    },
                    onWidgetOpacityChanged = {
                        settingsRepository.setWidgetOpacity(it)
                        refreshSettings()
                    },
                    onColorHueChanged = {
                        settingsRepository.setColorHue(it)
                        refreshSettings()
                    },
                    onRunningToggle = {
                        if (serviceEnabledState) {
                            settingsRepository.setRunningEnabled(!settingsState.runningEnabled)
                            refreshSettings()
                        } else {
                            openAccessibilitySettings()
                        }
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppVisibilityTracker.setSettingsVisible(true)
        if (::settingsRepository.isInitialized) refreshState()
    }

    override fun onResume() {
        super.onResume()
        if (::settingsRepository.isInitialized) refreshState()
    }

    override fun onStop() {
        AppVisibilityTracker.setSettingsVisible(false)
        super.onStop()
    }

    private fun refreshState() {
        refreshSettings()
        serviceEnabledState = isAccessibilityServiceEnabled()
    }

    private fun refreshSettings() {
        settingsState = settingsRepository.snapshot()
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        if (Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
            return false
        }
        val expectedComponent = ComponentName(this, AutoScrollAccessibilityService::class.java)
        return Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
            .split(':')
            .mapNotNull(ComponentName::unflattenFromString)
            .any { it == expectedComponent }
    }
}

@Composable
private fun SettingsScreen(
    settings: AutoScrollSettings,
    serviceEnabled: Boolean,
    onPixelsPerNotchChanged: (Float) -> Unit,
    onHapticChanged: (Boolean) -> Unit,
    onWidgetSizeChanged: (Int) -> Unit,
    onWidgetOpacityChanged: (Float) -> Unit,
    onColorHueChanged: (Float) -> Unit,
    onRunningToggle: () -> Unit,
) {
    var previewSpeedStep by remember { mutableIntStateOf(0) }
    var previewPlaying by remember { mutableStateOf(false) }
    var previewColorHue by remember { mutableFloatStateOf(settings.colorHue) }
    var colorAdjusting by remember { mutableStateOf(false) }
    var buttonFontIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(settings.colorHue, colorAdjusting) {
        if (!colorAdjusting) previewColorHue = settings.colorHue
    }

    val controlColor = Color.hsv(oppositeHue(settings.colorHue), 0.79f, 1f)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.changedToDownIgnoreConsumed() }) {
                            previewPlaying = false
                        }
                    }
                }
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PreviewCard(
            settings = settings,
            previewSpeedStep = previewSpeedStep,
            previewPlaying = previewPlaying,
            colorPreviewHue = previewColorHue,
            colorPreviewActive = colorAdjusting,
            onPreviewSpeedStepChanged = { previewSpeedStep = it },
            onPreviewInteraction = { previewPlaying = true },
        )

        CompactScrollLabCard(
            settings = settings,
            displayHue = previewColorHue,
            controlFillColor = controlColor.toArgb(),
            onPixelsPerNotchChanged = {
                onPixelsPerNotchChanged(it)
                previewPlaying = true
            },
            onWidgetSizeChanged = onWidgetSizeChanged,
            onWidgetOpacityChanged = onWidgetOpacityChanged,
            onColorInteractionStarted = {
                previewColorHue = it
                colorAdjusting = true
            },
            onColorPreviewChanged = { previewColorHue = it },
            onColorInteractionEnded = {
                previewColorHue = it
                onColorHueChanged(it)
                colorAdjusting = false
            },
            onHapticChanged = onHapticChanged,
        )

        CompactControlsCard()

        StartStopButton(
            serviceEnabled = serviceEnabled,
            running = settings.runningEnabled,
            colorHue = settings.colorHue,
            fontFamily = ButtonFontFamilies[buttonFontIndex],
            onClick = {
                buttonFontIndex = (buttonFontIndex + 1) % ButtonFontFamilies.size
                onRunningToggle()
            },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StartStopButton(
    serviceEnabled: Boolean,
    running: Boolean,
    colorHue: Float,
    fontFamily: FontFamily,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val buttonColor = Color.hsv(colorHue, 0.82f, 0.68f)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    var whiteJitter by remember { mutableStateOf(IntOffset.Zero) }
    var blackJitter by remember { mutableStateOf(IntOffset.Zero) }
    var spinningColor by remember { mutableStateOf(buttonColor) }
    var returnFromColor by remember { mutableStateOf(buttonColor) }
    val returnProgress = remember { Animatable(1f) }

    LaunchedEffect(pressed) {
        whiteJitter = IntOffset.Zero
        blackJitter = IntOffset.Zero
        if (!pressed) {
            returnFromColor = spinningColor
            returnProgress.snapTo(0f)
            returnProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = HoldColorReturnDurationMs),
            )
            return@LaunchedEffect
        }

        returnProgress.snapTo(0f)
        spinningColor = buttonColor
        val random = Random(System.nanoTime())
        var startedAt = 0L
        var finished = false
        while (isActive) {
            withFrameNanos { frameNanos ->
                if (startedAt == 0L) startedAt = frameNanos
                val elapsedSeconds = (frameNanos - startedAt) / 1_000_000_000f
                val growth = (elapsedSeconds / HoldJitterGrowSeconds).coerceIn(0f, 1f)
                val easedGrowth = growth * growth
                val fade = (
                    (elapsedSeconds - HoldJitterGrowSeconds) / HoldJitterFadeSeconds
                    ).coerceIn(0f, 1f)
                val envelope = if (elapsedSeconds <= HoldJitterGrowSeconds) {
                    easedGrowth
                } else {
                    1f - fade
                }
                val amplitude = if (elapsedSeconds <= HoldJitterGrowSeconds) {
                    HoldJitterStartPixels +
                        (HoldJitterMaximumPixels - HoldJitterStartPixels) * easedGrowth
                } else {
                    HoldJitterMaximumPixels * envelope
                }

                whiteJitter = randomJitter(random, amplitude)
                blackJitter = randomJitter(random, amplitude)
                val hueRotation = holdHueSweepProgress(elapsedSeconds) * 360f
                spinningColor = Color.hsv(
                    normalizeHue(colorHue - hueRotation),
                    0.82f,
                    0.68f,
                )

                if (elapsedSeconds >= HoldJitterGrowSeconds + HoldJitterFadeSeconds) {
                    whiteJitter = IntOffset.Zero
                    blackJitter = IntOffset.Zero
                    finished = true
                }
            }
            if (finished) break
        }
    }

    val displayedButtonColor = if (pressed) {
        spinningColor
    } else {
        lerp(returnFromColor, buttonColor, returnProgress.value)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val label = if (serviceEnabled && running) "stop scroblin" else "start scroblin"
        Button(
            onClick = onClick,
            interactionSource = interactionSource,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.95f),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = displayedButtonColor,
                contentColor = Color.White,
            ),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(0.95f),
                contentAlignment = Alignment.Center,
            ) {
                ButtonLabelLayer(
                    text = label,
                    color = Color.Black,
                    fontFamily = fontFamily,
                    jitter = blackJitter,
                    modifier = Modifier
                        .graphicsLayer {
                            scaleX = 1.035f
                            scaleY = 1.035f
                        }
                        .clearAndSetSemantics { },
                )
                ButtonLabelLayer(
                    text = label,
                    color = Color.White,
                    fontFamily = fontFamily,
                    jitter = whiteJitter,
                )
            }
        }
    }
}

@Composable
private fun ButtonLabelLayer(
    text: String,
    color: Color,
    fontFamily: FontFamily,
    jitter: IntOffset,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier.offset { jitter },
        style = TextStyle.Default,
        color = color,
        fontFamily = fontFamily,
        fontWeight = FontWeight.Black,
        textAlign = TextAlign.Center,
        maxLines = 2,
        autoSize = TextAutoSize.StepBased(
            minFontSize = 12.sp,
            maxFontSize = 112.sp,
            stepSize = 0.5.sp,
        ),
    )
}

@Composable
private fun PreviewCard(
    settings: AutoScrollSettings,
    previewSpeedStep: Int,
    previewPlaying: Boolean,
    colorPreviewHue: Float,
    colorPreviewActive: Boolean,
    onPreviewSpeedStepChanged: (Int) -> Unit,
    onPreviewInteraction: () -> Unit,
) {
    val previewPixelsPerSecond = previewSpeedStep * settings.pixelsPerNotch
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PreviewSpeedKnob(
                speedStep = previewSpeedStep,
                pixelsPerSecond = previewPixelsPerSecond,
                playing = previewPlaying,
                settings = settings,
                displayHue = colorPreviewHue,
                colorPreviewActive = colorPreviewActive,
                onSpeedStepChanged = onPreviewSpeedStepChanged,
                onInteraction = onPreviewInteraction,
            )
            SpeedTestBox(
                pixelsPerSecond = previewPixelsPerSecond,
                active = previewPlaying,
                modifier = Modifier
                    .weight(1f)
                    .height(ScrollLabKnobSize + 40.dp),
            )
        }
    }
}

@Composable
private fun PreviewSpeedKnob(
    speedStep: Int,
    pixelsPerSecond: Float,
    playing: Boolean,
    settings: AutoScrollSettings,
    displayHue: Float,
    colorPreviewActive: Boolean,
    onSpeedStepChanged: (Int) -> Unit,
    onInteraction: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Preview", style = MaterialTheme.typography.labelLarge)
        Box(
            modifier = Modifier.size(ScrollLabKnobSize),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { context ->
                    SpeedKnobView(context).apply { setSettingsControlMode(true) }
                },
                update = { view ->
                    view.listener = object : SpeedKnobListener {
                        override fun onOutsideTouch() = Unit
                        override fun onOverlayTouchStarted() = onInteraction()
                        override fun onOverlayTouchEnded() = Unit
                        override fun onSpeedStepChanged(step: Int) {
                            onSpeedStepChanged(step)
                            onInteraction()
                        }
                        override fun onPauseRequested() = Unit
                        override fun onResumeRequested() = onInteraction()
                        override fun onResetToZeroRequested() = onSpeedStepChanged(0)
                        override fun onFlipToGear() = Unit
                        override fun onFlipToKnob() = Unit
                        override fun onGearTapped() = Unit
                        override fun onPickupStarted(rawX: Float, rawY: Float) = Unit
                        override fun onPositionDragged(rawX: Float, rawY: Float) = Unit
                        override fun onPositionPlaced() = Unit
                    }
                    view.updateColorHue(displayHue)
                    view.updateHapticSetting(settings.hapticEnabled)
                    view.alpha = if (colorPreviewActive) 1f else settings.widgetOpacity
                    view.renderState(speedStep, !playing, OverlayFace.KNOB, false)
                    view.setColorPreview(colorPreviewActive)
                },
                modifier = Modifier.size(settings.widgetSizeDp.dp),
            )
        }
        Text(
            text = "${pixelsPerSecond.roundToInt()} px/s",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SpeedTestBox(
    pixelsPerSecond: Float,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val resources = LocalResources.current
    val previewText = remember(resources) {
        resources.openRawResource(R.raw.missile_knows_where_it_is)
            .bufferedReader()
            .use { it.readText().trim() }
    }
    val scrollState = rememberScrollState()
    var copyHeightPixels by remember { mutableIntStateOf(0) }
    LaunchedEffect(copyHeightPixels) {
        if (copyHeightPixels > 0) scrollState.scrollTo(copyHeightPixels)
    }
    LaunchedEffect(pixelsPerSecond, active, copyHeightPixels) {
        if (!active || pixelsPerSecond == 0f || copyHeightPixels <= 0) {
            return@LaunchedEffect
        }
        val copyHeight = copyHeightPixels.toFloat()
        var accumulated = scrollState.value.toFloat()
        val normalizedOffset = ((accumulated - copyHeight) % copyHeight + copyHeight) % copyHeight
        accumulated = copyHeight + normalizedOffset
        var previousFrame = 0L
        while (isActive) {
            withFrameNanos { frame ->
                if (previousFrame != 0L) {
                    val seconds = (frame - previousFrame) / 1_000_000_000f
                    accumulated += pixelsPerSecond * seconds
                    while (accumulated >= copyHeight * 2f) accumulated -= copyHeight
                    while (accumulated < copyHeight) accumulated += copyHeight
                }
                previousFrame = frame
            }
            scrollState.scrollTo(
                accumulated.roundToInt().coerceIn(0, scrollState.maxValue),
            )
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = if (active) 2.dp else 1.dp,
                color = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = RoundedCornerShape(12.dp),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState, enabled = false),
        ) {
            repeat(3) { copyIndex ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (copyIndex == 0) {
                                Modifier.onSizeChanged { copyHeightPixels = it.height }
                            } else {
                                Modifier
                            },
                        )
                        .padding(8.dp),
                ) {
                    Text(
                        text = "$previewText\n\n",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactScrollLabCard(
    settings: AutoScrollSettings,
    displayHue: Float,
    controlFillColor: Int,
    onPixelsPerNotchChanged: (Float) -> Unit,
    onWidgetSizeChanged: (Int) -> Unit,
    onWidgetOpacityChanged: (Float) -> Unit,
    onColorInteractionStarted: (Float) -> Unit,
    onColorPreviewChanged: (Float) -> Unit,
    onColorInteractionEnded: (Float) -> Unit,
    onHapticChanged: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Scroll Lab",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "Haptics",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.size(4.dp))
            Switch(
                checked = settings.hapticEnabled,
                onCheckedChange = onHapticChanged,
            )
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 5.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingKnob(
                    label = "Rate",
                    valueText = "${settings.pixelsPerNotch.roundToInt()} px/tick",
                    value = settings.pixelsPerNotch,
                    minimumValue = SettingsRepository.PIXELS_PER_NOTCH_RANGE.start,
                    maximumValue = SettingsRepository.PIXELS_PER_NOTCH_RANGE.endInclusive,
                    stepSize = SettingsRepository.PIXELS_PER_NOTCH_STEP,
                    visualNotchCount = 20,
                    fillColor = controlFillColor,
                    hapticEnabled = settings.hapticEnabled,
                    onValueChanged = onPixelsPerNotchChanged,
                    modifier = Modifier.weight(1f),
                )
                SettingKnob(
                    label = "Size",
                    valueText = "${settings.widgetSizeDp} dp",
                    value = settings.widgetSizeDp.toFloat(),
                    minimumValue = AutoScrollSettings.WIDGET_SIZE_RANGE.first.toFloat(),
                    maximumValue = AutoScrollSettings.WIDGET_SIZE_RANGE.last.toFloat(),
                    stepSize = 1f,
                    fillColor = controlFillColor,
                    hapticEnabled = settings.hapticEnabled,
                    onValueChanged = { onWidgetSizeChanged(it.roundToInt()) },
                    modifier = Modifier.weight(1f),
                )
                SettingKnob(
                    label = "Opacity",
                    valueText = "${(settings.widgetOpacity * 100).roundToInt()}%",
                    value = settings.widgetOpacity * 100f,
                    minimumValue = AutoScrollSettings.WIDGET_OPACITY_RANGE.start * 100f,
                    maximumValue = AutoScrollSettings.WIDGET_OPACITY_RANGE.endInclusive * 100f,
                    stepSize = 5f,
                    fillColor = controlFillColor,
                    hapticEnabled = settings.hapticEnabled,
                    onValueChanged = { onWidgetOpacityChanged(it / 100f) },
                    modifier = Modifier.weight(1f),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Color", style = MaterialTheme.typography.labelLarge)
                    AndroidView(
                        factory = { HueWheelView(it) },
                        update = { view ->
                            view.setHue(displayHue)
                            view.setHapticEnabled(settings.hapticEnabled)
                            view.onInteractionStarted = onColorInteractionStarted
                            view.onHueChanged = onColorPreviewChanged
                            view.onInteractionEnded = onColorInteractionEnded
                        },
                        modifier = Modifier.size(74.dp),
                    )
                    Text(
                        text = "${displayHue.roundToInt()}°",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingKnob(
    label: String,
    valueText: String,
    value: Float,
    minimumValue: Float,
    maximumValue: Float,
    stepSize: Float,
    visualNotchCount: Int = 40,
    fillColor: Int,
    hapticEnabled: Boolean,
    onValueChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        AndroidView(
            factory = { RotarySettingView(it) },
            update = { view ->
                view.configure(
                    value = value,
                    minimumValue = minimumValue,
                    maximumValue = maximumValue,
                    stepSize = stepSize,
                    pixelsPerStepDp = 6f,
                    fillColor = fillColor,
                    visualNotchCount = visualNotchCount,
                    hapticEnabled = hapticEnabled,
                )
                view.onValueChanged = onValueChanged
            },
            modifier = Modifier.size(74.dp),
        )
        Text(
            text = valueText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun CompactControlsCard() {
    SettingsCard(title = "Controls") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                ControlLine("Swipe Up/Down", "Set forward/back scroll speed")
                ControlLine("Tap Knob", "once to start, three times\nto set speed to zero.")
            }
            Column(modifier = Modifier.weight(1f)) {
                ControlLine("Swipe Sideways", "Show settings gear.\n(Hold and drag to move)")
                ControlLine(
                    "Tap Anywhere Else",
                    "Pause scroblin/do\nother things on your phone",
                )
            }
        }
    }
}

@Composable
private fun ControlLine(action: String, result: String) {
    Column(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            text = action,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 14.sp,
            ),
            maxLines = 1,
            softWrap = false,
        )
        Text(
            text = result,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 10.sp,
                lineHeight = 12.5.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = result.count { it == '\n' } + 1,
            softWrap = false,
        )
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Column(content = content)
        }
    }
}

private fun oppositeHue(hue: Float): Float = ((hue + 180f) % 360f + 360f) % 360f
private fun normalizeHue(hue: Float): Float = ((hue % 360f) + 360f) % 360f

private fun holdHueSweepProgress(elapsedSeconds: Float): Float {
    val growthTime = elapsedSeconds.coerceIn(0f, HoldJitterGrowSeconds)
    val growthArea = HoldHueInitialSpeedFraction * growthTime +
        (1f - HoldHueInitialSpeedFraction) * growthTime * growthTime * growthTime /
        (3f * HoldJitterGrowSeconds * HoldJitterGrowSeconds)
    val fullGrowthArea = HoldHueInitialSpeedFraction * HoldJitterGrowSeconds +
        (1f - HoldHueInitialSpeedFraction) * HoldJitterGrowSeconds / 3f
    val fadeTime = (elapsedSeconds - HoldJitterGrowSeconds)
        .coerceIn(0f, HoldJitterFadeSeconds)
    val fadeArea = fadeTime - fadeTime * fadeTime / (2f * HoldJitterFadeSeconds)
    val traveledArea = if (elapsedSeconds <= HoldJitterGrowSeconds) {
        growthArea
    } else {
        fullGrowthArea + fadeArea
    }
    val totalArea = fullGrowthArea + HoldJitterFadeSeconds / 2f
    return (traveledArea / totalArea).coerceIn(0f, 1f)
}

private fun randomJitter(random: Random, amplitudePixels: Float): IntOffset = IntOffset(
    x = ((random.nextFloat() * 2f - 1f) * amplitudePixels).roundToInt(),
    y = ((random.nextFloat() * 2f - 1f) * amplitudePixels).roundToInt(),
)

private val ScrollLabKnobSize = AutoScrollSettings.WIDGET_SIZE_RANGE.last.dp
private const val HoldJitterGrowSeconds = 5f
private const val HoldJitterFadeSeconds = 1.1f
private const val HoldJitterStartPixels = 2f
private const val HoldJitterMaximumPixels = 20f
private const val HoldHueInitialSpeedFraction = 0.05f
private const val HoldColorReturnDurationMs = 220
private val ButtonFontFamilies = listOf(
    FontFamily.SansSerif,
    FontFamily.Serif,
    FontFamily.Monospace,
    FontFamily.Cursive,
    FontFamily(Font(DeviceFontFamilyName("sans-serif-condensed"), weight = FontWeight.Black)),
    FontFamily(Font(DeviceFontFamilyName("sans-serif-smallcaps"), weight = FontWeight.Black)),
    FontFamily(Font(DeviceFontFamilyName("casual"), weight = FontWeight.Black)),
)

@Composable
private fun ScroblinTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
