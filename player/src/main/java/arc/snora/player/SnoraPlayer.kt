package arc.snora.player

import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.view.Surface
import android.view.SurfaceHolder
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SnoraPlayer(private val context: Context) : MPVLib.EventObserver {

    private val playerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var currentSurface: Surface? = null
    private var progressJob: Job? = null
    private var currentPathOrUri: String = ""
    private var isMpvInitialized: Boolean = false
    private var isMpvActive: Boolean = false

    val subtitleManager = SubtitleManager(this)
    val audioManager = AudioManager(this)
    val videoRenderer = VideoRenderer(this)

    init {
        try {
            MPVLib.addObserver(this)
        } catch (t: Throwable) {
        }
        _playbackState.value = _playbackState.value.copy(
            videoInfo = VideoInfo(
                hwdecActive = "libsnora (MPV Native Core)"
            )
        )
    }

    private fun ensureMpvCreated() {
        if (!isMpvInitialized) {
            try {
                MPVLib.create(context.applicationContext)
                MPVLib.init()
                MPVLib.setOptionString("hwdec", "auto")
                MPVLib.setOptionString("vo", "gpu")
                isMpvInitialized = true
            } catch (t: Throwable) {
                isMpvInitialized = false
            }
        }
    }

    private fun getOrCreateMediaPlayer(): MediaPlayer {
        mediaPlayer?.let { return it }
        val player = MediaPlayer().apply {
            setOnPreparedListener { mp ->
                val duration = mp.duration.toLong().coerceAtLeast(0L)
                _playbackState.value = _playbackState.value.copy(
                    status = PlaybackStatus.PLAYING,
                    durationMs = if (duration > 0) duration else _playbackState.value.durationMs
                )
                refreshTracks()
                videoRenderer.refreshVideoInfo()
                mp.start()
                startProgressTracker()
            }
            setOnCompletionListener {
                _playbackState.value = _playbackState.value.copy(status = PlaybackStatus.ENDED)
            }
            setOnErrorListener { _, what, extra ->
                _playbackState.value = _playbackState.value.copy(
                    status = PlaybackStatus.ERROR,
                    errorMessage = "Playback error: $what, extra: $extra"
                )
                true
            }
            setOnVideoSizeChangedListener { _, width, height ->
                if (width > 0 && height > 0) {
                    val current = _playbackState.value.videoInfo
                    _playbackState.value = _playbackState.value.copy(
                        videoInfo = current.copy(width = width, height = height)
                    )
                }
            }
        }
        currentSurface?.let { player.setSurface(it) }
        mediaPlayer = player
        return player
    }

    fun attachSurface(surface: Surface) {
        currentSurface = surface
        try {
            ensureMpvCreated()
            MPVLib.attachSurface(surface)
        } catch (t: Throwable) {
        }
        try {
            mediaPlayer?.setSurface(surface)
        } catch (e: Exception) {
        }
    }

    fun attachSurfaceHolder(holder: SurfaceHolder) {
        attachSurface(holder.surface)
        try {
            mediaPlayer?.setDisplay(holder)
        } catch (e: Exception) {
        }
    }

    fun detachSurface() {
        currentSurface = null
        try {
            MPVLib.detachSurface()
        } catch (t: Throwable) {
        }
        try {
            mediaPlayer?.setSurface(null)
        } catch (e: Exception) {
        }
    }

    fun playMedia(pathOrUri: String, startPositionMs: Long = 0L) {
        currentPathOrUri = pathOrUri

        try {
            ensureMpvCreated()
            playWithMpv(pathOrUri, startPositionMs)
            return
        } catch (t: Throwable) {
        }

        val player = getOrCreateMediaPlayer()
        try {
            isMpvActive = false
            progressJob?.cancel()
            player.reset()
            currentSurface?.let { player.setSurface(it) }

            if (pathOrUri.startsWith("content://") || pathOrUri.startsWith("file://") || pathOrUri.startsWith("http://") || pathOrUri.startsWith("https://")) {
                player.setDataSource(context, Uri.parse(pathOrUri))
            } else {
                player.setDataSource(pathOrUri)
            }

            _playbackState.value = _playbackState.value.copy(
                status = PlaybackStatus.BUFFERING,
                positionMs = startPositionMs,
                videoInfo = _playbackState.value.videoInfo.copy(hwdecActive = "Hardware (MediaCodec)")
            )

            player.prepareAsync()
        } catch (t: Throwable) {
            _playbackState.value = _playbackState.value.copy(
                status = PlaybackStatus.ERROR,
                errorMessage = t.localizedMessage ?: "Failed to open video"
            )
        }
    }

    private fun playWithMpv(pathOrUri: String, startPositionMs: Long = 0L) {
        isMpvActive = true
        mediaPlayer?.reset()
        currentSurface?.let { MPVLib.attachSurface(it) }

        _playbackState.value = _playbackState.value.copy(
            status = PlaybackStatus.PLAYING,
            positionMs = startPositionMs,
            videoInfo = _playbackState.value.videoInfo.copy(hwdecActive = "libsnora (MPV Native Core)")
        )

        MPVLib.command(arrayOf("loadfile", pathOrUri))
        if (startPositionMs > 0L) {
            val startSec = (startPositionMs / 1000.0).toString()
            MPVLib.command(arrayOf("seek", startSec, "absolute"))
        }

        startMpvProgressTracker()
    }

    private fun startMpvProgressTracker() {
        progressJob?.cancel()
        progressJob = playerScope.launch {
            while (isActive && isMpvActive) {
                val timePosSec = MPVLib.getPropertyDouble("time-pos") ?: 0.0
                val durationSec = MPVLib.getPropertyDouble("duration") ?: 0.0
                val isPaused = MPVLib.getPropertyBoolean("pause") ?: false

                val posMs = (timePosSec * 1000).toLong().coerceAtLeast(0L)
                val durMs = (durationSec * 1000).toLong().coerceAtLeast(0L)

                val width = MPVLib.getPropertyInt("video-out-params/dw") ?: MPVLib.getPropertyInt("width") ?: 0
                val height = MPVLib.getPropertyInt("video-out-params/dh") ?: MPVLib.getPropertyInt("height") ?: 0

                val currentInfo = _playbackState.value.videoInfo
                var newInfo = currentInfo
                if (width > 0 && height > 0 && (currentInfo.width != width || currentInfo.height != height)) {
                    newInfo = currentInfo.copy(width = width, height = height)
                }

                _playbackState.value = _playbackState.value.copy(
                    durationMs = if (durMs > 0) durMs else _playbackState.value.durationMs,
                    positionMs = posMs,
                    videoInfo = newInfo,
                    status = when {
                        isPaused -> PlaybackStatus.PAUSED
                        durMs > 0 && posMs >= durMs -> PlaybackStatus.ENDED
                        else -> PlaybackStatus.PLAYING
                    }
                )
                delay(250)
            }
        }
    }

    fun pause() {
        if (isMpvActive) {
            try {
                MPVLib.setPropertyBoolean("pause", true)
                _playbackState.value = _playbackState.value.copy(status = PlaybackStatus.PAUSED)
            } catch (t: Throwable) {
            }
            return
        }
        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.pause()
            }
            _playbackState.value = _playbackState.value.copy(status = PlaybackStatus.PAUSED)
        } catch (e: Exception) {
        }
    }

    fun resume() {
        if (isMpvActive) {
            try {
                MPVLib.setPropertyBoolean("pause", false)
                _playbackState.value = _playbackState.value.copy(status = PlaybackStatus.PLAYING)
            } catch (t: Throwable) {
            }
            return
        }
        try {
            mediaPlayer?.let {
                it.start()
                startProgressTracker()
            }
            _playbackState.value = _playbackState.value.copy(status = PlaybackStatus.PLAYING)
        } catch (e: Exception) {
        }
    }

    fun togglePlayPause() {
        if (isMpvActive) {
            val isPaused = MPVLib.getPropertyBoolean("pause") ?: false
            if (isPaused) {
                resume()
            } else {
                pause()
            }
            return
        }
        val player = mediaPlayer ?: return
        try {
            if (player.isPlaying) {
                pause()
            } else {
                resume()
            }
        } catch (e: Exception) {
        }
    }

    fun seekTo(positionMs: Long) {
        val clamped = positionMs.coerceIn(0L, _playbackState.value.durationMs.coerceAtLeast(1L))
        if (isMpvActive) {
            val sec = (clamped / 1000.0).toString()
            MPVLib.command(arrayOf("seek", sec, "absolute"))
            _playbackState.value = _playbackState.value.copy(positionMs = clamped)
            return
        }
        try {
            val player = mediaPlayer ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                player.seekTo(clamped, MediaPlayer.SEEK_CLOSEST)
            } else {
                player.seekTo(clamped.toInt())
            }
            _playbackState.value = _playbackState.value.copy(positionMs = clamped)
        } catch (e: Exception) {
        }
    }

    fun seekRelative(deltaSeconds: Double) {
        if (isMpvActive) {
            MPVLib.command(arrayOf("seek", deltaSeconds.toString(), "relative"))
            return
        }
        val current = _playbackState.value.positionMs
        val target = current + (deltaSeconds * 1000).toLong()
        seekTo(target)
    }

    fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.25f, 4.0f)
        if (isMpvActive) {
            try {
                MPVLib.setPropertyDouble("speed", clamped.toDouble())
                _playbackState.value = _playbackState.value.copy(speed = clamped)
            } catch (t: Throwable) {
            }
            return
        }
        try {
            val player = mediaPlayer ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val params = player.playbackParams ?: PlaybackParams()
                params.speed = clamped
                player.playbackParams = params
            }
            _playbackState.value = _playbackState.value.copy(speed = clamped)
        } catch (e: Exception) {
        }
    }

    fun setAspectRatioMode(mode: AspectRatioMode) {
        val aspectString = when (mode) {
            AspectRatioMode.FIT -> "-1"
            AspectRatioMode.CROP -> "16:9"
            AspectRatioMode.STRETCH -> "4:3"
            else -> "-1"
        }
        if (isMpvActive) {
            try {
                MPVLib.setPropertyString("video-aspect-override", aspectString)
            } catch (t: Throwable) {
            }
        }
        _playbackState.value = _playbackState.value.copy(aspectRatioMode = mode)
    }

    fun setZoom(zoom: Float) {
        val clamped = zoom.coerceIn(0.5f, 3.0f)
        if (isMpvActive) {
            try {
                MPVLib.setPropertyDouble("video-zoom", (clamped - 1.0).toDouble())
            } catch (t: Throwable) {
            }
        }
    }

    fun setHardwareAccelerationEnabled(enabled: Boolean) {
        if (isMpvActive) {
            try {
                MPVLib.setPropertyString("hwdec", if (enabled) "auto" else "no")
            } catch (t: Throwable) {
            }
        }
        val mode = if (enabled) HwDecMode.AUTO else HwDecMode.SW
        _playbackState.value = _playbackState.value.copy(hwDecMode = mode)
    }

    fun setControlsLocked(locked: Boolean) {
        _playbackState.value = _playbackState.value.copy(isControlsLocked = locked)
    }

    fun selectTrack(type: TrackType, trackIndex: Int) {
        if (isMpvActive) {
            val prop = when (type) {
                TrackType.AUDIO -> "aid"
                TrackType.SUBTITLE -> "sid"
                TrackType.VIDEO -> "vid"
            }
            val valStr = if (trackIndex < 0) "no" else (trackIndex + 1).toString()
            try {
                MPVLib.setPropertyString(prop, valStr)
            } catch (t: Throwable) {
            }
            val updatedTracks = _playbackState.value.tracks.map { track ->
                if (track.type == type) {
                    track.copy(isSelected = (track.id == trackIndex))
                } else {
                    track
                }
            }
            _playbackState.value = _playbackState.value.copy(tracks = updatedTracks)
            return
        }

        try {
            val player = mediaPlayer ?: return
            if (trackIndex < 0) {
                val currentTracks = _playbackState.value.tracks
                currentTracks.filter { it.type == type && it.isSelected }.forEach {
                    player.deselectTrack(it.id)
                }
                if (type == TrackType.SUBTITLE) {
                    _playbackState.value = _playbackState.value.copy(selectedSubtitleTrackId = -1)
                }
            } else {
                player.selectTrack(trackIndex)
                if (type == TrackType.AUDIO) {
                    _playbackState.value = _playbackState.value.copy(selectedAudioTrackId = trackIndex)
                } else if (type == TrackType.SUBTITLE) {
                    _playbackState.value = _playbackState.value.copy(selectedSubtitleTrackId = trackIndex)
                }
            }

            val updatedTracks = _playbackState.value.tracks.map { track ->
                if (track.type == type) {
                    track.copy(isSelected = (track.id == trackIndex))
                } else {
                    track
                }
            }
            _playbackState.value = _playbackState.value.copy(tracks = updatedTracks)
        } catch (e: Exception) {
        }
    }

    fun refreshTracks() {
        val player = mediaPlayer ?: return
        try {
            val trackInfoArray = player.trackInfo ?: emptyArray()
            val trackList = mutableListOf<TrackItem>()

            trackInfoArray.forEachIndexed { index, info ->
                val trackType = when (info.trackType) {
                    MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO -> TrackType.AUDIO
                    MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE,
                    MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT -> TrackType.SUBTITLE
                    MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_VIDEO -> TrackType.VIDEO
                    else -> null
                }

                if (trackType != null) {
                    val lang = info.language.ifEmpty { "und" }
                    val format = info.format
                    val codec = format?.getString("mime") ?: "standard"
                    val title = "Track ${index + 1} ($lang)"

                    val isSelected = if (trackType == TrackType.AUDIO) {
                        index == _playbackState.value.selectedAudioTrackId || (_playbackState.value.selectedAudioTrackId == -1 && trackList.none { it.type == TrackType.AUDIO })
                    } else {
                        index == _playbackState.value.selectedSubtitleTrackId
                    }

                    trackList.add(
                        TrackItem(
                            id = index,
                            type = trackType,
                            title = title,
                            lang = lang,
                            codec = codec,
                            isSelected = isSelected
                        )
                    )
                }
            }
            if (trackList.isNotEmpty()) {
                _playbackState.value = _playbackState.value.copy(tracks = trackList)
            }
        } catch (e: Exception) {
        }
    }

    fun updateState(block: (PlaybackState) -> PlaybackState) {
        _playbackState.value = block(_playbackState.value)
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = playerScope.launch {
            while (isActive) {
                mediaPlayer?.let { player ->
                    try {
                        if (player.isPlaying) {
                            val currentPos = player.currentPosition.toLong()
                            _playbackState.value = _playbackState.value.copy(positionMs = currentPos)
                        }
                    } catch (e: Exception) {
                    }
                }
                delay(250)
            }
        }
    }

    override fun eventProperty(property: String) {}
    override fun eventProperty(property: String, value: Long) {}
    override fun eventProperty(property: String, value: Boolean) {}
    override fun eventProperty(property: String, value: String) {}
    override fun eventProperty(property: String, value: Double) {}
    override fun event(eventId: Int) {
        if (eventId == MPVLib.MpvEvent.MPV_EVENT_END_FILE) {
            _playbackState.value = _playbackState.value.copy(status = PlaybackStatus.ENDED)
        } else if (eventId == MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED) {
            _playbackState.value = _playbackState.value.copy(status = PlaybackStatus.PLAYING)
        }
    }

    fun destroy() {
        progressJob?.cancel()
        try {
            MPVLib.removeObserver(this)
            if (isMpvInitialized) {
                MPVLib.command(arrayOf("stop"))
                MPVLib.detachSurface()
                MPVLib.destroy()
                isMpvInitialized = false
            }
        } catch (t: Throwable) {
        }
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {
        }
        mediaPlayer = null
        currentSurface = null
        _playbackState.value = PlaybackState(status = PlaybackStatus.IDLE)
    }
}
