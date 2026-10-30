package arc.snora.player

class SubtitleManager(private val player: SnoraPlayer) {

    fun loadExternalSubtitle(path: String, selectImmediately: Boolean = true) {
        player.refreshTracks()
    }

    fun selectSubtitleTrack(trackId: Int) {
        player.selectTrack(TrackType.SUBTITLE, trackId)
    }

    fun disableSubtitles() {
        player.selectTrack(TrackType.SUBTITLE, -1)
    }

    fun setSubtitleDelay(seconds: Double) {
        player.updateState { it.copy(subDelaySec = seconds) }
    }

    fun adjustSubtitleDelay(deltaSeconds: Double) {
        val current = player.playbackState.value.subDelaySec
        setSubtitleDelay(current + deltaSeconds)
    }

    fun setSubtitleScale(scale: Float) {
        val clamped = scale.coerceIn(0.5f, 3.0f)
        player.updateState { it.copy(subScale = clamped) }
    }

    fun setSubtitleVisibility(visible: Boolean) {
        if (!visible) {
            disableSubtitles()
        }
    }
}
