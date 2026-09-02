package com.tsss.gt6lock

data class Detection(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val confidence: Float,
    val classId: Int,
    val label: String,
    val predicted: Boolean = false
) {
    val cx: Float get() = (x1 + x2) * 0.5f
    val cy: Float get() = (y1 + y2) * 0.5f
    val width: Float get() = (x2 - x1).coerceAtLeast(0f)
    val height: Float get() = (y2 - y1).coerceAtLeast(0f)
}
