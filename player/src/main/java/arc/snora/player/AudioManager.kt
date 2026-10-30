package arc.snora.player

import `is`.xyz.mpv.MPVLib

class AudioManager(private val player: SnoraPlayer) {

    fun selectAudioTrack(trackId: Int) {
        if (trackId <= 0) {
            MPVLib.setPropertyString("aid", "no")
        } else {
            MPVLib.setPropertyInt("aid", trackId)
        }
        player.refreshTracks()
    }

    fun setAudioDelay(seconds: Double) {
        MPVLib.setPropertyDouble("audio-delay", seconds)
        player.updateState { it.copy(audioDelaySec = seconds) }
    }

    fun adjustAudioDelay(deltaSeconds: Double) {
        val current = MPVLib.getPropertyDouble("audio-delay") ?: 0.0
        val target = current + deltaSeconds
        setAudioDelay(target)
    }

    fun setVolume(volume: Int) {
        val clamped = volume.coerceIn(0, 150)
        MPVLib.setPropertyInt("volume", clamped)
        player.updateState { it.copy(volume = clamped) }
    }

    fun adjustVolume(delta: Int) {
        val current = MPVLib.getPropertyInt("volume") ?: 100
        setVolume(current + delta)
    }

    fun setMute(muted: Boolean) {
        MPVLib.setPropertyBoolean("mute", muted)
        player.updateState { it.copy(isMuted = muted) }
    }

    fun toggleMute() {
        val current = MPVLib.getPropertyBoolean("mute") ?: false
        setMute(!current)
    }
}
