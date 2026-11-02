package arc.snora.player

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager as AndroidAudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState as SessionPlaybackState
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class VolumeUiState(
    val level: Int,
    val percent: Int,
    val isBoost: Boolean
)

class SnoraPlayer(private val context: Context) : MPVLib.EventObserver {

    private val playerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val mpvCommandDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private var currentSurface: Surface? = null
    @Volatile private var isSurfaceAttached: Boolean = false
    private var progressJob: Job? = null
    private var pausedRedrawJob: Job? = null
    private var currentPathOrUri: String = ""
    private var pendingStartPositionMs: Long = 0L
    @Volatile private var needsFileLoad: Boolean = false
    private var isMpvInitialized: Boolean = false
    @Volatile private var isMpvPlaying: Boolean = false
    @Volatile private var playbackRequestId: Long = 0L
    private var voInUse: String = "gpu"
    private var preferredHwDecMode: HwDecMode = HwDecMode.AUTO
    @Volatile private var isBuffering: Boolean = false
    private var timelinePreparedForMs: Long = 0L

    private val systemAudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as? AndroidAudioManager
    private val maxDeviceVolume =
        systemAudioManager?.getStreamMaxVolume(AndroidAudioManager.STREAM_MUSIC) ?: 15

    private var hasAudioFocus = false
    private var audioFocusRequest: AudioFocusRequest? = null
    private var mediaSession: MediaSession? = null
    private val playbackNotification = PlayerNotificationManager(context)
    private var currentTitle: String = "Video"
    private var currentPoster: Bitmap? = null
    private var posterJob: Job? = null
    private var lastSessionProgressUpdateMs: Long = 0L
    private var lastMetadataTitle: String? = null
    private var lastMetadataPoster: Bitmap? = null
    private var lastMetadataDurationMs: Long = -1L

