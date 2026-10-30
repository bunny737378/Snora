package arc.snora.player

object FFmpegBridge {

    var isLoaded: Boolean = false
        private set

    val loadedLibraries = mutableListOf<String>()

    init {
        loadLibraries()
    }

    private fun loadLibraries() {
        val libs = listOf(
            "avutil",
            "swresample",
            "avcodec",
            "avformat",
            "swscale",
            "avfilter",
            "avdevice"
        )

        for (lib in libs) {
            try {
                System.loadLibrary(lib)
                loadedLibraries.add(lib)
            } catch (t: Throwable) {
            }
        }

        isLoaded = loadedLibraries.isNotEmpty()
    }

    fun getConfigurationInfo(): String {
        return if (isLoaded) {
            "FFmpeg Custom Ultimate (${loadedLibraries.size} modules active)"
        } else {
            "FFmpeg Standard / MediaCodec"
        }
    }
}
