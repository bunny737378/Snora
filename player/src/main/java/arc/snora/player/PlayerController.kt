package arc.snora.player

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager as AndroidAudioManager
import android.provider.Settings
import android.view.WindowManager
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
    val progress: Float = 0f
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

    private val _isHudVisible = MutableStateFlow(true)
    val isHudVisible: StateFlow<Boolean> = _isHudVisible.asStateFlow()

    private val _gestureState = MutableStateFlow(GestureOverlayState())
    val gestureState: StateFlow<GestureOverlayState> = _gestureState.asStateFlow()

    private var autoHideJob: Job? = null
    private var gestureHideJob: Job? = null

    private val systemAudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as? AndroidAudioManager

    init {
        scheduleAutoHide()
    }

    fun toggleHudVisibility() {
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
        scheduleAutoHide()
    }

    fun hideHud() {
        _isHudVisible.value = false
        autoHideJob?.cancel()
    }

    fun scheduleAutoHide() {
        autoHideJob?.cancel()
        autoHideJob = scope.launch {
            delay(3500)
            if (!player.playbackState.value.isControlsLocked) {
                _isHudVisible.value = false
            }
        }
    }

    fun onDoubleTapSeek(isForward: Boolean) {
        val delta = if (isForward) 10.0 else -10.0
        player.seekRelative(delta)
        showGestureOverlay(
            GestureIconType.SEEK,
            if (isForward) "+10s" else "-10s",
            0.5f
        )
    }

    fun adjustVolumeGesture(deltaPercent: Float) {
        systemAudioManager?.let { am ->
            val max = am.getStreamMaxVolume(AndroidAudioManager.STREAM_MUSIC)
            val current = am.getStreamVolume(AndroidAudioManager.STREAM_MUSIC)
            val deltaSteps = (deltaPercent * max).toInt()
            val target = (current + deltaSteps).coerceIn(0, max)
            am.setStreamVolume(AndroidAudioManager.STREAM_MUSIC, target, 0)
            val pct = target.toFloat() / max.toFloat()
            showGestureOverlay(
                GestureIconType.VOLUME,
                "${(pct * 100).toInt()}%",
                pct
            )
        }
    }

    fun adjustBrightnessGesture(activity: Activity?, deltaPercent: Float) {
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
            showGestureOverlay(
                GestureIconType.BRIGHTNESS,
                "${(target * 100).toInt()}%",
                target
            )
        }
    }

    private fun showGestureOverlay(type: GestureIconType, text: String, progress: Float) {
        _gestureState.value = GestureOverlayState(
            isVisible = true,
            iconType = type,
            text = text,
            progress = progress
        )
        gestureHideJob?.cancel()
        gestureHideJob = scope.launch {
            delay(1000)
            _gestureState.value = _gestureState.value.copy(isVisible = false)
        }
    }

    fun toggleOrientation(activity: Activity?) {
        activity?.let { act ->
            val current = act.requestedOrientation
            act.requestedOrientation = if (current == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
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
}
