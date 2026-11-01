package arc.snora.player

class VideoRenderer(private val player: SnoraPlayer) {

    fun setAspectRatioMode(mode: AspectRatioMode) {
        player.setAspectRatioMode(mode)
    }

    fun cycleAspectRatioMode() {
        val current = player.playbackState.value.aspectRatioMode
        val values = AspectRatioMode.values()
        val nextIndex = (current.ordinal + 1) % values.size
        setAspectRatioMode(values[nextIndex])
    }

    fun toggleHwDec() {
        player.toggleHwDec()
    }

    fun setHwDecMode(mode: HwDecMode) {
        player.setHwDecMode(mode)
    }
}
