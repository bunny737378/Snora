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
    FIT("Fit", "Video ko bina crop kiye poora dikhata hai"),
    CROP("Crop", "Video ke extra parts ko kaatkar required size mein dikhata hai"),
    FILL("Fill", "Video ko poori screen par dikhata hai"),
    RATIO_16_9("16:9", "16:9 widescreen aspect ratio"),
    RATIO_1_1("1:1", "1:1 square aspect ratio"),
    RATIO_4_3("4:3", "4:3 standard aspect ratio")
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
    val selectedAudioTrackId: Int = -1,
    val selectedSubtitleTrackId: Int = -1,
    val videoInfo: VideoInfo = VideoInfo(),
    val errorMessage: String? = null
)
