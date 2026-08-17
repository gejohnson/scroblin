package com.scroblin.app.settings

import android.content.Context
import android.content.SharedPreferences
import com.scroblin.app.overlay.AutoScrollState
import kotlin.math.roundToInt

data class AutoScrollSettings(
    val pixelsPerNotch: Float = DEFAULT_PIXELS_PER_NOTCH,
    val hapticEnabled: Boolean = true,
    val widgetSizeDp: Int = DEFAULT_WIDGET_SIZE_DP,
    val widgetOpacity: Float = DEFAULT_WIDGET_OPACITY,
    val colorHue: Float = DEFAULT_COLOR_HUE,
    val runningEnabled: Boolean = false,
    val speedStep: Int = 0,
    val xFraction: Float = AutoScrollState.DEFAULT_X_FRACTION,
    val yFraction: Float = AutoScrollState.DEFAULT_Y_FRACTION,
) {
    companion object {
        const val DEFAULT_PIXELS_PER_NOTCH = 0f
        const val DEFAULT_WIDGET_SIZE_DP = 70
        const val DEFAULT_WIDGET_OPACITY = 1f
        const val DEFAULT_COLOR_HUE = 199f
        val WIDGET_SIZE_RANGE = 60..115
        val WIDGET_OPACITY_RANGE = 0.25f..1f
    }
}

class SettingsRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    init {
        migrateToSingleRingSpeedModel()
        removeRetiredSoundSettings()
    }

    fun snapshot(): AutoScrollSettings {
        return AutoScrollSettings(
            pixelsPerNotch = snapPixelsPerNotch(preferences.getFloat(
                KEY_PIXELS_PER_NOTCH,
                AutoScrollSettings.DEFAULT_PIXELS_PER_NOTCH,
            )),
            hapticEnabled = preferences.getBoolean(KEY_HAPTIC_ENABLED, true),
            widgetSizeDp = preferences.getInt(
                KEY_WIDGET_SIZE,
                AutoScrollSettings.DEFAULT_WIDGET_SIZE_DP,
            ).coerceIn(AutoScrollSettings.WIDGET_SIZE_RANGE),
            widgetOpacity = preferences.getFloat(
                KEY_WIDGET_OPACITY,
                AutoScrollSettings.DEFAULT_WIDGET_OPACITY,
            ).coerceIn(AutoScrollSettings.WIDGET_OPACITY_RANGE),
            colorHue = preferences.getFloat(
                KEY_COLOR_HUE,
                AutoScrollSettings.DEFAULT_COLOR_HUE,
            ).let(::normalizeHue),
            runningEnabled = preferences.getBoolean(KEY_RUNNING_ENABLED, false),
            speedStep = preferences.getInt(KEY_SPEED_STEP, 0)
                .coerceIn(-AutoScrollState.MAX_SPEED_STEP, AutoScrollState.MAX_SPEED_STEP),
            xFraction = preferences.getFloat(
                KEY_X_FRACTION,
                AutoScrollState.DEFAULT_X_FRACTION,
            ).coerceIn(0f, 1f),
            yFraction = preferences.getFloat(
                KEY_Y_FRACTION,
                AutoScrollState.DEFAULT_Y_FRACTION,
            ).coerceIn(0f, 1f),
        )
    }

    fun setPixelsPerNotch(value: Float) {
        preferences.edit()
            .putFloat(KEY_PIXELS_PER_NOTCH, snapPixelsPerNotch(value))
            .apply()
    }

    fun setHapticEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_HAPTIC_ENABLED, enabled).apply()
    }

    fun setWidgetSizeDp(sizeDp: Int) {
        preferences.edit()
            .putInt(KEY_WIDGET_SIZE, sizeDp.coerceIn(AutoScrollSettings.WIDGET_SIZE_RANGE))
            .apply()
    }

    fun setWidgetOpacity(opacity: Float) {
        preferences.edit()
            .putFloat(KEY_WIDGET_OPACITY, opacity.coerceIn(AutoScrollSettings.WIDGET_OPACITY_RANGE))
            .apply()
    }

    fun setColorHue(hue: Float) {
        preferences.edit().putFloat(KEY_COLOR_HUE, normalizeHue(hue)).apply()
    }

    fun setRunningEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_RUNNING_ENABLED, enabled).apply()
    }

    fun saveSpeedStep(speedStep: Int) {
        preferences.edit()
            .putInt(
                KEY_SPEED_STEP,
                speedStep.coerceIn(-AutoScrollState.MAX_SPEED_STEP, AutoScrollState.MAX_SPEED_STEP),
            )
            .apply()
    }

    fun saveOverlayPosition(xFraction: Float, yFraction: Float) {
        preferences.edit()
            .putFloat(KEY_X_FRACTION, xFraction.coerceIn(0f, 1f))
            .putFloat(KEY_Y_FRACTION, yFraction.coerceIn(0f, 1f))
            .apply()
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun migrateToSingleRingSpeedModel() {
        if (preferences.getInt(KEY_SPEED_MODEL_VERSION, 0) >= SINGLE_RING_SPEED_MODEL_VERSION) {
            return
        }
        preferences.edit()
            .remove(KEY_MINIMUM_SPEED)
            .remove(KEY_MAXIMUM_SPEED)
            .putFloat(KEY_PIXELS_PER_NOTCH, AutoScrollSettings.DEFAULT_PIXELS_PER_NOTCH)
            .putInt(KEY_SPEED_STEP, 0)
            .putInt(KEY_SPEED_MODEL_VERSION, SINGLE_RING_SPEED_MODEL_VERSION)
            .commit()
    }

    private fun removeRetiredSoundSettings() {
        if (!preferences.contains(KEY_SOUND_ENABLED) &&
            !preferences.contains(KEY_SOUND_VOLUME_PERCENT)
        ) {
            return
        }
        preferences.edit()
            .remove(KEY_SOUND_ENABLED)
            .remove(KEY_SOUND_VOLUME_PERCENT)
            .apply()
    }

    companion object {
        const val KEY_PIXELS_PER_NOTCH = "pixels_per_notch"
        const val KEY_MINIMUM_SPEED = "minimum_pixels_per_second"
        const val KEY_MAXIMUM_SPEED = "maximum_pixels_per_second"
        const val KEY_HAPTIC_ENABLED = "haptic_enabled"
        private const val KEY_SOUND_ENABLED = "sound_enabled"
        private const val KEY_SOUND_VOLUME_PERCENT = "sound_volume_percent"
        const val KEY_WIDGET_SIZE = "widget_size_dp"
        const val KEY_WIDGET_OPACITY = "widget_opacity"
        const val KEY_COLOR_HUE = "color_hue"
        const val KEY_RUNNING_ENABLED = "running_enabled"
        const val KEY_SPEED_STEP = "speed_step"
        const val KEY_X_FRACTION = "overlay_x_fraction"
        const val KEY_Y_FRACTION = "overlay_y_fraction"
        private const val KEY_SPEED_MODEL_VERSION = "speed_model_version"
        private const val SINGLE_RING_SPEED_MODEL_VERSION = 2

        val PIXELS_PER_NOTCH_RANGE = 0f..100f
        const val PIXELS_PER_NOTCH_STEP = 5f
        private const val PREFERENCES_NAME = "auto_scroll_settings"

        private fun snapPixelsPerNotch(value: Float): Float =
            ((value / PIXELS_PER_NOTCH_STEP).roundToInt() * PIXELS_PER_NOTCH_STEP)
                .coerceIn(PIXELS_PER_NOTCH_RANGE)

        private fun normalizeHue(hue: Float): Float = ((hue % 360f) + 360f) % 360f
    }
}
