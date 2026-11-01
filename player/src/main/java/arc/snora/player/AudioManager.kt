package arc.snora.player

class AudioManager(private val player: SnoraPlayer) {

    fun selectAudioTrack(trackId: Int) {
        player.selectTrack(TrackType.AUDIO, trackId)
    }

    fun disableAudio() {
        player.selectTrack(TrackType.AUDIO, -1)
    }

    fun loadExternalAudio(pathOrUri: String) {
        player.loadExternalAudio(pathOrUri)
    }

    fun setAudioDelay(seconds: Double) {
        player.setAudioDelay(seconds)
    }

    fun adjustAudioDelay(deltaSeconds: Double) {
        val current = player.playbackState.value.audioDelaySec
        setAudioDelay(current + deltaSeconds)
    }

    fun setVolume30(level: Int) {
        player.setVolume30(level)
    }
}
