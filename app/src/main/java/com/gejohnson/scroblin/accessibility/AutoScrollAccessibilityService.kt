package com.gejohnson.scroblin.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.gejohnson.scroblin.MainActivity
import com.gejohnson.scroblin.overlay.AutoScrollState
import com.gejohnson.scroblin.overlay.OverlayController
import com.gejohnson.scroblin.overlay.OverlayFace
import com.gejohnson.scroblin.overlay.SpeedKnobListener
import com.gejohnson.scroblin.scroll.GestureScrollEngine
import com.gejohnson.scroblin.settings.AppVisibilityTracker
import com.gejohnson.scroblin.settings.AutoScrollSettings
import com.gejohnson.scroblin.settings.SettingsRepository

class AutoScrollAccessibilityService : AccessibilityService(), SpeedKnobListener {
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var overlayController: OverlayController
    private lateinit var scrollEngine: GestureScrollEngine

    private var settings = AutoScrollSettings()
    private var state = AutoScrollState()
    private var overlayTouchInProgress = false
    private var lastOverlayTouchUptimeMs = 0L
    private var connected = false
    private var settingsScreenVisible = false
    private var lastWindowPackageName: String? = null

    private val homePackageNames: Set<String> by lazy(::resolveHomePackageNames)

    private val visibilityListener: (Boolean) -> Unit = { visible ->
        mainHandler.post {
            if (connected) {
                settingsScreenVisible = visible
                if (visible) {
                    state = state.copy(paused = true, pickedUp = false)
                    scrollEngine.pause()
                }
                updateOverlayPresence()
            }
        }
    }

    private val contextCheckRunnable = Runnable {
        updateKeyboardVisibility()
        updateHomeScreenVisibility()
    }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        mainHandler.post { applySettingsFromRepository() }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (connected) return
        connected = true

        settingsRepository = SettingsRepository(this)
        settings = settingsRepository.snapshot()
        state = AutoScrollState(
            speedStep = settings.speedStep,
            paused = true,
            face = OverlayFace.KNOB,
            xFraction = settings.xFraction,
            yFraction = settings.yFraction,
        )
        scrollEngine = GestureScrollEngine(this) {
            mainHandler.post { handleSyntheticGestureCancellation() }
        }.also {
            it.setSpeedStep(state.speedStep)
            it.updatePixelsPerNotch(settings.pixelsPerNotch)
        }
        overlayController = OverlayController(this)
        settingsRepository.registerListener(preferenceListener)
        AppVisibilityTracker.addListener(visibilityListener)

