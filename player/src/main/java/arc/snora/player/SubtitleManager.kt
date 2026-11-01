package arc.snora.player

class SubtitleManager(private val player: SnoraPlayer) {

    fun loadExternalSubtitle(pathOrUri: String) {
        player.loadExternalSubtitle(pathOrUri)
    }

    fun selectSubtitleTrack(trackId: Int) {
        player.selectTrack(TrackType.SUBTITLE, trackId)
    }

    fun disableSubtitles() {
        player.selectTrack(TrackType.SUBTITLE, -1)
    }

    fun setSubtitleDelay(seconds: Double) {
        player.setSubtitleDelay(seconds)
    }

    fun adjustSubtitleDelay(deltaSeconds: Double) {
        val current = player.playbackState.value.subDelaySec
        setSubtitleDelay(current + deltaSeconds)
    }
}
