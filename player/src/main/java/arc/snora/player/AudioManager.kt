package arc.snora.player

class AudioManager(private val player: SnoraPlayer) {

    fun selectAudioTrack(trackId: Int) {
        player.selectTrack(TrackType.AUDIO, trackId)
    }

    fun setAudioDelay(seconds: Double) {
        player.updateState { it.copy(audioDelaySec = seconds) }
    }

    fun adjustAudioDelay(deltaSeconds: Double) {
        val current = player.playbackState.value.audioDelaySec
        setAudioDelay(current + deltaSeconds)
    }

    fun setVolume(volume: Int) {
        val clamped = volume.coerceIn(0, 150)
        player.updateState { it.copy(volume = clamped) }
    }

    fun adjustVolume(delta: Int) {
        val current = player.playbackState.value.volume
        setVolume(current + delta)
    }

    fun setMute(muted: Boolean) {
        player.updateState { it.copy(isMuted = muted) }
    }

    fun toggleMute() {
        val current = player.playbackState.value.isMuted
        setMute(!current)
    }
}
