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

    private var autoHideJob: Job? = null
    private var gestureHideJob: Job? = null
    private var isScrubbing: Boolean = false

    private var accumulatedVolumeDelta: Float = 0f

    init {
        scheduleAutoHide()
    }

    fun setScrubbing(scrubbing: Boolean) {
        isScrubbing = scrubbing
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
            _isHudVisible.value = !_isHudVisible.value
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

    fun onDoubleTapSeek(isForward: Boolean) {
        val delta = if (isForward) 10.0 else -10.0
        player.seekRelative(delta)
        showGestureOverlay(
            type = GestureIconType.SEEK,
            text = if (isForward) "+10s" else "-10s",
            progress = 0.5f,
            volLevel = 0,
            boost = false
        )
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
        player.setControlsLocked(!currentLocked)
        if (!currentLocked) {
            _isHudVisible.value = false
        } else {
            showHud()
        }
    }

    private var wasPlayingBeforeSlide: Boolean = false
    private var thumbnailJob: Job? = null
    private var lastLoadedBitmap: android.graphics.Bitmap? = null

    fun onSlideSeekStart() {
        dismissGestureOverlayImmediately()
        hideHud()
        val currentStatus = player.playbackState.value.status
        wasPlayingBeforeSlide = (currentStatus == PlaybackStatus.PLAYING)
        if (wasPlayingBeforeSlide) {
            player.pause()
        }
        val duration = player.playbackState.value.durationMs.coerceAtLeast(1L)
        val pos = player.playbackState.value.positionMs
        val initialFraction = (pos.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        player.updateState {
            it.copy(
                slideSeekState = SlideSeekState(
                    isActive = true,
                    targetMs = pos,
                    seekFraction = initialFraction,
                    thumbnailBitmap = lastLoadedBitmap
                )
            )
        }
    }

    fun onSlideSeek(fraction: Float) {
        val clampedFraction = fraction.coerceIn(0f, 1f)
        val duration = player.playbackState.value.durationMs.coerceAtLeast(1L)
        val targetMs = (clampedFraction * duration).toLong().coerceIn(0L, duration)

        player.updateState {
            it.copy(
                slideSeekState = it.slideSeekState.copy(
                    isActive = true,
                    targetMs = targetMs,
                    seekFraction = clampedFraction
                )
            )
        }

        thumbnailJob?.cancel()
        thumbnailJob = scope.launch {
            val bmp = player.loadThumbnailFrame(targetMs)
            if (bmp != null) {
                lastLoadedBitmap = bmp
                player.updateState {
                    if (it.slideSeekState.isActive) {
                        it.copy(slideSeekState = it.slideSeekState.copy(thumbnailBitmap = bmp))
                    } else it
                }
            }
        }
    }

    fun onSlideSeekEnd() {
        thumbnailJob?.cancel()
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
