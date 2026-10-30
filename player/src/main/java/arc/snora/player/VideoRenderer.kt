package arc.snora.player

import `is`.xyz.mpv.MPVLib

class VideoRenderer(private val player: SnoraPlayer) {

    fun setAspectRatioMode(mode: AspectRatioMode) {
        when (mode) {
            AspectRatioMode.FIT -> {
                MPVLib.setPropertyDouble("panscan", 0.0)
                MPVLib.setPropertyDouble("video-aspect-override", -1.0)
            }
            AspectRatioMode.CROP -> {
                MPVLib.setPropertyDouble("panscan", 1.0)
                MPVLib.setPropertyDouble("video-aspect-override", -1.0)
            }
            AspectRatioMode.STRETCH -> {
                MPVLib.setPropertyDouble("panscan", 0.0)
                val width = MPVLib.getPropertyInt("dwidth") ?: 1920
                val height = MPVLib.getPropertyInt("dheight") ?: 1080
                MPVLib.setPropertyDouble("video-aspect-override", width.toDouble() / height.toDouble())
            }
            AspectRatioMode.RATIO_16_9 -> {
                MPVLib.setPropertyDouble("panscan", 0.0)
                MPVLib.setPropertyDouble("video-aspect-override", 16.0 / 9.0)
            }
            AspectRatioMode.RATIO_4_3 -> {
                MPVLib.setPropertyDouble("panscan", 0.0)
                MPVLib.setPropertyDouble("video-aspect-override", 4.0 / 3.0)
            }
        }
        player.updateState { it.copy(aspectRatioMode = mode) }
    }

    fun setHwDecMode(mode: HwDecMode) {
        MPVLib.setPropertyString("hwdec", mode.mpvValue)
        player.updateState { it.copy(hwDecMode = mode) }
        refreshVideoInfo()
    }

    fun cycleAspectRatioMode() {
        val current = player.playbackState.value.aspectRatioMode
        val values = AspectRatioMode.values()
        val nextIndex = (current.ordinal + 1) % values.size
        setAspectRatioMode(values[nextIndex])
    }

    fun refreshVideoInfo() {
        val title = MPVLib.getPropertyString("media-title") ?: ""
        val width = MPVLib.getPropertyInt("dwidth") ?: 0
        val height = MPVLib.getPropertyInt("dheight") ?: 0
        val videoCodec = MPVLib.getPropertyString("video-codec") ?: ""
        val audioCodec = MPVLib.getPropertyString("audio-codec") ?: ""
        val fps = MPVLib.getPropertyDouble("container-fps") ?: 0.0
        val hwdecCurrent = MPVLib.getPropertyString("hwdec-current") ?: "none"

        val info = VideoInfo(
            title = title,
            width = width,
            height = height,
            videoCodec = videoCodec,
            audioCodec = audioCodec,
            fps = fps,
            hwdecActive = hwdecCurrent
        )
        player.updateState { it.copy(videoInfo = info) }
    }
}
