package io.github.iteramen.voxdroid

import kotlin.math.roundToInt

internal data class ControlSpace(val width: Int, val height: Int, val controlWidth: Int, val controlHeight: Int, val density: Float) {
    val maxX: Int get() = maxOf(0, width - controlWidth)
    val maxY: Int get() = maxOf(0, height - controlHeight)
}
internal data class ControlPosition(val x: Int, val y: Int)
/** Margins from the nearest physical edges, independent of rotation and button count. */
internal data class ControlAnchor(val right: Boolean, val bottom: Boolean, val horizontalDp: Float, val verticalDp: Float)
internal fun rememberControl(position: ControlPosition, space: ControlSpace): ControlAnchor {
    val x = position.x.coerceIn(0, space.maxX)
    val y = position.y.coerceIn(0, space.maxY)
    val right = x >= space.maxX / 2f
    val bottom = y >= space.maxY / 2f
    return ControlAnchor(right, bottom, (if (right) space.maxX - x else x) / space.density,
        (if (bottom) space.maxY - y else y) / space.density)
}
internal fun placeControl(anchor: ControlAnchor, space: ControlSpace): ControlPosition {
    val dx = (anchor.horizontalDp * space.density).roundToInt()
    val dy = (anchor.verticalDp * space.density).roundToInt()
    return ControlPosition((if (anchor.right) space.maxX - dx else dx).coerceIn(0, space.maxX),
        (if (anchor.bottom) space.maxY - dy else dy).coerceIn(0, space.maxY))
}
