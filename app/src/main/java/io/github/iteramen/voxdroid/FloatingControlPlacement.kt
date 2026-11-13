package io.github.iteramen.voxdroid

internal data class ControlSpace(val width: Int, val height: Int, val controlWidth: Int, val controlHeight: Int, val density: Float) {
    val maxX: Int get() = maxOf(0, width - controlWidth)
    val maxY: Int get() = maxOf(0, height - controlHeight)
}
internal data class ControlPosition(val x: Int, val y: Int)
internal data class ControlAnchor(val x: Int, val y: Int)
internal fun rememberControl(position: ControlPosition, space: ControlSpace): ControlAnchor =
    ControlAnchor(position.x.coerceIn(0, space.maxX), position.y.coerceIn(0, space.maxY))
internal fun placeControl(anchor: ControlAnchor, space: ControlSpace): ControlPosition =
    ControlPosition(anchor.x.coerceIn(0, space.maxX), anchor.y.coerceIn(0, space.maxY))
