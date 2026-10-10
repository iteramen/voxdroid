package io.github.iteramen.voxdroid

import kotlin.math.hypot

data class Saved(val aboveKeyboard: Int)

/** Twice the platform's touch slop: a tap's wobble never moves the pill. */
fun isDrag(dx: Float, dy: Float, touchSlop: Float): Boolean = hypot(dx, dy) > 2 * touchSlop

/** The pill's top edge: its height above the keyboard, or above the screen's bottom edge when there is no keyboard. */
fun place(
    saved: Saved?,
    pillH: Int,
    screenH: Int,
    keyboardTop: Int?,
    margin: Int,
): Int {
    val anchor = keyboardTop ?: screenH
    return (anchor - (saved?.aboveKeyboard ?: margin) - pillH).coerceIn(0, maxOf(0, screenH - pillH))
}

/** Keeps the pill's bottom above the keyboard's top, so a pill that grows later grows upward. */
fun remember(pillTop: Int, pillH: Int, screenH: Int, keyboardTop: Int?): Saved =
    Saved((keyboardTop ?: screenH) - (pillTop + pillH))
