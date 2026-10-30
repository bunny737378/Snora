package arc.snora.player

import `is`.xyz.mpv.MPVLib

class SubtitleManager(private val player: SnoraPlayer) {

    fun loadExternalSubtitle(path: String, selectImmediately: Boolean = true) {
        val flag = if (selectImmediately) "select" else "auto"
        MPVLib.command(arrayOf("sub-add", path, flag))
        player.refreshTracks()
    }

    fun selectSubtitleTrack(trackId: Int) {
        if (trackId <= 0) {
            MPVLib.setPropertyString("sid", "no")
        } else {
            MPVLib.setPropertyInt("sid", trackId)
        }
        player.refreshTracks()
    }

    fun disableSubtitles() {
        MPVLib.setPropertyString("sid", "no")
        player.refreshTracks()
    }

    fun setSubtitleDelay(seconds: Double) {
        MPVLib.setPropertyDouble("sub-delay", seconds)
        player.updateState { it.copy(subDelaySec = seconds) }
    }

    fun adjustSubtitleDelay(deltaSeconds: Double) {
        val current = MPVLib.getPropertyDouble("sub-delay") ?: 0.0
        val target = current + deltaSeconds
        setSubtitleDelay(target)
    }

    fun setSubtitleScale(scale: Float) {
        val clamped = scale.coerceIn(0.5f, 3.0f)
        MPVLib.setPropertyDouble("sub-scale", clamped.toDouble())
        player.updateState { it.copy(subScale = clamped) }
    }

    fun setSubtitleVisibility(visible: Boolean) {
        MPVLib.setPropertyBoolean("sub-visibility", visible)
    }
}
