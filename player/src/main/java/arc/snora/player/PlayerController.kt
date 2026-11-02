package arc.snora.player

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.provider.Settings
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
    val volumeLevel: Int = 15,
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
    private var doubleTapJob: Job? = null
    private var timelinePreviewJob: Job? = null
    private var isScrubbing: Boolean = false

    private var accumulatedVolumeDelta: Float = 0f
    private var brightnessBeforePlayer: Float? = null
    private var currentBrightness: Float? = null

    init {
        scheduleAutoHide()
    }

    fun onPlayerViewCreated(activity: Activity?) {
        if (activity != null && brightnessBeforePlayer == null) {
            val attributes = activity.window.attributes
            brightnessBeforePlayer = attributes.screenBrightness
            currentBrightness = if (attributes.screenBrightness >= 0f) {
                attributes.screenBrightness
            } else {
                readSystemBrightness(activity)
            }
        }
        accumulatedVolumeDelta = 0f
        isScrubbing = false
        _gestureState.value = GestureOverlayState()
        _doubleTapSeekState.value = DoubleTapSeekState()
        showHud()
    }

    fun onPlayerViewDisposed(activity: Activity?) {
        autoHideJob?.cancel()
        gestureHideJob?.cancel()
        doubleTapJob?.cancel()
        timelinePreviewJob?.cancel()
        _gestureState.value = GestureOverlayState()
        _doubleTapSeekState.value = DoubleTapSeekState()

        val original = brightnessBeforePlayer
        if (activity != null && original != null) {
            // Window brightness controls the real display while SNORA is visible,
            // not a video shader. Restoring the original override also restores
            // the system's previous brightness as soon as the player closes.
            val attributes = activity.window.attributes
            attributes.screenBrightness = original
            activity.window.attributes = attributes
        }
        brightnessBeforePlayer = null
        currentBrightness = null
        accumulatedVolumeDelta = 0f
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
        dismissBrightnessImmediately()
        hideHud()

        accumulatedVolumeDelta += deltaPercent * 30f * 0.7f
        val steps = accumulatedVolumeDelta.toInt()
        if (steps != 0) {
            val current = player.playbackState.value.volumeLevel
            val target = (current + steps).coerceIn(0, 30)
            val volume = player.setVolume30(target)
            accumulatedVolumeDelta -= steps
            showVolumeOverlay(volume)
        }
    }

    fun onHardwareVolumeKey(direction: Int) {
        dismissBrightnessImmediately()
        hideHud()
        showVolumeOverlay(player.adjustDeviceVolume(direction))
    }

    private fun showVolumeOverlay(volume: VolumeUiState) {
        showGestureOverlay(
            type = GestureIconType.VOLUME,
            progress = volume.level.toFloat() / 30f,
            volLevel = volume.level,
            boost = volume.isBoost
        )
    }

    fun adjustBrightnessGesture(activity: Activity?, deltaPercent: Float) {
        dismissVolumeImmediately()
        hideHud()

        activity?.let { act ->
            if (brightnessBeforePlayer == null) {
                brightnessBeforePlayer = act.window.attributes.screenBrightness
                currentBrightness = readSystemBrightness(act)
            }
            val current = currentBrightness ?: readSystemBrightness(act)
            val target = (current + deltaPercent).coerceIn(0.01f, 1.0f)
            currentBrightness = target
            val lp = act.window.attributes
            lp.screenBrightness = target
            act.window.attributes = lp

            showGestureOverlay(
                type = GestureIconType.BRIGHTNESS,
                progress = target,
                volLevel = 0,
                boost = false
            )
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
        volLevel: Int,
        boost: Boolean
    ) {
        _gestureState.value = GestureOverlayState(
            isVisible = true,
            iconType = type,
            progress = progress,
            volumeLevel = volLevel,
            isBoost = boost
        )
        gestureHideJob?.cancel()
        gestureHideJob = scope.launch {
            delay(1100)
            _gestureState.value = _gestureState.value.copy(isVisible = false)
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
}
