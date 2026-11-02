package arc.snora.player

import android.graphics.Bitmap

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

enum class AspectRatioMode(val label: String, val description: String) {
    FIT("Original", ""),
    CROP("Crop", ""),
    FILL("Fill", ""),
    RATIO_16_9("16:9", ""),
    RATIO_1_1("1:1", ""),
    RATIO_4_3("4:3", "")
}

enum class HwDecMode(val value: String, val label: String) {
    AUTO("auto", "HW"),
    SW("no", "SW")
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
    val hwdecActive: String = "HW"
)

data class SlideSeekState(
    val isActive: Boolean = false,
    val targetMs: Long = 0L,
    val seekFraction: Float = 0f,
    val thumbnailBitmap: Bitmap? = null
)

data class SubtitleAppearance(
    val horizontalPosition: Float = DEFAULT_HORIZONTAL_POSITION,
    val verticalPosition: Float = DEFAULT_VERTICAL_POSITION,
    val fontSizeSp: Float = DEFAULT_FONT_SIZE_SP
) {
    fun isDefault(): Boolean {
        return kotlin.math.abs(horizontalPosition - DEFAULT_HORIZONTAL_POSITION) < 0.001f &&
            kotlin.math.abs(verticalPosition - DEFAULT_VERTICAL_POSITION) < 0.001f &&
            kotlin.math.abs(fontSizeSp - DEFAULT_FONT_SIZE_SP) < 0.01f
    }

    companion object {
        const val DEFAULT_HORIZONTAL_POSITION = 0.5f
        const val DEFAULT_VERTICAL_POSITION = 0.12f
        const val DEFAULT_FONT_SIZE_SP = 17f
        const val MIN_FONT_SIZE_SP = 10f
        const val MAX_FONT_SIZE_SP = 24f
    }
}

data class PlaybackState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1.0f,
    val isMuted: Boolean = false,
    val volumeLevel: Int = 15,
    val audioDelaySec: Double = 0.0,
    val subDelaySec: Double = 0.0,
    val subScale: Float = 1.0f,
    val isControlsLocked: Boolean = false,
    val isScrubbing: Boolean = false,
    val slideSeekState: SlideSeekState = SlideSeekState(),
    val aspectRatioMode: AspectRatioMode = AspectRatioMode.FIT,
    val hwDecMode: HwDecMode = HwDecMode.AUTO,
    val hwDecFailed: Boolean = false,
    val hwDecCurrent: String = "mediacodec",
    val tracks: List<TrackItem> = emptyList(),
    val chapters: List<ChapterItem> = emptyList(),
    val currentSubtitleText: String = "",
    val subtitleAppearance: SubtitleAppearance? = null,
    val selectedAudioTrackId: Int = -1,
    val selectedSubtitleTrackId: Int = -1,
    val videoInfo: VideoInfo = VideoInfo(),
    val errorMessage: String? = null
)
