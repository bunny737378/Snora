package arc.snora.player

enum class PlaybackStatus {
    IDLE,
    BUFFERING,
    PLAYING,
    PAUSED,
    ENDED,
    ERROR
}

enum class TrackType {
    VIDEO,
    AUDIO,
    SUBTITLE
}

enum class AspectRatioMode(val label: String) {
    FIT("Fit to Screen"),
    CROP("Crop & Fill"),
    STRETCH("Stretch"),
    RATIO_16_9("16:9"),
    RATIO_4_3("4:3")
}

enum class HwDecMode(val value: String, val label: String) {
    AUTO("auto", "Auto (Hardware + SW Fallback)"),
    SW("sw", "Software (SW Decoders Only)")
}

data class TrackItem(
    val id: Int,
    val type: TrackType,
    val title: String,
    val lang: String,
    val codec: String,
    val isSelected: Boolean
)

data class VideoInfo(
    val title: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val videoCodec: String = "",
    val audioCodec: String = "",
    val fps: Double = 0.0,
    val hwdecActive: String = "Auto (MediaCodec)"
)

data class PlaybackState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1.0f,
    val isMuted: Boolean = false,
    val volume: Int = 100,
    val audioDelaySec: Double = 0.0,
    val subDelaySec: Double = 0.0,
    val subScale: Float = 1.0f,
    val isControlsLocked: Boolean = false,
    val isScrubbing: Boolean = false,
    val aspectRatioMode: AspectRatioMode = AspectRatioMode.FIT,
    val hwDecMode: HwDecMode = HwDecMode.AUTO,
    val tracks: List<TrackItem> = emptyList(),
    val chapters: List<ChapterItem> = emptyList(),
    val currentSubtitleText: String = "",
    val selectedAudioTrackId: Int = -1,
    val selectedSubtitleTrackId: Int = -1,
    val videoInfo: VideoInfo = VideoInfo(),
    val errorMessage: String? = null
)
