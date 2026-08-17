package com.scroblin.app.settings

import java.util.concurrent.CopyOnWriteArraySet

object AppVisibilityTracker {
    @Volatile
    var settingsVisible: Boolean = false
        private set

    private val listeners = CopyOnWriteArraySet<(Boolean) -> Unit>()

    fun setSettingsVisible(visible: Boolean) {
        if (settingsVisible == visible) return
        settingsVisible = visible
        listeners.forEach { it(visible) }
    }

    fun addListener(listener: (Boolean) -> Unit) {
        listeners += listener
        listener(settingsVisible)
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners -= listener
    }
}
