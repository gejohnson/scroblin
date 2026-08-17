package com.scroblin.app.scroll

data class GestureProfile(
    val startYFraction: Float = 0.70f,
    val endYFraction: Float = 0.30f,
    val minStrokeDistanceDp: Float = 48f,
    val maxStrokeDurationMs: Long = 560L,
    val initialEscapeDistanceDp: Float = 12f,
    val initialEscapeDurationMs: Long = 42L,
    val targetCycleDurationMs: Long = 300L,
)
