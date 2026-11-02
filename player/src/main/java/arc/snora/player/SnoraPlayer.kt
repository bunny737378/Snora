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
import android.view.Surface
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

    private var currentSurface: Surface? = null
    private var progressJob: Job? = null
    private var currentPathOrUri: String = ""
    private var isMpvInitialized: Boolean = false
    private var isMpvPlaying: Boolean = false
    private var voInUse: String = "gpu"
    @Volatile private var isBuffering: Boolean = false
    private var timelinePreparedForMs: Long = 0L

    private val systemAudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as? AndroidAudioManager
    private val maxDeviceVolume =
        systemAudioManager?.getStreamMaxVolume(AndroidAudioManager.STREAM_MUSIC) ?: 15

    private var hasAudioFocus = false
    private var audioFocusRequest: AudioFocusRequest? = null
    private var mediaSession: MediaSession? = null

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
        ensureMpvCreated()
        try {
            MPVLib.addObserver(this)
        } catch (t: Throwable) {
        }
        setupMediaSession()
        val currentSysVol = systemAudioManager?.getStreamVolume(AndroidAudioManager.STREAM_MUSIC) ?: 10
        val initialLevel = ((currentSysVol.toFloat() / maxDeviceVolume.toFloat().coerceAtLeast(1f)) * 15f).toInt().coerceIn(1, 15)
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
                isActive = true
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
            val playbackStateBuilder = SessionPlaybackState.Builder()
                .setActions(
                    SessionPlaybackState.ACTION_PLAY or
                    SessionPlaybackState.ACTION_PAUSE or
                    SessionPlaybackState.ACTION_PLAY_PAUSE or
                    SessionPlaybackState.ACTION_STOP or
                    SessionPlaybackState.ACTION_SEEK_TO
                )
                .setState(
                    if (isPlaying) SessionPlaybackState.STATE_PLAYING else SessionPlaybackState.STATE_PAUSED,
                    state.positionMs,
                    1.0f
                )
            session.setPlaybackState(playbackStateBuilder.build())

            val titleText = currentPathOrUri.substringAfterLast('/').ifEmpty { "Video" }
            val metaBuilder = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, titleText)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, state.durationMs)
            session.setMetadata(metaBuilder.build())
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
        currentSurface = surface
        try {
            ensureMpvCreated()
            MPVLib.attachSurface(surface)
            MPVLib.setOptionString("force-window", "yes")
            MPVLib.setPropertyString("vo", voInUse)

            if (currentPathOrUri.isNotEmpty() && !isMpvPlaying) {
                playMedia(currentPathOrUri, 0L)
            }
        } catch (t: Throwable) {
        }
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        try {
            MPVLib.setPropertyString("android-surface-size", "${width}x$height")
        } catch (t: Throwable) {
        }
    }

    fun detachSurface() {
        currentSurface = null
        try {
            MPVLib.setPropertyString("vo", "null")
            MPVLib.setPropertyString("force-window", "no")
            MPVLib.detachSurface()
        } catch (t: Throwable) {
        }
    }

    fun playMedia(pathOrUri: String, startPositionMs: Long = 0L) {
        currentPathOrUri = pathOrUri
        thumbnailLoader.setDataSource(pathOrUri)
        ensureMpvCreated()
        requestAudioFocus()

        isMpvPlaying = true
        currentSurface?.let {
            try {
                MPVLib.attachSurface(it)
                MPVLib.setOptionString("force-window", "yes")
                MPVLib.setPropertyString("vo", voInUse)
            } catch (t: Throwable) {
            }
        }

        isBuffering = true
        timelinePreparedForMs = 0L
        _playbackState.value = _playbackState.value.copy(
            status = PlaybackStatus.BUFFERING,
            positionMs = 0L,
            errorMessage = null
        )
        updateMediaSessionState()

        try {
            MPVLib.command(arrayOf("loadfile", pathOrUri))
            MPVLib.setPropertyBoolean("pause", false)
        } catch (t: Throwable) {
            isBuffering = false
            _playbackState.value = _playbackState.value.copy(
                status = PlaybackStatus.ERROR,
                errorMessage = t.localizedMessage ?: "Failed to open video"
            )
            updateMediaSessionState()
        }

        startMpvProgressTracker()

        playerScope.launch {
            delay(400)
            refreshTracks()
            delay(800)
            refreshTracks()
            delay(1500)
            refreshTracks()
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
        } catch (t: Throwable) {
        }
    }

    private fun startMpvProgressTracker() {
        progressJob?.cancel()
        progressJob = playerScope.launch {
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
        val clamped = positionMs.coerceIn(0L, _playbackState.value.durationMs.coerceAtLeast(1L))
        try {
            val sec = (clamped / 1000.0).toString()
            MPVLib.command(arrayOf("seek", sec, "absolute"))
            _playbackState.value = _playbackState.value.copy(positionMs = clamped)
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
        } catch (t: Throwable) {
        }
    }

    fun setAspectRatioMode(mode: AspectRatioMode) {
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
            if (_playbackState.value.status != PlaybackStatus.PLAYING) {
                MPVLib.command(arrayOf("seek", "0", "relative+exact"))
            }
        } catch (t: Throwable) {
        }
        _playbackState.value = _playbackState.value.copy(aspectRatioMode = mode)
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

    fun setVolume30(level: Int) {
        val clamped = level.coerceIn(0, 30)
        _playbackState.value = _playbackState.value.copy(volumeLevel = clamped)

        if (clamped <= 15) {
            val deviceTarget = ((clamped.toFloat() / 15f) * maxDeviceVolume.toFloat()).toInt().coerceIn(0, maxDeviceVolume)
            try {
                systemAudioManager?.setStreamVolume(AndroidAudioManager.STREAM_MUSIC, deviceTarget, 0)
                MPVLib.setPropertyDouble("volume", 100.0)
            } catch (t: Throwable) {
            }
        } else {
            val boostSteps = clamped - 15
            val boostPercent = 100.0 + (boostSteps.toDouble() / 15.0) * 100.0
            try {
                systemAudioManager?.setStreamVolume(AndroidAudioManager.STREAM_MUSIC, maxDeviceVolume, 0)
                MPVLib.setPropertyDouble("volume", boostPercent)
            } catch (t: Throwable) {
            }
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
        progressJob?.cancel()
        isMpvPlaying = false
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
        _playbackState.value = PlaybackState(status = PlaybackStatus.IDLE)
    }
}
