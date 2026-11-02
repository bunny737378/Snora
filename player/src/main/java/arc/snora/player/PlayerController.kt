package arc.snora.player

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.database.ContentObserver
import android.media.AudioManager as AndroidAudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class GestureOverlayState(
    val isVisible: Boolean = false,
    val iconType: GestureIconType = GestureIconType.NONE,
    val progress: Float = 0f,
    val volumeLevel: Float = 15f,
    val isBoost: Boolean = false
)

data class DoubleTapSeekState(
    val isVisible: Boolean = false,
    val isForward: Boolean = true,
    val seconds: Int = 10
)

enum class GestureIconType {
    NONE,
    VOLUME,
    BRIGHTNESS
}

class PlayerController(
    val context: Context,
    val player: SnoraPlayer
) {
    private val scope = CoroutineScope(Dispatchers.Main.immediate)

    private val _isHudVisible = MutableStateFlow(false)
    val isHudVisible: StateFlow<Boolean> = _isHudVisible.asStateFlow()

    private val _gestureState = MutableStateFlow(GestureOverlayState())
    val gestureState: StateFlow<GestureOverlayState> = _gestureState.asStateFlow()

    private val _doubleTapSeekState = MutableStateFlow(DoubleTapSeekState())
    val doubleTapSeekState: StateFlow<DoubleTapSeekState> = _doubleTapSeekState.asStateFlow()

    private var autoHideJob: Job? = null
    private var gestureHideJob: Job? = null
    private var lastGestureInteractionMs: Long = 0L
    private var doubleTapJob: Job? = null
    private var timelinePreviewJob: Job? = null
    private var isScrubbing: Boolean = false

    private var accumulatedVolumeDelta: Float = 0f
    private var brightnessBeforePlayer: Float? = null
    private var currentBrightness: Float? = null
    private var playerActivity: Activity? = null
    private var deviceObserversRegistered = false
    private var hasRequestedBrightnessPermission = false
    @Volatile private var pendingSystemBrightness: Float? = null
    private var systemBrightnessWriteJob: Job? = null

    private val brightnessObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            syncBrightnessFromDevice(showOverlay = true)
        }
    }

    private val volumeChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != VOLUME_CHANGED_ACTION) return
            val streamType = intent.getIntExtra(
                EXTRA_VOLUME_STREAM_TYPE,
                AndroidAudioManager.STREAM_MUSIC
            )
            if (streamType == AndroidAudioManager.STREAM_MUSIC) {
                syncVolumeFromDevice(showOverlay = true)
            }
        }
    }

    init {
        scheduleAutoHide()
    }

    fun onPlayerViewCreated(activity: Activity?) {
        playerActivity = activity
        if (activity != null && brightnessBeforePlayer == null) {
            brightnessBeforePlayer = activity.window.attributes.screenBrightness
        }
        accumulatedVolumeDelta = 0f
        isScrubbing = false
        _gestureState.value = GestureOverlayState()
        _doubleTapSeekState.value = DoubleTapSeekState()
        registerDeviceObservers()
        syncWithDevice()
        showHud()
    }

    fun onPlayerViewDisposed(activity: Activity?) {
        autoHideJob?.cancel()
        gestureHideJob?.cancel()
        doubleTapJob?.cancel()
        timelinePreviewJob?.cancel()
        unregisterDeviceObservers()
        stopSystemBrightnessWriter(activity)
        _gestureState.value = GestureOverlayState()
        _doubleTapSeekState.value = DoubleTapSeekState()

        if (activity != null) {
            val attributes = activity.window.attributes
            attributes.screenBrightness = if (Settings.System.canWrite(activity)) {
                // The system setting now owns brightness, including after close.
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            } else {
                // Permission-free fallback retains the original local-window behavior.
                brightnessBeforePlayer ?: attributes.screenBrightness
            }
            activity.window.attributes = attributes
        }
        brightnessBeforePlayer = null
        currentBrightness = null
        accumulatedVolumeDelta = 0f
        playerActivity = null
    }

    /** Re-reads both device values on launch and when returning to the player. */
    fun syncWithDevice() {
        syncVolumeFromDevice(showOverlay = false)
        syncBrightnessFromDevice(showOverlay = false)
        startSystemBrightnessWriter(playerActivity)
    }

    private fun registerDeviceObservers() {
        if (deviceObserversRegistered) return
        deviceObserversRegistered = true
        try {
            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
                false,
                brightnessObserver
            )
        } catch (t: Throwable) {
        }
        try {
            val filter = IntentFilter(VOLUME_CHANGED_ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    volumeChangedReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(volumeChangedReceiver, filter)
            }
        } catch (t: Throwable) {
        }
    }

    private fun unregisterDeviceObservers() {
        if (!deviceObserversRegistered) return
        deviceObserversRegistered = false
        try {
            context.contentResolver.unregisterContentObserver(brightnessObserver)
        } catch (t: Throwable) {
        }
        try {
            context.unregisterReceiver(volumeChangedReceiver)
        } catch (t: Throwable) {
        }
    }

    private fun syncVolumeFromDevice(showOverlay: Boolean) {
        val previousLevel = player.playbackState.value.volumeLevel
        val volume = player.syncVolumeFromDevice()
        val changed = volume.level != previousLevel ||
            (previousLevel > 15 && !volume.isBoost)
        if (!changed) return

        accumulatedVolumeDelta = 0f
        if (showOverlay) {
            dismissBrightnessImmediately()
            hideHud()
            showVolumeOverlay(volume)
        }
    }

    private fun syncBrightnessFromDevice(showOverlay: Boolean) {
        val activity = playerActivity ?: return
        val previous = currentBrightness
        val systemBrightness = readSystemBrightness(activity)
        val changed = previous == null || abs(previous - systemBrightness) > 0.002f
        currentBrightness = systemBrightness
        applyWindowBrightness(activity, systemBrightness)
        if (showOverlay && previous != null && changed) {
            dismissVolumeImmediately()
            hideHud()
            showBrightnessOverlay(systemBrightness)
        }
    }

    private fun applyWindowBrightness(activity: Activity, brightness: Float) {
        val attributes = activity.window.attributes
        attributes.screenBrightness = brightness.coerceIn(0.01f, 1f)
        activity.window.attributes = attributes
    }

    private fun showBrightnessOverlay(brightness: Float) {
        showGestureOverlay(
            type = GestureIconType.BRIGHTNESS,
            progress = brightness,
            volLevel = 0f,
            boost = false
        )
    }

    private fun readSystemBrightness(activity: Activity): Float {
        val brightness = try {
            Settings.System.getInt(
                activity.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            ).toFloat() / 255f
        } catch (e: Exception) {
            0.5f
        }
        return brightness.coerceIn(0.01f, 1f)
    }

    fun setScrubbing(scrubbing: Boolean) {
        isScrubbing = scrubbing
        player.updateState { it.copy(isScrubbing = scrubbing) }
        if (scrubbing) {
            autoHideJob?.cancel()
            _isHudVisible.value = true
        } else {
            scheduleAutoHide()
        }
    }

    fun onScreenTap() {
        if (_gestureState.value.isVisible) {
            dismissGestureOverlayImmediately()
            showHud()
            return
        }

        if (player.playbackState.value.isControlsLocked) {
            val next = !_isHudVisible.value
            _isHudVisible.value = next
            if (next) {
                scheduleLockAutoHide()
            } else {
                autoHideJob?.cancel()
            }
            return
        }

        val next = !_isHudVisible.value
        _isHudVisible.value = next
        if (next) {
            scheduleAutoHide()
        } else {
            autoHideJob?.cancel()
        }
    }

    fun showHud() {
        _isHudVisible.value = true
        if (!isScrubbing) {
            scheduleAutoHide()
        }
    }

    fun hideHud() {
        if (!isScrubbing) {
            _isHudVisible.value = false
            autoHideJob?.cancel()
        }
    }

    fun scheduleAutoHide() {
        if (isScrubbing) return
        autoHideJob?.cancel()
        autoHideJob = scope.launch {
            delay(3500)
            if (!player.playbackState.value.isControlsLocked && !isScrubbing) {
                _isHudVisible.value = false
            }
        }
    }

    fun scheduleLockAutoHide() {
        autoHideJob?.cancel()
        autoHideJob = scope.launch {
            delay(4000)
            if (player.playbackState.value.isControlsLocked) {
                _isHudVisible.value = false
            }
        }
    }

    fun onDoubleTapSeek(isForward: Boolean) {
        val current = _doubleTapSeekState.value
        val newSeconds = if (current.isVisible && current.isForward == isForward) {
            current.seconds + 10
        } else {
            10
        }
        val delta = if (isForward) 10.0 else -10.0
        player.seekRelative(delta)

        _doubleTapSeekState.value = DoubleTapSeekState(
            isVisible = true,
            isForward = isForward,
            seconds = newSeconds
        )

        doubleTapJob?.cancel()
        doubleTapJob = scope.launch {
            delay(900)
            _doubleTapSeekState.value = _doubleTapSeekState.value.copy(isVisible = false)
        }
    }

    fun adjustVolumeGesture(deltaPercent: Float) {
        if (!_gestureState.value.isVisible ||
            _gestureState.value.iconType != GestureIconType.VOLUME
        ) {
            accumulatedVolumeDelta = 0f
        }
        dismissBrightnessImmediately()
        hideHud()

        accumulatedVolumeDelta += deltaPercent * 30f * 0.7f
        val steps = accumulatedVolumeDelta.toInt()
        if (steps != 0) {
            val current = player.playbackState.value.volumeLevel
            val target = (current + steps).coerceIn(0, 30)
            player.setVolume30(target)
            accumulatedVolumeDelta = if (target == 0 || target == 30) {
                0f
            } else {
                accumulatedVolumeDelta - steps
            }
        }

        // The system stream changes in discrete steps, but the indicator follows
        // every fractional pointer movement without restarting an animation.
        val displayLevel = (
            player.playbackState.value.volumeLevel + accumulatedVolumeDelta
        ).coerceIn(0f, 30f)
        showGestureOverlay(
            type = GestureIconType.VOLUME,
            progress = displayLevel / 30f,
            volLevel = displayLevel,
            boost = displayLevel > 15f
        )
    }

    fun onHardwareVolumeKey(direction: Int) {
        accumulatedVolumeDelta = 0f
        dismissBrightnessImmediately()
        hideHud()
        showVolumeOverlay(player.adjustDeviceVolume(direction))
    }

    private fun showVolumeOverlay(volume: VolumeUiState) {
        showGestureOverlay(
            type = GestureIconType.VOLUME,
            progress = volume.level.toFloat() / 30f,
            volLevel = volume.level.toFloat(),
            boost = volume.isBoost
        )
    }

    fun adjustBrightnessGesture(activity: Activity?, deltaPercent: Float) {
        dismissVolumeImmediately()
        hideHud()

        activity?.let { act ->
            if (!Settings.System.canWrite(act) && !hasRequestedBrightnessPermission) {
                requestSystemBrightnessPermission(act)
                return@let
            }
            if (brightnessBeforePlayer == null) {
                brightnessBeforePlayer = act.window.attributes.screenBrightness
            }
            val current = currentBrightness ?: readSystemBrightness(act)
            val target = (current + deltaPercent).coerceIn(0.01f, 1.0f)
            currentBrightness = target

            if (Settings.System.canWrite(act)) {
                pendingSystemBrightness = target
                startSystemBrightnessWriter(act)
            }
            applyWindowBrightness(act, target)
            showBrightnessOverlay(target)
        }
    }

    private fun requestSystemBrightnessPermission(activity: Activity) {
        hasRequestedBrightnessPermission = true
        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
            data = Uri.parse("package:${activity.packageName}")
        }
        try {
            activity.startActivity(intent)
        } catch (t: Throwable) {
            try {
                activity.startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (ignored: Throwable) {
            }
        }
    }

    /** Coalesces system writes off the UI thread while the window/fill update immediately. */
    private fun startSystemBrightnessWriter(activity: Activity?) {
        if (activity == null || !Settings.System.canWrite(activity)) return
        if (systemBrightnessWriteJob?.isActive == true) return
        systemBrightnessWriteJob = scope.launch(Dispatchers.IO) {
            while (true) {
                val target = pendingSystemBrightness
                if (target != null) {
                    pendingSystemBrightness = null
                    writeSystemBrightness(activity, target)
                }
                delay(50)
            }
        }
    }

    private fun stopSystemBrightnessWriter(activity: Activity?) {
        systemBrightnessWriteJob?.cancel()
        systemBrightnessWriteJob = null
        val finalTarget = pendingSystemBrightness
        pendingSystemBrightness = null
        if (activity != null && finalTarget != null && Settings.System.canWrite(activity)) {
            // Do not lose the final finger position if the player closes mid-drag.
            scope.launch(Dispatchers.IO) {
                writeSystemBrightness(activity, finalTarget)
            }
        }
    }

    private fun writeSystemBrightness(activity: Activity, brightness: Float) {
        try {
            Settings.System.putInt(
                activity.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                (brightness * 255f).roundToInt().coerceIn(1, 255)
            )
        } catch (t: Throwable) {
            // The local window remains functional if the grant is revoked.
        }
    }

    private fun dismissBrightnessImmediately() {
        if (_gestureState.value.iconType == GestureIconType.BRIGHTNESS) {
            gestureHideJob?.cancel()
            _gestureState.value = _gestureState.value.copy(isVisible = false)
        }
    }

    private fun dismissVolumeImmediately() {
        if (_gestureState.value.iconType == GestureIconType.VOLUME) {
            accumulatedVolumeDelta = 0f
            gestureHideJob?.cancel()
            _gestureState.value = _gestureState.value.copy(isVisible = false)
        }
    }

    fun dismissGestureOverlayImmediately() {
        gestureHideJob?.cancel()
        _gestureState.value = _gestureState.value.copy(isVisible = false)
    }

    private fun showGestureOverlay(
        type: GestureIconType,
        progress: Float,
        volLevel: Float,
        boost: Boolean
    ) {
        _gestureState.value = GestureOverlayState(
            isVisible = true,
            iconType = type,
            progress = progress,
            volumeLevel = volLevel,
            isBoost = boost
        )
        lastGestureInteractionMs = SystemClock.uptimeMillis()
        if (gestureHideJob?.isActive != true) {
            gestureHideJob = scope.launch {
                while (true) {
                    val remainingMs = 1_100L -
                        (SystemClock.uptimeMillis() - lastGestureInteractionMs)
                    if (remainingMs <= 0L) break
                    delay(remainingMs)
                }
                _gestureState.value = _gestureState.value.copy(isVisible = false)
                gestureHideJob = null
            }
        }
    }

    fun toggleOrientation(activity: Activity?) {
        activity?.let { act ->
            val current = act.requestedOrientation
            act.requestedOrientation = if (current == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE ||
                current == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            ) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }
    }

    fun toggleLock() {
        val currentLocked = player.playbackState.value.isControlsLocked
        val newLocked = !currentLocked
        player.setControlsLocked(newLocked)
        if (newLocked) {
            _isHudVisible.value = true
            scheduleLockAutoHide()
        } else {
            showHud()
        }
    }

    fun replay() {
        player.seekTo(0L)
        player.resume()
    }

    /** Maintains timeline preview state without blocking playback or native work. */
    fun onTimelinePreview(targetMs: Long, fraction: Float, active: Boolean) {
        val duration = player.playbackState.value.durationMs.coerceAtLeast(1L)
        val target = targetMs.coerceIn(0L, duration)
        timelinePreviewJob?.cancel()
        if (active) {
            player.updateState {
                it.copy(slideSeekState = SlideSeekState(
                    isActive = true,
                    targetMs = target,
                    seekFraction = fraction.coerceIn(0f, 1f),
                    thumbnailBitmap = null
                ))
            }
        } else {
            timelinePreviewJob = scope.launch {
                delay(400)
                if (!isScrubbing) {
                    player.updateState { it.copy(slideSeekState = SlideSeekState(isActive = false)) }
                }
            }
        }
    }

    private var slideSeekStartPositionMs: Long = 0L

    fun onSlideSeekStart(
        startPositionMs: Long = player.playbackState.value.positionMs
    ) {
        dismissGestureOverlayImmediately()
        hideHud()
        // Playback continues; dragging only updates the centered text preview.
        slideSeekStartPositionMs = startPositionMs
        val previewStart = SlideSeekMath.previewStartPositionMs(startPositionMs)
        val duration = player.playbackState.value.durationMs.coerceAtLeast(1L)
        val initialFraction = (previewStart.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        player.updateState {
            it.copy(
                slideSeekState = SlideSeekState(
                    isActive = true,
                    startMs = previewStart,
                    targetMs = previewStart,
                    seekFraction = initialFraction,
                    thumbnailBitmap = null
                )
            )
        }
    }

    fun onSlideSeek(targetMs: Long, fraction: Float) {
        val duration = player.playbackState.value.durationMs
        if (duration <= 0L) return
        val clampedFraction = fraction.coerceIn(0f, 1f)
        val clampedTargetMs = targetMs.coerceIn(0L, duration)

        player.updateState {
            it.copy(
                slideSeekState = it.slideSeekState.copy(
                    isActive = true,
                    targetMs = clampedTargetMs,
                    seekFraction = clampedFraction
                )
            )
        }
    }

    fun onSlideSeekEnd(finalTargetMs: Long? = null) {
        val state = player.playbackState.value
        if (!state.slideSeekState.isActive) return

        val target = (finalTargetMs ?: state.slideSeekState.targetMs)
            .coerceIn(0L, state.durationMs.coerceAtLeast(0L))
        val shouldSeek = state.durationMs > 0L && SlideSeekMath.shouldSeekOnRelease(
            startPositionMs = slideSeekStartPositionMs,
            targetPositionMs = target
        )
        player.updateState {
            it.copy(slideSeekState = SlideSeekState(isActive = false))
        }
        if (shouldSeek) {
            // Exactly one real seek, only after the finger is released.
            player.seekTo(target)
        }
    }

    fun onSlideSeekCancel() {
        // An interrupted gesture must never fall through to an arbitrary seek.
        player.updateState {
            it.copy(slideSeekState = SlideSeekState(isActive = false))
        }
    }

    companion object {
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
    }
}
