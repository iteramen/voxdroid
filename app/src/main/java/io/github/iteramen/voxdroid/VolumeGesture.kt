package io.github.iteramen.voxdroid

enum class OnDown { Stop, ArmHold, Swallow }

enum class OnUp { ReapplyVolume, Nothing }

/** A hold starts a take, a plain press while recording stops it, and a short press while idle is re-applied as a volume press because the host consumed the key. */
class VolumeGesture {

    private var pressed = false
    private var handled = false

    fun down(repeatCount: Int, recording: Boolean): OnDown {
        if (repeatCount > 0) return OnDown.Swallow
        pressed = true
        handled = recording
        return if (recording) OnDown.Stop else OnDown.ArmHold
    }

    fun holdElapsed(): Boolean {
        if (!pressed || handled) return false
        handled = true
        return true
    }

    fun up(): OnUp {
        val reapply = pressed && !handled
        pressed = false
        handled = false
        return if (reapply) OnUp.ReapplyVolume else OnUp.Nothing
    }
}
