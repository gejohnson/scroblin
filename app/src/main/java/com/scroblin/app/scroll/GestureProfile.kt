package com.scroblin.app.scroll

data class GestureProfile(
    val startYFraction: Float = 0.72f,
    val endYFraction: Float = 0.28f,
    val minStrokeDistanceDp: Float = 4f,
    val maxStrokeDurationMs: Long = 700L,
    val initialEscapeDistanceDp: Float = 8f,
    val initialEscapeDurationMs: Long = 30L,
    val targetCycleDurationMs: Long = 420L,
)
