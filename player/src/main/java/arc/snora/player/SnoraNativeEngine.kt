package arc.snora.player

import android.view.Surface
import org.json.JSONArray

data class ChapterItem(
    val id: Int,
    val title: String,
    val startMs: Long,
    val endMs: Long
)

object SnoraNativeEngine {

    var isLoaded: Boolean = false
        private set

    init {
        loadLibraries()
    }

    private fun loadLibraries() {
        val ffmpegLibs = listOf(
            "c++_shared",
            "avutil",
            "swresample",
            "avcodec",
            "avformat",
            "swscale",
            "avfilter",
            "avdevice"
        )
        for (lib in ffmpegLibs) {
            try {
                System.loadLibrary(lib)
            } catch (t: Throwable) {
            }
        }

        try {
            System.loadLibrary("snora")
        } catch (t: Throwable) {
        }

        try {
            System.loadLibrary("player")
            isLoaded = true
        } catch (t: Throwable) {
            try {
                System.loadLibrary("snora_player")
                isLoaded = true
            } catch (t2: Throwable) {
                isLoaded = false
            }
        }
    }

    external fun setSurface(surface: Surface?)

    external fun playVideo(url: String)

    external fun stop()

    external fun seekTo(positionMs: Long)

    external fun setSpeed(speed: Float)

    external fun setAspectRatioMode(mode: Int)

    external fun setZoom(zoom: Float)

    external fun setHardwareAccelerationEnabled(enabled: Boolean)

    external fun getDurationMs(): Long

    external fun getCurrentPositionMs(): Long

    external fun isPlaying(): Boolean

    external fun getTracksJson(): String

    external fun getChaptersJson(): String

    external fun getCurrentSubtitleText(): String

    external fun setAudioTrack(streamIndex: Int)

    external fun getVideoWidth(): Int

    external fun getVideoHeight(): Int

    external fun setSubtitleTrack(streamIndex: Int)

    fun getAvailableTracks(): List<TrackItem> {
        if (!isLoaded) return emptyList()
        val jsonStr = try {
            getTracksJson()
        } catch (e: Exception) {
            return emptyList()
        }
        if (jsonStr.isBlank() || jsonStr == "[]") return emptyList()

        val list = mutableListOf<TrackItem>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.optInt("id", i)
                val typeCode = obj.optInt("type", 0)
                val title = obj.optString("title", "Track $id")
                val lang = obj.optString("lang", "und")
                val codec = obj.optString("codec", "")
                val isSelected = obj.optBoolean("isSelected", false)

                val trackType = when (typeCode) {
                    0 -> TrackType.VIDEO
                    1 -> TrackType.AUDIO
                    2 -> TrackType.SUBTITLE
                    else -> TrackType.VIDEO
                }

                list.add(
                    TrackItem(
                        id = id,
                        type = trackType,
                        title = title,
                        lang = lang,
                        codec = codec,
                        isSelected = isSelected
                    )
                )
            }
        } catch (e: Exception) {
        }
        return list
    }

    fun getAvailableChapters(): List<ChapterItem> {
        if (!isLoaded) return emptyList()
        val jsonStr = try {
            getChaptersJson()
        } catch (e: Exception) {
            return emptyList()
        }
        if (jsonStr.isBlank() || jsonStr == "[]") return emptyList()

        val list = mutableListOf<ChapterItem>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.optInt("id", i)
                val title = obj.optString("title", "Chapter ${i + 1}")
                val startMs = obj.optLong("startMs", 0L)
                val endMs = obj.optLong("endMs", 0L)

                list.add(
                    ChapterItem(
                        id = id,
                        title = title,
                        startMs = startMs,
                        endMs = endMs
                    )
                )
            }
        } catch (e: Exception) {
        }
        return list
    }
}