        Log.d(TAG, "service connected")
        scheduleContextCheck(0L)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!connected) return
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> handleTouchInteractionStart()

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                lastWindowPackageName = event.packageName?.toString()
                scheduleContextCheck(0L)
            }

            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            -> scheduleContextCheck(CONTEXT_CHECK_DELAY_MS)

            else -> Unit
        }
    }

    override fun onInterrupt() {
        pauseScrolling("service interrupted")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!connected) return
        mainHandler.post {
            overlayController.onDisplayConfigurationChanged()
            scheduleContextCheck(CONTEXT_CHECK_DELAY_MS)
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanUp()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanUp()
        super.onDestroy()
    }

    override fun onOverlayTouchStarted() {
        overlayTouchInProgress = true
        lastOverlayTouchUptimeMs = SystemClock.uptimeMillis()
    }

    override fun onOverlayTouchEnded() {
        overlayTouchInProgress = false
        lastOverlayTouchUptimeMs = SystemClock.uptimeMillis()
        if (state.isScrolling) {
            scrollEngine.setSpeedStep(state.speedStep)
            scrollEngine.resume()
        }
    }

    override fun onOutsideTouch() {
        if (state.isScrolling) {
            pauseScrolling("outside overlay touch")
        }
    }

    override fun onSpeedStepChanged(step: Int) {
        if (state.keyboardVisible || state.homeScreenVisible || !settings.runningEnabled) return
        val boundedStep = step.coerceIn(
            -AutoScrollState.MAX_SPEED_STEP,
            AutoScrollState.MAX_SPEED_STEP,
        )
        state = state.copy(speedStep = boundedStep, paused = false)
        settingsRepository.saveSpeedStep(boundedStep)
        scrollEngine.setSpeedStep(boundedStep)
        if (boundedStep == 0 || overlayTouchInProgress) {
            scrollEngine.pause()
        } else {
            scrollEngine.resume()
        }
        overlayController.render(state)
        Log.d(TAG, "speedStep=$boundedStep paused=false")
    }

    override fun onResumeRequested() {
        if (state.keyboardVisible || state.homeScreenVisible || !settings.runningEnabled) return
        state = state.copy(paused = false)
        scrollEngine.setSpeedStep(state.speedStep)
        if (state.speedStep != 0) {
            scrollEngine.resume()
        }
        overlayController.render(state)
        Log.d(TAG, "resumed speedStep=${state.speedStep}")
    }

    override fun onPauseRequested() {
        pauseScrolling("knob tapped")
    }

    override fun onResetToZeroRequested() {
        state = state.copy(speedStep = 0, paused = true, pickedUp = false)
        settingsRepository.saveSpeedStep(0)
        scrollEngine.setSpeedStep(0)
        scrollEngine.pause()
        overlayController.render(state)
        Log.d(TAG, "speed smoothly reset to zero")
    }

    override fun onFlipToGear() {
        pauseScrolling("flipped to gear")
        state = state.copy(face = OverlayFace.GEAR, pickedUp = false)
        overlayController.render(state)
        Log.d(TAG, "face=GEAR")
    }

    override fun onFlipToKnob() {
        pauseScrolling("flipped to knob")
        state = state.copy(face = OverlayFace.KNOB, pickedUp = false)
        overlayController.render(state)
        Log.d(TAG, "face=KNOB")
    }

    override fun onGearTapped() {
        pauseScrolling("settings opened")
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        startActivity(intent)
        Log.d(TAG, "settings opened")
    }

    override fun onPickupStarted(rawX: Float, rawY: Float) {
        pauseScrolling("overlay picked up")
        state = state.copy(pickedUp = true)
        overlayController.render(state)
        overlayController.beginDrag(rawX, rawY)
        Log.d(TAG, "pickup")
    }

    override fun onPositionDragged(rawX: Float, rawY: Float) {
        overlayController.updatePosition(rawX, rawY)
    }

    override fun onPositionPlaced() {
        val (xFraction, yFraction) = overlayController.finishDrag()
        state = state.copy(
            pickedUp = false,
            xFraction = xFraction,
            yFraction = yFraction,
        )
        settingsRepository.saveOverlayPosition(xFraction, yFraction)
        overlayController.render(state)
        Log.d(TAG, "placed xFraction=$xFraction yFraction=$yFraction")
    }

    private fun handleTouchInteractionStart() {
        val overlayTouchWasRecent = overlayTouchInProgress ||
            SystemClock.uptimeMillis() - lastOverlayTouchUptimeMs < OVERLAY_TOUCH_GRACE_MS
        when {
            overlayTouchWasRecent -> Log.d(TAG, "touch interaction ignored: overlay touch")
            state.isScrolling -> pauseScrolling("external touch")
        }
    }

    private fun handleSyntheticGestureCancellation() {
        if (!connected) return
        val overlayTouchWasRecent = overlayTouchInProgress ||
            SystemClock.uptimeMillis() - lastOverlayTouchUptimeMs < OVERLAY_TOUCH_GRACE_MS
        if (overlayTouchWasRecent) {
            Log.d(TAG, "synthetic gesture cancelled by overlay interaction")
            if (state.isScrolling) {
                scrollEngine.setSpeedStep(state.speedStep)
                scrollEngine.resume()
            }
        } else {
            pauseScrolling("synthetic gesture cancelled")
        }
    }

    private fun pauseScrolling(reason: String) {
        if (!connected) return
        val stateChanged = !state.paused
        state = state.copy(paused = true, pickedUp = false)
        scrollEngine.pause()
        overlayController.render(state)
        if (stateChanged) {
            Log.d(TAG, "paused: $reason")
        }
    }

    private fun scheduleContextCheck(delayMs: Long) {
        mainHandler.removeCallbacks(contextCheckRunnable)
        mainHandler.postDelayed(contextCheckRunnable, delayMs)
    }

    private fun updateHomeScreenVisibility() {
        if (!connected) return
        val activePackageName = activeApplicationPackageName()
        val homeScreenVisible = activePackageName != null &&
            activePackageName in homePackageNames &&
            !launcherAppDrawerVisible(activePackageName)
        if (homeScreenVisible == state.homeScreenVisible) return

        state = state.copy(homeScreenVisible = homeScreenVisible, pickedUp = false)
        if (homeScreenVisible) {
            scrollEngine.pause()
        } else if (state.isScrolling && settings.runningEnabled && !settingsScreenVisible) {
            scrollEngine.setSpeedStep(state.speedStep)
            scrollEngine.resume()
        }
        overlayController.render(state)
        Log.d(
            TAG,
            "homeScreenVisible=$homeScreenVisible package=$activePackageName",
        )
    }

    private fun activeApplicationPackageName(): String? {
        val applicationWindows = windows.orEmpty()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val activeWindow = applicationWindows.firstOrNull { it.isFocused }
            ?: applicationWindows.firstOrNull { it.isActive }
        return activeWindow?.root?.packageName?.toString()
            ?: rootInActiveWindow?.packageName?.toString()
            ?: lastWindowPackageName
    }

    private fun launcherAppDrawerVisible(packageName: String): Boolean {
        val applicationWindows = windows.orEmpty()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val activeWindow = applicationWindows.firstOrNull { it.isFocused }
            ?: applicationWindows.firstOrNull { it.isActive }
        val root = activeWindow?.root ?: rootInActiveWindow ?: return false
        return runCatching {
            root.findAccessibilityNodeInfosByViewId("$packageName:id/apps_view")
                .any { it.isVisibleToUser }
        }.getOrDefault(false)
    }

    private fun resolveHomePackageNames(): Set<String> {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return listOfNotNull(
            packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo
                ?.packageName,
        ).toSet()
    }

    private fun updateKeyboardVisibility() {
        if (!connected) return
        val keyboardVisible = windows?.any {
            it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
        } == true
        if (keyboardVisible == state.keyboardVisible) return

        if (keyboardVisible) {
            state = state.copy(keyboardVisible = true, paused = true, pickedUp = false)
            scrollEngine.pause()
            overlayController.render(state)
            if (overlayController.isShowing) {
                overlayController.fadeOutForKeyboard()
            }
            Log.d(TAG, "keyboard visible; overlay hidden and scrolling paused")
        } else {
            state = state.copy(keyboardVisible = false, paused = true, pickedUp = false)
            scrollEngine.pause()
            overlayController.render(state)
            if (overlayController.isShowing) {
                overlayController.fadeInAfterKeyboard()
            }
            Log.d(TAG, "keyboard hidden; overlay restored paused")
        }
    }

    private fun applySettingsFromRepository() {
        if (!connected) return
        val wasRunningEnabled = settings.runningEnabled
        settings = settingsRepository.snapshot()
        scrollEngine.updatePixelsPerNotch(settings.pixelsPerNotch)
        if (settings.speedStep != state.speedStep) {
            state = state.copy(speedStep = settings.speedStep)
            scrollEngine.setSpeedStep(settings.speedStep)
        }
        state = state.copy(
            xFraction = settings.xFraction,
            yFraction = settings.yFraction,
        )
        if (wasRunningEnabled && !settings.runningEnabled) {
            state = state.copy(paused = true, pickedUp = false)
            scrollEngine.pause()
        }
        updateOverlayPresence()
        Log.d(TAG, "settings updated")
    }

    private fun updateOverlayPresence() {
        if (!connected) return
        val shouldShow = settings.runningEnabled && !settingsScreenVisible
        if (shouldShow) {
            overlayController.show(state, settings, this)
            overlayController.updateSettings(settings)
            overlayController.setPositionFractions(settings.xFraction, settings.yFraction)
            overlayController.render(state)
            if (state.keyboardVisible) {
                overlayController.fadeOutForKeyboard()
            }
        } else {
            scrollEngine.pause()
            overlayController.destroy()
        }
    }

    private fun cleanUp() {
        if (!connected) return
        connected = false
        mainHandler.removeCallbacksAndMessages(null)
        AppVisibilityTracker.removeListener(visibilityListener)
        settingsRepository.unregisterListener(preferenceListener)
        scrollEngine.stop()
        overlayController.destroy()
        Log.d(TAG, "service disconnected")
    }

    companion object {
        private const val TAG = "AutoScroll"
        private const val CONTEXT_CHECK_DELAY_MS = 90L
        private const val OVERLAY_TOUCH_GRACE_MS = 180L
    }
}
