package arc.snora.player

import android.content.Context
import android.view.Surface
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SnoraPlayer(private val context: Context) : MPVLib.EventObserver {

    private val playerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    val subtitleManager = SubtitleManager(this)
    val audioManager = AudioManager(this)
    val videoRenderer = VideoRenderer(this)

    private var isInitialized = false

    init {
        initializeMpv()
    }

    private fun initializeMpv() {
        if (isInitialized) return
        try {
            MPVLib.create(context.applicationContext)
            MPVLib.setOptionString("vo", "gpu")
            MPVLib.setOptionString("gpu-context", "android")
            MPVLib.setOptionString("opengl-es", "yes")
            MPVLib.setOptionString("hwdec", "auto")
            MPVLib.setOptionString("hwdec-codecs", "all")
            MPVLib.setOptionString("ao", "audiotrack,opensles")
            MPVLib.setOptionString("tls-verify", "no")
            MPVLib.setOptionString("keep-open", "always")
            MPVLib.setOptionString("sub-auto", "fuzzy")
            MPVLib.setOptionString("sub-font-size", "46")
            MPVLib.init()

            MPVLib.observeProperty("time-pos", MPVLib.MPV_FORMAT_DOUBLE)
            MPVLib.observeProperty("duration", MPVLib.MPV_FORMAT_DOUBLE)
            MPVLib.observeProperty("pause", MPVLib.MPV_FORMAT_FLAG)
            MPVLib.observeProperty("speed", MPVLib.MPV_FORMAT_DOUBLE)
            MPVLib.observeProperty("track-list/count", MPVLib.MPV_FORMAT_INT64)
            MPVLib.observeProperty("eof-reached", MPVLib.MPV_FORMAT_FLAG)
            MPVLib.observeProperty("media-title", MPVLib.MPV_FORMAT_STRING)
            MPVLib.observeProperty("video-codec", MPVLib.MPV_FORMAT_STRING)
            MPVLib.observeProperty("audio-codec", MPVLib.MPV_FORMAT_STRING)
            MPVLib.observeProperty("hwdec-current", MPVLib.MPV_FORMAT_STRING)

            MPVLib.addObserver(this)
            isInitialized = true
        } catch (t: Throwable) {
            _playbackState.value = _playbackState.value.copy(
                status = PlaybackStatus.ERROR,
                errorMessage = t.localizedMessage ?: "Failed to initialize player core"
            )
        }
    }

    fun attachSurface(surface: Surface) {
        if (isInitialized) {
            MPVLib.attachSurface(surface)
        }
    }

    fun detachSurface() {
        if (isInitialized) {
            MPVLib.detachSurface()
        }
    }

    fun playMedia(pathOrUri: String, startPositionMs: Long = 0L) {
        if (!isInitialized) initializeMpv()
        val cmd = if (startPositionMs > 0L) {
            val startSeconds = (startPositionMs / 1000.0).toString()
            arrayOf("loadfile", pathOrUri, "replace", "start=$startSeconds")
        } else {
            arrayOf("loadfile", pathOrUri, "replace")
        }
        MPVLib.command(cmd)
        MPVLib.setPropertyBoolean("pause", false)
        _playbackState.value = _playbackState.value.copy(
            status = PlaybackStatus.BUFFERING,
            positionMs = startPositionMs
        )
    }

    fun pause() {
        MPVLib.setPropertyBoolean("pause", true)
        _playbackState.value = _playbackState.value.copy(status = PlaybackStatus.PAUSED)
    }

    fun resume() {
        MPVLib.setPropertyBoolean("pause", false)
        _playbackState.value = _playbackState.value.copy(status = PlaybackStatus.PLAYING)
    }

    fun togglePlayPause() {
        val currentPause = MPVLib.getPropertyBoolean("pause") ?: false
        if (currentPause) {
            resume()
        } else {
            pause()
        }
    }

    fun seekTo(positionMs: Long) {
        val targetSeconds = (positionMs.coerceAtLeast(0L) / 1000.0).toString()
        MPVLib.command(arrayOf("seek", targetSeconds, "absolute"))
        _playbackState.value = _playbackState.value.copy(positionMs = positionMs)
    }

    fun seekRelative(deltaSeconds: Double) {
        MPVLib.command(arrayOf("seek", deltaSeconds.toString(), "relative"))
    }

    fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.25f, 4.0f)
        MPVLib.setPropertyDouble("speed", clamped.toDouble())
        _playbackState.value = _playbackState.value.copy(speed = clamped)
    }

    fun setControlsLocked(locked: Boolean) {
        _playbackState.value = _playbackState.value.copy(isControlsLocked = locked)
    }

    fun refreshTracks() {
        val count = MPVLib.getPropertyInt("track-list/count") ?: 0
        val trackList = mutableListOf<TrackItem>()
        for (i in 0 until count) {
            val typeStr = MPVLib.getPropertyString("track-list/$i/type") ?: continue
            val id = MPVLib.getPropertyInt("track-list/$i/id") ?: (i + 1)
            val title = MPVLib.getPropertyString("track-list/$i/title") ?: ""
            val lang = MPVLib.getPropertyString("track-list/$i/lang") ?: ""
            val codec = MPVLib.getPropertyString("track-list/$i/codec") ?: ""
            val selected = MPVLib.getPropertyBoolean("track-list/$i/selected") ?: false

            val type = when (typeStr) {
                "video" -> TrackType.VIDEO
                "audio" -> TrackType.AUDIO
                "sub" -> TrackType.SUBTITLE
                else -> continue
            }
            trackList.add(TrackItem(id, type, title, lang, codec, selected))
        }
        _playbackState.value = _playbackState.value.copy(tracks = trackList)
    }

    fun updateState(block: (PlaybackState) -> PlaybackState) {
        _playbackState.value = block(_playbackState.value)
    }

    override fun eventProperty(property: String, value: Double) {
        playerScope.launch {
            when (property) {
                "time-pos" -> {
                    val ms = (value * 1000).toLong()
                    _playbackState.value = _playbackState.value.copy(positionMs = ms)
                }
                "duration" -> {
                    val ms = (value * 1000).toLong()
                    _playbackState.value = _playbackState.value.copy(durationMs = ms)
                }
                "speed" -> {
                    _playbackState.value = _playbackState.value.copy(speed = value.toFloat())
                }
            }
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        playerScope.launch {
            when (property) {
                "pause" -> {
                    val newStatus = if (value) PlaybackStatus.PAUSED else PlaybackStatus.PLAYING
                    _playbackState.value = _playbackState.value.copy(status = newStatus)
                }
                "eof-reached" -> {
                    if (value) {
                        _playbackState.value = _playbackState.value.copy(status = PlaybackStatus.ENDED)
                    }
                }
            }
        }
    }

    override fun eventProperty(property: String, value: Long) {
        playerScope.launch {
            if (property == "track-list/count") {
                refreshTracks()
            }
        }
    }

    override fun eventProperty(property: String, value: String) {
        playerScope.launch {
            videoRenderer.refreshVideoInfo()
        }
    }

    override fun event(eventId: Int) {
        playerScope.launch {
            when (eventId) {
                MPVLib.MPV_EVENT_FILE_LOADED -> {
                    _playbackState.value = _playbackState.value.copy(status = PlaybackStatus.PLAYING)
                    refreshTracks()
                    videoRenderer.refreshVideoInfo()
                }
                MPVLib.MPV_EVENT_END_FILE -> {
                    _playbackState.value = _playbackState.value.copy(status = PlaybackStatus.ENDED)
                }
                MPVLib.MPV_EVENT_SHUTDOWN -> {
                    _playbackState.value = _playbackState.value.copy(status = PlaybackStatus.IDLE)
                }
            }
        }
    }

    fun destroy() {
        if (isInitialized) {
            MPVLib.removeObserver(this)
            MPVLib.destroy()
            isInitialized = false
        }
    }
}