    private val audioFocusChangeListener = AndroidAudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AndroidAudioManager.AUDIOFOCUS_LOSS,
            AndroidAudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AndroidAudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                pause()
            }
            AndroidAudioManager.AUDIOFOCUS_GAIN -> {
                // Audio focus restored
            }
        }
    }

    val subtitleManager = SubtitleManager(this)
    val audioManager = AudioManager(this)
    val videoRenderer = VideoRenderer(this)
    val thumbnailLoader = ThumbnailLoader(context)

    suspend fun loadThumbnailFrame(timeMs: Long): Bitmap? {
        return thumbnailLoader.loadFrame(timeMs)
    }

    init {
        PlaybackActionReceiver.attach(this)
        ensureMpvCreated()
        try {
            MPVLib.addObserver(this)
        } catch (t: Throwable) {
        }
        setupMediaSession()
        val currentSysVol = systemAudioManager?.getStreamVolume(AndroidAudioManager.STREAM_MUSIC) ?: 10
        val initialLevel = ((currentSysVol.toFloat() / maxDeviceVolume.toFloat().coerceAtLeast(1f)) * 15f)
            .roundToInt()
            .coerceIn(0, 15)
        _playbackState.value = _playbackState.value.copy(
            volumeLevel = initialLevel,
            videoInfo = VideoInfo(hwdecActive = "HW")
        )
    }

    fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        val am = systemAudioManager ?: return false
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
            val req = AudioFocusRequest.Builder(AndroidAudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            audioFocusRequest = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                audioFocusChangeListener,
                AndroidAudioManager.STREAM_MUSIC,
                AndroidAudioManager.AUDIOFOCUS_GAIN
            )
        }
        hasAudioFocus = (result == AndroidAudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        return hasAudioFocus
    }

    fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        val am = systemAudioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(audioFocusChangeListener)
        }
        hasAudioFocus = false
    }

    private fun setupMediaSession() {
        try {
            mediaSession = MediaSession(context, "SnoraPlayerSession").apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() { resume() }
                    override fun onPause() { pause() }
                    override fun onStop() { pause() }
                    override fun onSeekTo(pos: Long) { seekTo(pos) }
                })
                isActive = false
            }
            updateMediaSessionState()
        } catch (t: Throwable) {
        }
    }

    private fun updateMediaSessionState() {
        try {
            val session = mediaSession ?: return
            val state = _playbackState.value
            val isPlaying = state.status == PlaybackStatus.PLAYING
            val sessionState = when (state.status) {
                PlaybackStatus.PLAYING -> SessionPlaybackState.STATE_PLAYING
                PlaybackStatus.BUFFERING -> SessionPlaybackState.STATE_BUFFERING
                PlaybackStatus.ENDED -> SessionPlaybackState.STATE_STOPPED
                PlaybackStatus.ERROR -> SessionPlaybackState.STATE_ERROR
                else -> SessionPlaybackState.STATE_PAUSED
            }
            val playbackStateBuilder = SessionPlaybackState.Builder()
                .setActions(
                    SessionPlaybackState.ACTION_PLAY or
                    SessionPlaybackState.ACTION_PAUSE or
                    SessionPlaybackState.ACTION_PLAY_PAUSE or
                    SessionPlaybackState.ACTION_STOP or
                    SessionPlaybackState.ACTION_SEEK_TO
                )
                .setState(
                    sessionState,
                    state.positionMs,
                    if (isPlaying) state.speed else 0f
                )
            session.setPlaybackState(playbackStateBuilder.build())

            if (lastMetadataTitle != currentTitle ||
                lastMetadataPoster !== currentPoster ||
                lastMetadataDurationMs != state.durationMs
            ) {
                val metaBuilder = MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, currentTitle)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, state.durationMs)
                currentPoster?.let {
                    metaBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it)
                }
                session.setMetadata(metaBuilder.build())
                lastMetadataTitle = currentTitle
                lastMetadataPoster = currentPoster
                lastMetadataDurationMs = state.durationMs
            }

            if (isMpvPlaying) {
                playbackNotification.show(
                    state = state,
                    title = currentTitle,
                    poster = currentPoster,
                    mediaSession = session
                )
            } else {
                playbackNotification.cancel()
            }
        } catch (t: Throwable) {
        }
    }

    private fun ensureMpvCreated() {
        if (!isMpvInitialized) {
            try {
                MPVLib.create(context.applicationContext)
                MPVLib.setOptionString("profile", "fast")
                MPVLib.setOptionString("vo", "gpu")
                MPVLib.setOptionString("hwdec", "auto")
                MPVLib.setOptionString("hwdec-codecs", "all")
                MPVLib.setOptionString("video-sync", "audio")
                MPVLib.setOptionString("ao", "audiotrack,opensles")
                MPVLib.setOptionString("volume-max", "200")
                MPVLib.setOptionString("keep-open", "yes")
                MPVLib.setOptionString("idle", "yes")
                MPVLib.init()

                MPVLib.setOptionString("force-window", "no")

                MPVLib.observeProperty("track-list", MPVLib.MpvFormat.MPV_FORMAT_NONE)
                MPVLib.observeProperty("hwdec-current", MPVLib.MpvFormat.MPV_FORMAT_STRING)
                MPVLib.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
                MPVLib.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
                MPVLib.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
                MPVLib.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
                MPVLib.observeProperty("sub-text", MPVLib.MpvFormat.MPV_FORMAT_STRING)
                MPVLib.observeProperty("aid", MPVLib.MpvFormat.MPV_FORMAT_INT64)
                MPVLib.observeProperty("sid", MPVLib.MpvFormat.MPV_FORMAT_INT64)

                isMpvInitialized = true
            } catch (t: Throwable) {
                isMpvInitialized = false
            }
        }
    }

    fun attachSurface(surface: Surface) {
        if (isSurfaceAttached && currentSurface === surface) {
            loadPendingMediaIfReady()
            return
        }

        currentSurface = surface
        try {
            ensureMpvCreated()

            // mpv must receive the new Android window before its video output is
            // re-enabled. Doing this in the opposite order can leave mpv rendering
            // to the previous, already destroyed surface (audio keeps playing).
            MPVLib.attachSurface(surface)
            isSurfaceAttached = true
            MPVLib.setOptionString("force-window", "yes")
            MPVLib.setPropertyString("vo", voInUse)
            MPVLib.setPropertyString("vid", "auto")

            loadPendingMediaIfReady()
        } catch (t: Throwable) {
        }
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        try {
            MPVLib.setPropertyString("android-surface-size", "${width}x$height")
        } catch (t: Throwable) {
        }
        if (_playbackState.value.status == PlaybackStatus.PAUSED) {
            schedulePausedFrameRedraw(delayMs = 80L)
        }
    }

    fun detachSurface() {
        currentSurface = null
        if (!isSurfaceAttached) return
        isSurfaceAttached = false

        try {
            // Tear down the video output before releasing the Surface global ref.
            // SurfaceView and DisposableEffect can both report the same teardown,
            // so the guard above deliberately makes this operation idempotent.
            MPVLib.setPropertyString("vo", "null")
            MPVLib.setPropertyString("force-window", "no")
        } catch (t: Throwable) {
        } finally {
            try {
                MPVLib.detachSurface()
            } catch (t: Throwable) {
            }
        }
    }

    fun playMedia(
        pathOrUri: String,
        startPositionMs: Long = 0L,
        subtitleAppearance: SubtitleAppearance? = null,
        title: String? = null
    ) {
        playbackRequestId += 1L
        val requestId = playbackRequestId
        currentPathOrUri = pathOrUri
        currentTitle = title?.takeIf { it.isNotBlank() }
            ?: pathOrUri.substringAfterLast('/').ifBlank { "Video" }
        currentPoster = null
        posterJob?.cancel()
        lastSessionProgressUpdateMs = 0L
        pendingStartPositionMs = startPositionMs.coerceAtLeast(0L)
        needsFileLoad = true
        thumbnailLoader.setDataSource(pathOrUri)
        ensureMpvCreated()
        requestAudioFocus()
        mediaSession?.isActive = true

        isMpvPlaying = true
        isBuffering = true
        timelinePreparedForMs = 0L
        pausedRedrawJob?.cancel()

        // A new item gets a new playback session. mpv otherwise retains speed,
        // panscan/aspect, delays and track selection across loadfile calls.
        // Device volume and the explicit decoder preference are not per-video
        // playback state, so those two are intentionally retained.
        val decoderMode = preferredHwDecMode
        _playbackState.value = PlaybackState(
            status = PlaybackStatus.BUFFERING,
            volumeLevel = _playbackState.value.volumeLevel.coerceIn(0, 15),
            subtitleAppearance = subtitleAppearance,
            hwDecMode = decoderMode,
            hwDecCurrent = if (decoderMode == HwDecMode.AUTO) "auto" else "no",
            videoInfo = VideoInfo(
                hwdecActive = if (decoderMode == HwDecMode.AUTO) "HW" else "SW"
            )
        )
        updateMediaSessionState()
        posterJob = playerScope.launch {
            val poster = thumbnailLoader.loadFrame(1_000L)
            if (poster != null && requestId == playbackRequestId) {
                currentPoster = poster
                updateMediaSessionState()
            }
        }

        // A tap normally happens before Compose has created the SurfaceView.
        // Keep the request pending instead of starting an audio-only mpv file with
        // no window. surfaceCreated() will call this once the window is valid.
        loadPendingMediaIfReady()
    }

    /** Must run on mpvCommandDispatcher immediately before a new loadfile. */
    private fun applyFreshPlaybackDefaults() {
        MPVLib.setPropertyDouble("speed", 1.0)
        MPVLib.setPropertyDouble("audio-delay", 0.0)
        MPVLib.setPropertyDouble("sub-delay", 0.0)
        MPVLib.setPropertyString("aid", "auto")
        MPVLib.setPropertyString("sid", "auto")
        MPVLib.setPropertyDouble("volume", 100.0)
        MPVLib.setPropertyString("keepaspect", "yes")
        MPVLib.setPropertyDouble("panscan", 0.0)
        MPVLib.setPropertyString("video-aspect-override", "-1")
        MPVLib.setPropertyString(
            "hwdec",
            if (preferredHwDecMode == HwDecMode.AUTO) "auto" else "no"
        )
    }

    private fun loadPendingMediaIfReady() {
        if (!needsFileLoad || !isMpvPlaying || !isSurfaceAttached) return
        if (currentSurface?.isValid != true || currentPathOrUri.isEmpty()) return

        val requestId = playbackRequestId
        val source = currentPathOrUri
        val startPositionMs = pendingStartPositionMs
        needsFileLoad = false

        // loadfile can wait for demuxer/codec setup. Serialize it with stop and
        // renderer commands, but never make the tap or Surface callback wait for it.
        playerScope.launch(mpvCommandDispatcher) {
            if (requestId != playbackRequestId || !isMpvPlaying || !isSurfaceAttached) {
                return@launch
            }

            try {
                applyFreshPlaybackDefaults()
                MPVLib.command(arrayOf("loadfile", source))
                if (requestId != playbackRequestId || !isMpvPlaying) return@launch

                if (startPositionMs > 0L) {
                    MPVLib.command(
                        arrayOf("seek", (startPositionMs / 1000.0).toString(), "absolute")
                    )
                }
                MPVLib.setPropertyBoolean("pause", false)
            } catch (t: Throwable) {
                if (requestId == playbackRequestId) {
                    isBuffering = false
                    _playbackState.value = _playbackState.value.copy(
                        status = PlaybackStatus.ERROR,
                        errorMessage = t.localizedMessage ?: "Failed to open video"
                    )
                    updateMediaSessionState()
                }
                return@launch
            }

            startMpvProgressTracker()

            // Track enumeration performs many synchronous JNI property reads.
            playerScope.launch(Dispatchers.IO) {
                delay(400)
                if (requestId != playbackRequestId) return@launch
                refreshTracks()
                delay(800)
                if (requestId != playbackRequestId) return@launch
                refreshTracks()
                delay(1500)
                if (requestId != playbackRequestId) return@launch
                refreshTracks()
            }
        }
    }

    /** Stops the old item without blocking Back or the next video tap. */
    fun stopPlayback() {
        playbackRequestId += 1L
        val requestId = playbackRequestId
        isMpvPlaying = false
        needsFileLoad = false
        isBuffering = false
        progressJob?.cancel()
        pausedRedrawJob?.cancel()
        posterJob?.cancel()
        abandonAudioFocus()
        _playbackState.value = _playbackState.value.copy(status = PlaybackStatus.PAUSED)
        updateMediaSessionState()
        mediaSession?.isActive = false

        playerScope.launch(mpvCommandDispatcher) {
            if (requestId != playbackRequestId) return@launch
            try {
                MPVLib.command(arrayOf("stop"))
            } catch (t: Throwable) {
            }
        }
    }

    fun refreshTracks() {
        try {
            val count = MPVLib.getPropertyInt("track-list/count") ?: 0
            val trackList = mutableListOf<TrackItem>()
            val currentAid = MPVLib.getPropertyInt("aid") ?: -1
            val currentSid = MPVLib.getPropertyInt("sid") ?: -1

            for (i in 0 until count) {
                val typeStr = MPVLib.getPropertyString("track-list/$i/type") ?: continue
                val trackType = when (typeStr.lowercase()) {
                    "audio" -> TrackType.AUDIO
                    "sub" -> TrackType.SUBTITLE
                    "video" -> TrackType.VIDEO
                    else -> continue
                }
                val trackId = MPVLib.getPropertyInt("track-list/$i/id") ?: (i + 1)
                val title = MPVLib.getPropertyString("track-list/$i/title") ?: ""
                val lang = MPVLib.getPropertyString("track-list/$i/lang") ?: "und"
                val codec = MPVLib.getPropertyString("track-list/$i/codec") ?: ""
                val isSelected = MPVLib.getPropertyBoolean("track-list/$i/selected") ?: (
                    (trackType == TrackType.AUDIO && trackId == currentAid) ||
                    (trackType == TrackType.SUBTITLE && trackId == currentSid)
                )

                trackList.add(
                    TrackItem(
                        id = trackId,
                        type = trackType,
                        title = title.trim(),
                        lang = lang,
                        codec = codec.uppercase(),
                        isSelected = isSelected
                    )
                )
            }

            val languageCounts = trackList
                .filter { it.type == TrackType.AUDIO || it.type == TrackType.SUBTITLE }
                .groupingBy { it.type to LanguageNames.groupingKey(it.lang) }
                .eachCount()

            val languageSeenIndices = mutableMapOf<Pair<TrackType, String>, Int>()
            val namedTracks = trackList.map { track ->
                if (track.type != TrackType.AUDIO && track.type != TrackType.SUBTITLE) return@map track
                val label = if (track.type == TrackType.AUDIO) "Audio" else "Subtitle"
                val groupKey = track.type to LanguageNames.groupingKey(track.lang)
                val totalForLang = languageCounts[groupKey] ?: 1
                val indexForLang = (languageSeenIndices[groupKey] ?: 0) + 1
                languageSeenIndices[groupKey] = indexForLang

                track.copy(
                    title = LanguageNames.displayTrackTitle(
                        title = track.title,
                        lang = track.lang,
                        trackIndexForLanguage = indexForLang,
                        totalForLanguage = totalForLang,
                        fallback = "$label Track ${track.id}"
                    )
                )
            }

            val chaptersCount = MPVLib.getPropertyInt("chapter-list/count") ?: 0
            val chapterList = mutableListOf<ChapterItem>()
            for (c in 0 until chaptersCount) {
                val cTitle = MPVLib.getPropertyString("chapter-list/$c/title") ?: "Chapter ${c + 1}"
                val cStart = ((MPVLib.getPropertyDouble("chapter-list/$c/time") ?: 0.0) * 1000).toLong()
                val cEnd = if (c + 1 < chaptersCount) {
                    ((MPVLib.getPropertyDouble("chapter-list/${c + 1}/time") ?: 0.0) * 1000).toLong()
                } else {
                    _playbackState.value.durationMs
                }
                chapterList.add(ChapterItem(c, cTitle, cStart, cEnd))
            }

            _playbackState.value = _playbackState.value.copy(
                tracks = namedTracks,
                chapters = chapterList,
                selectedAudioTrackId = currentAid,
                selectedSubtitleTrackId = currentSid
            )
            updateMediaSessionState()
        } catch (t: Throwable) {
        }
    }

    private fun startMpvProgressTracker() {
        progressJob?.cancel()
        progressJob = playerScope.launch(Dispatchers.IO) {
            while (isActive && isMpvPlaying) {
                try {
                    val timePosSec = MPVLib.getPropertyDouble("time-pos") ?: 0.0
                    val durationSec = MPVLib.getPropertyDouble("duration") ?: 0.0
                    val isPaused = MPVLib.getPropertyBoolean("pause") ?: false

                    val posMs = (timePosSec * 1000).toLong().coerceAtLeast(0L)
                    val durMs = (durationSec * 1000).toLong().coerceAtLeast(0L)

                    val width = MPVLib.getPropertyInt("video-out-params/dw")
                        ?: MPVLib.getPropertyInt("width") ?: 0
                    val height = MPVLib.getPropertyInt("video-out-params/dh")
                        ?: MPVLib.getPropertyInt("height") ?: 0
                    val fps = MPVLib.getPropertyDouble("container-fps") ?: 0.0
                    val hwdecCurrent = MPVLib.getPropertyString("hwdec-current") ?: "no"

                    val isHwWorking = hwdecCurrent.isNotBlank() && hwdecCurrent != "no"
                    val hwMode = if (isHwWorking) HwDecMode.AUTO else HwDecMode.SW

                    val currentInfo = _playbackState.value.videoInfo
                    val newInfo = currentInfo.copy(
                        width = if (width > 0) width else currentInfo.width,
                        height = if (height > 0) height else currentInfo.height,
                        fps = if (fps > 0) fps else currentInfo.fps,
                        hwdecActive = if (isHwWorking) "HW ($hwdecCurrent)" else "SW"
                    )

                    val effectiveDuration = if (durMs > 0) durMs else _playbackState.value.durationMs
                    if (effectiveDuration > 0L && timelinePreparedForMs != effectiveDuration) {
                        timelinePreparedForMs = effectiveDuration
                        playerScope.launch { thumbnailLoader.prepareTimeline(effectiveDuration) }
                    }
                    _playbackState.value = _playbackState.value.copy(
                        durationMs = effectiveDuration,
                        positionMs = posMs,
                        videoInfo = newInfo,
                        hwDecMode = hwMode,
                        hwDecCurrent = hwdecCurrent,
                        status = when {
                            isPaused -> PlaybackStatus.PAUSED
                            durMs > 0 && posMs >= durMs -> PlaybackStatus.ENDED
                            isBuffering -> PlaybackStatus.BUFFERING
                            else -> PlaybackStatus.PLAYING
                        }
                    )
                    val nowMs = SystemClock.uptimeMillis()
                    if (!isPaused && nowMs - lastSessionProgressUpdateMs >= 1_000L) {
                        lastSessionProgressUpdateMs = nowMs
                        updateMediaSessionState()
                    }
                } catch (t: Throwable) {
                }
                delay(200)
            }
        }
    }

    fun pause() {
        try {
            MPVLib.setPropertyBoolean("pause", true)
            _playbackState.value = _playbackState.value.copy(status = PlaybackStatus.PAUSED)
            abandonAudioFocus()
            updateMediaSessionState()
        } catch (t: Throwable) {
        }
    }

    fun resume() {
        requestAudioFocus()
        try {
            MPVLib.setPropertyBoolean("pause", false)
            _playbackState.value = _playbackState.value.copy(status = PlaybackStatus.PLAYING)
            updateMediaSessionState()
        } catch (t: Throwable) {
        }
    }

    fun togglePlayPause() {
        try {
            val isPaused = MPVLib.getPropertyBoolean("pause") ?: false
            if (isPaused) {
                resume()
            } else {
                pause()
            }
        } catch (t: Throwable) {
        }
    }

    fun seekTo(positionMs: Long) {
        val durationMs = _playbackState.value.durationMs
        // Never clamp an unknown duration to 1 ms: doing so turns a valid slide
        // release into an unexpected jump back to the beginning.
        val clamped = if (durationMs > 0L) {
            positionMs.coerceIn(0L, durationMs)
        } else {
            positionMs.coerceAtLeast(0L)
        }
        try {
            val sec = (clamped / 1000.0).toString()
            MPVLib.command(arrayOf("seek", sec, "absolute"))
            _playbackState.value = _playbackState.value.copy(positionMs = clamped)
            updateMediaSessionState()
        } catch (t: Throwable) {
        }
    }

    fun seekRelative(deltaSeconds: Double) {
        try {
            MPVLib.command(arrayOf("seek", deltaSeconds.toString(), "relative"))
        } catch (t: Throwable) {
        }
    }

    fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.25f, 4.0f)
        try {
            MPVLib.setPropertyDouble("speed", clamped.toDouble())
            _playbackState.value = _playbackState.value.copy(speed = clamped)
            updateMediaSessionState()
        } catch (t: Throwable) {
        }
    }

    fun setAspectRatioMode(mode: AspectRatioMode) {
        // Playing video resizes directly. A paused software-decoded frame may
        // need one same-position redraw after the new renderer properties.
        _playbackState.value = _playbackState.value.copy(aspectRatioMode = mode)
        val requestId = playbackRequestId
        playerScope.launch(mpvCommandDispatcher) {
            try {
                when (mode) {
                    AspectRatioMode.FIT -> {
                        MPVLib.setPropertyString("keepaspect", "yes")
                        MPVLib.setPropertyDouble("panscan", 0.0)
                        MPVLib.setPropertyString("video-aspect-override", "-1")
                    }
                    AspectRatioMode.CROP -> {
                        MPVLib.setPropertyString("keepaspect", "yes")
                        MPVLib.setPropertyDouble("panscan", 1.0)
                        MPVLib.setPropertyString("video-aspect-override", "-1")
                    }
                    AspectRatioMode.FILL -> {
                        MPVLib.setPropertyString("keepaspect", "no")
                        MPVLib.setPropertyDouble("panscan", 0.0)
                        MPVLib.setPropertyString("video-aspect-override", "-1")
                    }
                    AspectRatioMode.RATIO_16_9 -> {
                        MPVLib.setPropertyString("keepaspect", "yes")
                        MPVLib.setPropertyDouble("panscan", 0.0)
                        MPVLib.setPropertyString("video-aspect-override", "16:9")
                    }
                    AspectRatioMode.RATIO_1_1 -> {
                        MPVLib.setPropertyString("keepaspect", "yes")
                        MPVLib.setPropertyDouble("panscan", 0.0)
                        MPVLib.setPropertyString("video-aspect-override", "1:1")
                    }
                    AspectRatioMode.RATIO_4_3 -> {
                        MPVLib.setPropertyString("keepaspect", "yes")
                        MPVLib.setPropertyDouble("panscan", 0.0)
                        MPVLib.setPropertyString("video-aspect-override", "4:3")
                    }
                }
                redrawPausedFrameOnWorker(requestId)
            } catch (t: Throwable) {
            }
        }
    }

    private fun schedulePausedFrameRedraw(delayMs: Long) {
        val requestId = playbackRequestId
        pausedRedrawJob?.cancel()
        pausedRedrawJob = playerScope.launch(mpvCommandDispatcher) {
            delay(delayMs)
            redrawPausedFrameOnWorker(requestId)
        }
    }

    /** Redraws the current frame without resuming or changing its timestamp. */
    private fun redrawPausedFrameOnWorker(requestId: Long) {
        if (requestId != playbackRequestId || !isMpvPlaying || !isSurfaceAttached) return
        if (_playbackState.value.status != PlaybackStatus.PAUSED) return

        val isPaused = MPVLib.getPropertyBoolean("pause") ?: return
        if (!isPaused) return
        val positionSeconds = _playbackState.value.positionMs.coerceAtLeast(0L) / 1000.0
        MPVLib.command(
            arrayOf("seek", positionSeconds.toString(), "absolute+exact")
        )
        MPVLib.setPropertyBoolean("pause", true)
    }

    fun toggleHwDec() {
        val currentMode = _playbackState.value.hwDecMode
        if (currentMode == HwDecMode.AUTO) {
            try {
                MPVLib.setPropertyString("hwdec", "no")
                _playbackState.value = _playbackState.value.copy(
                    hwDecMode = HwDecMode.SW,
                    hwDecCurrent = "no",
                    videoInfo = _playbackState.value.videoInfo.copy(hwdecActive = "SW")
                )
            } catch (t: Throwable) {
            }
        } else {
            if (_playbackState.value.hwDecFailed) {
                return
            }
            try {
                MPVLib.setPropertyString("hwdec", "auto")
                playerScope.launch {
                    delay(350)
                    val current = MPVLib.getPropertyString("hwdec-current") ?: "no"
                    if (current == "no" || current.isEmpty()) {
                        _playbackState.value = _playbackState.value.copy(
                            hwDecFailed = true,
                            hwDecMode = HwDecMode.SW,
                            hwDecCurrent = "no",
                            videoInfo = _playbackState.value.videoInfo.copy(hwdecActive = "SW (HW Unavailable)")
                        )
                    } else {
                        _playbackState.value = _playbackState.value.copy(
                            hwDecFailed = false,
                            hwDecMode = HwDecMode.AUTO,
                            hwDecCurrent = current,
                            videoInfo = _playbackState.value.videoInfo.copy(hwdecActive = "HW ($current)")
                        )
                    }
                }
            } catch (t: Throwable) {
            }
        }
    }

    fun setHwDecMode(mode: HwDecMode) {
        preferredHwDecMode = mode
        val target = if (mode == HwDecMode.AUTO) "auto" else "no"
        try {
            MPVLib.setPropertyString("hwdec", target)
            _playbackState.value = _playbackState.value.copy(
                hwDecMode = mode,
                hwDecCurrent = if (mode == HwDecMode.AUTO) "auto" else "no",
                videoInfo = _playbackState.value.videoInfo.copy(
                    hwdecActive = if (mode == HwDecMode.AUTO) "HW" else "SW"
                )
            )
        } catch (t: Throwable) {
        }
    }

    fun setVolume30(level: Int): VolumeUiState {
        val clamped = level.coerceIn(0, 30)
        _playbackState.value = _playbackState.value.copy(volumeLevel = clamped)

        if (clamped <= 15) {
            val deviceTarget = ((clamped.toFloat() / 15f) * maxDeviceVolume.toFloat())
                .roundToInt()
                .coerceIn(0, maxDeviceVolume)
            try {
                systemAudioManager?.setStreamVolume(
                    AndroidAudioManager.STREAM_MUSIC,
                    deviceTarget,
                    0
                )
                MPVLib.setPropertyDouble("volume", 100.0)
            } catch (t: Throwable) {
            }
        } else {
            val boostSteps = clamped - 15
            val boostPercent = 100.0 + (boostSteps.toDouble() / 15.0) * 100.0
            try {
                systemAudioManager?.setStreamVolume(
                    AndroidAudioManager.STREAM_MUSIC,
                    maxDeviceVolume,
                    0
                )
                MPVLib.setPropertyDouble("volume", boostPercent)
            } catch (t: Throwable) {
            }
        }
        return VolumeUiState(
            level = clamped,
            percent = ((clamped.toFloat() / 15f) * 100f).roundToInt(),
            isBoost = clamped > 15
        )
    }

    /** Syncs external device-stream changes without cancelling an unchanged mpv boost. */
    fun syncVolumeFromDevice(): VolumeUiState {
        val audio = systemAudioManager
        val currentLevel = _playbackState.value.volumeLevel
        if (audio == null) {
            return VolumeUiState(
                level = currentLevel,
                percent = ((currentLevel.toFloat() / 15f) * 100f).roundToInt(),
                isBoost = currentLevel > 15
            )
        }

        return try {
            val deviceLevel = audio.getStreamVolume(AndroidAudioManager.STREAM_MUSIC)
            if (currentLevel > 15 && deviceLevel >= maxDeviceVolume) {
                return VolumeUiState(
                    level = currentLevel,
                    percent = ((currentLevel.toFloat() / 15f) * 100f).roundToInt(),
                    isBoost = true
                )
            }

            val level = (
                (deviceLevel.toFloat() / maxDeviceVolume.toFloat().coerceAtLeast(1f)) * 15f
            ).roundToInt().coerceIn(0, 15)
            val percent = (
                (deviceLevel.toFloat() / maxDeviceVolume.toFloat().coerceAtLeast(1f)) * 100f
            ).roundToInt().coerceIn(0, 100)
            if (level != currentLevel || currentLevel > 15) {
                MPVLib.setPropertyDouble("volume", 100.0)
                _playbackState.value = _playbackState.value.copy(volumeLevel = level)
            }
            VolumeUiState(level = level, percent = percent, isBoost = false)
        } catch (t: Throwable) {
            VolumeUiState(
                level = currentLevel,
                percent = ((currentLevel.toFloat() / 15f) * 100f).roundToInt(),
                isBoost = currentLevel > 15
            )
        }
    }

    /** Changes the real media stream by one hardware-key step, without a system popup. */
    fun adjustDeviceVolume(direction: Int): VolumeUiState {
        val audio = systemAudioManager
            ?: return setVolume30(
                (_playbackState.value.volumeLevel + direction).coerceIn(0, 15)
            )

        return try {
            // Hardware buttons always control the device stream, not mpv boost.
            MPVLib.setPropertyDouble("volume", 100.0)
            audio.adjustStreamVolume(
                AndroidAudioManager.STREAM_MUSIC,
                if (direction >= 0) AndroidAudioManager.ADJUST_RAISE else AndroidAudioManager.ADJUST_LOWER,
                0
            )
            val deviceLevel = audio.getStreamVolume(AndroidAudioManager.STREAM_MUSIC)
            val level = ((deviceLevel.toFloat() / maxDeviceVolume.toFloat().coerceAtLeast(1f)) * 15f)
                .roundToInt()
                .coerceIn(0, 15)
            val percent = ((deviceLevel.toFloat() / maxDeviceVolume.toFloat().coerceAtLeast(1f)) * 100f)
                .roundToInt()
                .coerceIn(0, 100)
            _playbackState.value = _playbackState.value.copy(volumeLevel = level)
            VolumeUiState(level = level, percent = percent, isBoost = false)
        } catch (t: Throwable) {
            val current = _playbackState.value.volumeLevel.coerceIn(0, 15)
            VolumeUiState(
                level = current,
                percent = ((current.toFloat() / 15f) * 100f).roundToInt(),
                isBoost = false
            )
        }
    }

    fun setControlsLocked(locked: Boolean) {
        _playbackState.value = _playbackState.value.copy(isControlsLocked = locked)
    }

    fun selectTrack(type: TrackType, trackId: Int) {
        try {
            val prop = when (type) {
                TrackType.AUDIO -> "aid"
                TrackType.SUBTITLE -> "sid"
                TrackType.VIDEO -> "vid"
            }
            val valStr = if (trackId < 0) "no" else trackId.toString()
            MPVLib.setPropertyString(prop, valStr)

            val updatedTracks = _playbackState.value.tracks.map { track ->
                if (track.type == type) {
                    track.copy(isSelected = (track.id == trackId))
                } else {
                    track
                }
            }
            _playbackState.value = _playbackState.value.copy(
                tracks = updatedTracks,
                selectedAudioTrackId = if (type == TrackType.AUDIO) trackId else _playbackState.value.selectedAudioTrackId,
                selectedSubtitleTrackId = if (type == TrackType.SUBTITLE) trackId else _playbackState.value.selectedSubtitleTrackId,
                currentSubtitleText = if (type == TrackType.SUBTITLE && trackId < 0) "" else _playbackState.value.currentSubtitleText
            )
        } catch (t: Throwable) {
        }
    }

    fun loadExternalSubtitle(uriString: String) {
        try {
            MPVLib.command(arrayOf("sub-add", uriString, "select"))
            playerScope.launch {
                delay(300)
                refreshTracks()
            }
        } catch (t: Throwable) {
        }
    }

    fun loadExternalAudio(uriString: String) {
        try {
            MPVLib.command(arrayOf("audio-add", uriString, "select"))
            playerScope.launch {
                delay(300)
                refreshTracks()
            }
        } catch (t: Throwable) {
        }
    }

    fun setSubtitleDelay(seconds: Double) {
        try {
            MPVLib.setPropertyDouble("sub-delay", seconds)
            _playbackState.value = _playbackState.value.copy(subDelaySec = seconds)
        } catch (t: Throwable) {
        }
    }

    fun setAudioDelay(seconds: Double) {
        try {
            MPVLib.setPropertyDouble("audio-delay", seconds)
            _playbackState.value = _playbackState.value.copy(audioDelaySec = seconds)
        } catch (t: Throwable) {
        }
    }

    fun updateState(block: (PlaybackState) -> PlaybackState) {
        _playbackState.value = block(_playbackState.value)
    }

    override fun eventProperty(property: String) {
        if (property == "track-list") {
            refreshTracks()
        }
    }

    override fun eventProperty(property: String, value: Long) {
        if (property == "aid") {
            _playbackState.value = _playbackState.value.copy(selectedAudioTrackId = value.toInt())
        } else if (property == "sid") {
            _playbackState.value = _playbackState.value.copy(selectedSubtitleTrackId = value.toInt())
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        if (property == "pause") {
            _playbackState.value = _playbackState.value.copy(
                status = if (value) PlaybackStatus.PAUSED else if (isBuffering) PlaybackStatus.BUFFERING else PlaybackStatus.PLAYING
            )
        } else if (property == "paused-for-cache") {
            isBuffering = value
            val current = _playbackState.value
            _playbackState.value = current.copy(
                status = when {
                    current.status == PlaybackStatus.PAUSED -> PlaybackStatus.PAUSED
                    value -> PlaybackStatus.BUFFERING
                    else -> PlaybackStatus.PLAYING
                }
            )
        }
    }

    override fun eventProperty(property: String, value: String) {
        if (property == "sub-text") {
            val text = if (_playbackState.value.selectedSubtitleTrackId == -1) "" else value
            _playbackState.value = _playbackState.value.copy(currentSubtitleText = text)
        } else if (property == "hwdec-current") {
            val isHw = value.isNotBlank() && value != "no"
            _playbackState.value = _playbackState.value.copy(
                hwDecCurrent = value,
                hwDecMode = if (isHw) HwDecMode.AUTO else HwDecMode.SW
            )
        }
    }

    override fun eventProperty(property: String, value: Double) {}

    override fun event(eventId: Int) {
        if (eventId == MPVLib.MpvEvent.MPV_EVENT_START_FILE) {
            isBuffering = true
            _playbackState.value = _playbackState.value.copy(status = PlaybackStatus.BUFFERING)
        } else if (eventId == MPVLib.MpvEvent.MPV_EVENT_END_FILE) {
            isBuffering = false
            _playbackState.value = _playbackState.value.copy(status = PlaybackStatus.ENDED)
        } else if (eventId == MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED ||
            eventId == MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART
        ) {
            isBuffering = false
            if (_playbackState.value.status == PlaybackStatus.BUFFERING) {
                _playbackState.value = _playbackState.value.copy(status = PlaybackStatus.PLAYING)
            }
            refreshTracks()
        }
    }

    fun destroy() {
        playbackRequestId += 1L
        progressJob?.cancel()
        pausedRedrawJob?.cancel()
        posterJob?.cancel()
        playbackNotification.cancel()
        PlaybackActionReceiver.detach(this)
        isMpvPlaying = false
        needsFileLoad = false
        isBuffering = false
        abandonAudioFocus()
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
            mediaSession = null
        } catch (t: Throwable) {
        }
        try {
            thumbnailLoader.release()
            MPVLib.removeObserver(this)
            if (isMpvInitialized) {
                MPVLib.command(arrayOf("stop"))
                MPVLib.detachSurface()
                MPVLib.destroy()
                isMpvInitialized = false
            }
        } catch (t: Throwable) {
        }
        currentSurface = null
        isSurfaceAttached = false
        _playbackState.value = PlaybackState(status = PlaybackStatus.IDLE)
    }
}
