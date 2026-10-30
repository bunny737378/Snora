package arc.snora.player

import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.view.Surface
import android.view.SurfaceHolder
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

class SnoraPlayer(private val context: Context) {

    private val playerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var currentSurface: Surface? = null
    private var progressJob: Job? = null
    private var currentPathOrUri: String = ""
    private var activeStreamServer: FFmpegStreamServer? = null
    private var isUsingFFmpegStream: Boolean = false

    val subtitleManager = SubtitleManager(this)
    val audioManager = AudioManager(this)
    val videoRenderer = VideoRenderer(this)

    init {
        playerScope.launch {
            FFmpegEngine.initialize(context)
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
                if (!isUsingFFmpegStream) {
                    switchToFFmpegPipeline(currentPathOrUri, _playbackState.value.positionMs)
                    true
                } else {
                    _playbackState.value = _playbackState.value.copy(
                        status = PlaybackStatus.ERROR,
                        errorMessage = "Playback error: $what, extra: $extra"
                    )
                    true
                }
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
            mediaPlayer?.setSurface(surface)
        } catch (e: Exception) {
        }
    }

    fun attachSurfaceHolder(holder: SurfaceHolder) {
        currentSurface = holder.surface
        try {
            mediaPlayer?.setDisplay(holder)
        } catch (e: Exception) {
        }
    }

    fun detachSurface() {
        currentSurface = null
        try {
            mediaPlayer?.setSurface(null)
        } catch (e: Exception) {
        }
    }

    fun playMedia(pathOrUri: String, startPositionMs: Long = 0L) {
        currentPathOrUri = pathOrUri
        isUsingFFmpegStream = false
        stopStreamServer()

        val isSwMode = _playbackState.value.hwDecMode == HwDecMode.SW
        val isMatroska = pathOrUri.lowercase().contains(".mkv") || pathOrUri.lowercase().contains(".webm")

        if (isSwMode || isMatroska) {
            switchToFFmpegPipeline(pathOrUri, startPositionMs)
            return
        }

        val player = getOrCreateMediaPlayer()
        try {
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

            playerScope.launch {
                val probedTracks = FFmpegEngine.probeMedia(context, pathOrUri)
                if (probedTracks.isNotEmpty()) {
                    _playbackState.value = _playbackState.value.copy(tracks = probedTracks)
                }
            }
        } catch (t: Throwable) {
            switchToFFmpegPipeline(pathOrUri, startPositionMs)
        }
    }

    private fun switchToFFmpegPipeline(pathOrUri: String, startPositionMs: Long) {
        isUsingFFmpegStream = true
        val player = getOrCreateMediaPlayer()
        playerScope.launch {
            try {
                progressJob?.cancel()
                player.reset()
                currentSurface?.let { player.setSurface(it) }

                stopStreamServer()
                val server = FFmpegEngine.createStreamServer(
                    context = context,
                    sourcePath = pathOrUri,
                    startSec = startPositionMs / 1000L,
                    forceTranscode = (_playbackState.value.hwDecMode == HwDecMode.SW)
                )

                if (server != null) {
                    activeStreamServer = server
                    val streamUrl = server.start()

                    player.setDataSource(streamUrl)
                    _playbackState.value = _playbackState.value.copy(
                        status = PlaybackStatus.BUFFERING,
                        positionMs = startPositionMs,
                        videoInfo = _playbackState.value.videoInfo.copy(hwdecActive = "FFmpeg Custom SW (NEWWFFMCREATEE)")
                    )
                    player.prepareAsync()
                } else {
                    player.setDataSource(context, Uri.parse(pathOrUri))
                    player.prepareAsync()
                }

                val probedTracks = FFmpegEngine.probeMedia(context, pathOrUri)
                if (probedTracks.isNotEmpty()) {
                    _playbackState.value = _playbackState.value.copy(tracks = probedTracks)
                }
            } catch (e: Exception) {
                _playbackState.value = _playbackState.value.copy(
                    status = PlaybackStatus.ERROR,
                    errorMessage = e.localizedMessage ?: "Failed to play with FFmpeg engine"
                )
            }
        }
    }

    private fun stopStreamServer() {
        activeStreamServer?.stop()
        activeStreamServer = null
    }

    fun pause() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.pause()
            }
            _playbackState.value = _playbackState.value.copy(status = PlaybackStatus.PAUSED)
        } catch (e: Exception) {
        }
    }

    fun resume() {
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
        try {
            val player = mediaPlayer ?: return
            val clamped = positionMs.coerceIn(0L, _playbackState.value.durationMs.coerceAtLeast(1L))

            if (isUsingFFmpegStream) {
                switchToFFmpegPipeline(currentPathOrUri, clamped)
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    player.seekTo(clamped, MediaPlayer.SEEK_CLOSEST)
                } else {
                    player.seekTo(clamped.toInt())
                }
                _playbackState.value = _playbackState.value.copy(positionMs = clamped)
            }
        } catch (e: Exception) {
        }
    }

    fun seekRelative(deltaSeconds: Double) {
        val current = _playbackState.value.positionMs
        val target = current + (deltaSeconds * 1000).toLong()
        seekTo(target)
    }

    fun setSpeed(speed: Float) {
        try {
            val player = mediaPlayer ?: return
            val clamped = speed.coerceIn(0.25f, 4.0f)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val params = player.playbackParams ?: PlaybackParams()
                params.speed = clamped
                player.playbackParams = params
            }
            _playbackState.value = _playbackState.value.copy(speed = clamped)
        } catch (e: Exception) {
        }
    }

    fun setControlsLocked(locked: Boolean) {
        _playbackState.value = _playbackState.value.copy(isControlsLocked = locked)
    }

    fun selectTrack(type: TrackType, trackIndex: Int) {
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

    fun destroy() {
        progressJob?.cancel()
        stopStreamServer()
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
