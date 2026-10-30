package arc.snora.player

import android.view.Surface

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
            System.loadLibrary("snora_player")
            isLoaded = true
        } catch (t: Throwable) {
            isLoaded = false
        }
    }

    external fun setSurface(surface: Surface?)

    external fun playVideo(url: String)

    external fun stop()

    external fun getDurationMs(): Long

    external fun getCurrentPositionMs(): Long

    external fun isPlaying(): Boolean
}
