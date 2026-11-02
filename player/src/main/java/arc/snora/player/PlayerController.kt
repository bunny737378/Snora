package arc.snora.player

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
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
    val text: String = "",
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
    BRIGHTNESS,
    SEEK
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

    init {
        scheduleAutoHide()
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
            player.setVolume30(target)
            accumulatedVolumeDelta -= steps

            val isBoost = target > 15
            val label = "$target%"
            val progress = target.toFloat() / 30f

            showGestureOverlay(
                type = GestureIconType.VOLUME,
                text = label,
                progress = progress,
                volLevel = target,
                boost = isBoost
            )
        }
    }

    fun adjustBrightnessGesture(activity: Activity?, deltaPercent: Float) {
        dismissVolumeImmediately()
        hideHud()

        activity?.let { act ->
            val lp = act.window.attributes
            var current = lp.screenBrightness
            if (current < 0) {
                current = try {
                    Settings.System.getInt(
                        act.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS
                    ) / 255f
                } catch (e: Exception) {
                    0.5f
                }
            }
            val target = (current + deltaPercent).coerceIn(0.01f, 1.0f)
            lp.screenBrightness = target
            act.window.attributes = lp
            val percentInt = (target * 100).toInt().coerceIn(1, 100)

            showGestureOverlay(
                type = GestureIconType.BRIGHTNESS,
                text = "$percentInt%",
                progress = target,
                volLevel = 0,
                boost = false
            )
        }
    }

    private fun dismissBrightnessImmediately() {
        if (_gestureState.value.iconType == GestureIconType.BRIGHTNESS) {
            gestureHideJob?.cancel()
            _gestureState.value = GestureOverlayState(isVisible = false)
        }
    }

    private fun dismissVolumeImmediately() {
        if (_gestureState.value.iconType == GestureIconType.VOLUME) {
            gestureHideJob?.cancel()
            _gestureState.value = GestureOverlayState(isVisible = false)
        }
    }

    fun dismissGestureOverlayImmediately() {
        gestureHideJob?.cancel()
        _gestureState.value = GestureOverlayState(isVisible = false)
    }

    private fun showGestureOverlay(
        type: GestureIconType,
        text: String,
        progress: Float,
        volLevel: Int,
        boost: Boolean
    ) {
        _gestureState.value = GestureOverlayState(
            isVisible = true,
            iconType = type,
            text = text,
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

    /** Display timestamp pill while scrubbing/tapping without thumbnail extraction lag. */
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

    private var wasPlayingBeforeSlide: Boolean = false
    private var isThumbnailLoading: Boolean = false
    private var lastThumbnailRequestMs: Long = -1L

    fun onSlideSeekStart() {
        dismissGestureOverlayImmediately()
        hideHud()
        val currentStatus = player.playbackState.value.status
        wasPlayingBeforeSlide = (currentStatus == PlaybackStatus.PLAYING)
        if (wasPlayingBeforeSlide) {
            player.pause()
        }
        val pos = player.playbackState.value.positionMs
        val duration = player.playbackState.value.durationMs.coerceAtLeast(1L)
        val initialFraction = (pos.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        lastThumbnailRequestMs = -1L
        player.updateState {
            it.copy(
                slideSeekState = SlideSeekState(
                    isActive = true,
                    targetMs = pos,
                    seekFraction = initialFraction,
                    thumbnailBitmap = null
                )
            )
        }
        requestThumbnail(pos)
    }

    fun onSlideSeek(targetMs: Long, fraction: Float) {
        val clampedFraction = fraction.coerceIn(0f, 1f)
        val duration = player.playbackState.value.durationMs.coerceAtLeast(1L)
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

        if (kotlin.math.abs(clampedTargetMs - lastThumbnailRequestMs) > 800L) {
            requestThumbnail(clampedTargetMs)
        }
    }

    private fun requestThumbnail(targetMs: Long) {
        if (isThumbnailLoading) return
        lastThumbnailRequestMs = targetMs
        isThumbnailLoading = true
        scope.launch {
            try {
                val bmp = player.loadThumbnailFrame(targetMs)
                if (bmp != null) {
                    player.updateState {
                        if (it.slideSeekState.isActive) {
                            it.copy(slideSeekState = it.slideSeekState.copy(thumbnailBitmap = bmp))
                        } else it
                    }
                }
            } finally {
                isThumbnailLoading = false
                val currentTarget = player.playbackState.value.slideSeekState.targetMs
                if (player.playbackState.value.slideSeekState.isActive &&
                    kotlin.math.abs(currentTarget - lastThumbnailRequestMs) > 800L
                ) {
                    requestThumbnail(currentTarget)
                }
            }
        }
    }

    fun onSlideSeekEnd() {
        val targetMs = player.playbackState.value.slideSeekState.targetMs
        player.seekTo(targetMs)
        player.updateState {
            it.copy(slideSeekState = SlideSeekState(isActive = false))
        }
        if (wasPlayingBeforeSlide) {
            player.resume()
        }
    }
}
