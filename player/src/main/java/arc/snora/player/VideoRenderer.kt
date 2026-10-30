package arc.snora.player

class VideoRenderer(private val player: SnoraPlayer) {

    fun setAspectRatioMode(mode: AspectRatioMode) {
        player.updateState { it.copy(aspectRatioMode = mode) }
    }

    fun setHwDecMode(mode: HwDecMode) {
        player.updateState { it.copy(hwDecMode = mode) }
    }

    fun cycleAspectRatioMode() {
        val current = player.playbackState.value.aspectRatioMode
        val values = AspectRatioMode.values()
        val nextIndex = (current.ordinal + 1) % values.size
        setAspectRatioMode(values[nextIndex])
    }

    fun refreshVideoInfo() {
        val current = player.playbackState.value.videoInfo
        val hwdec = if (FFmpegBridge.isLoaded) "FFmpeg MediaCodec Active" else "Native Hardware"
        player.updateState {
            it.copy(videoInfo = current.copy(hwdecActive = hwdec))
        }
    }
}
