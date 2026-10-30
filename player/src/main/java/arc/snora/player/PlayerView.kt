package arc.snora.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun PlayerView(
    controller: PlayerController,
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val playbackState by controller.player.playbackState.collectAsState()
    val isHudVisible by controller.isHudVisible.collectAsState()
    val gestureState by controller.gestureState.collectAsState()

    DisposableEffect(controller.player) {
        onDispose {
            controller.player.pause()
            controller.player.detachSurface()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("snora_player_container")
            .pointerInput(playbackState.isControlsLocked) {
                detectTapGestures(
                    onTap = {
                        controller.toggleHudVisibility()
                    },
                    onDoubleTap = { offset ->
                        if (!playbackState.isControlsLocked) {
                            val isRightHalf = offset.x > size.width / 2
                            controller.onDoubleTapSeek(isRightHalf)
                        }
                    }
                )
            }
            .pointerInput(playbackState.isControlsLocked) {
                if (!playbackState.isControlsLocked) {
                    var totalDragY = 0f
                    detectDragGestures(
                        onDragStart = { totalDragY = 0f },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            totalDragY += dragAmount.y
                            val isLeftHalf = change.position.x < size.width / 2
                            val deltaPercent = -dragAmount.y / (size.height * 0.75f)

                            if (isLeftHalf) {
                                controller.adjustBrightnessGesture(activity, deltaPercent)
                            } else {
                                controller.adjustVolumeGesture(deltaPercent)
                            }
                        }
                    )
                }
            }
    ) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            controller.player.attachSurface(holder.surface)
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int
                        ) {
                            controller.player.attachSurface(holder.surface)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            controller.player.detachSurface()
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        PlayerControls(
            state = playbackState,
            isHudVisible = isHudVisible,
            gestureState = gestureState,
            title = title,
            onBack = onBack,
            onTogglePlayPause = { controller.player.togglePlayPause() },
            onSeek = { targetMs -> controller.player.seekTo(targetMs) },
            onSeekRelative = { deltaSec -> controller.player.seekRelative(deltaSec) },
            onSetSpeed = { speed -> controller.player.setSpeed(speed) },
            onCycleAspectRatio = { controller.player.videoRenderer.cycleAspectRatioMode() },
            onToggleLock = { controller.toggleLock() },
            onToggleOrientation = { controller.toggleOrientation(activity) },
            onSelectSubtitle = { trackId -> controller.player.subtitleManager.selectSubtitleTrack(trackId) },
            onSelectAudio = { trackId -> controller.player.audioManager.selectAudioTrack(trackId) },
            onSubDelayAdjust = { delta -> controller.player.subtitleManager.adjustSubtitleDelay(delta) },
            onAudioDelayAdjust = { delta -> controller.player.audioManager.adjustAudioDelay(delta) },
            onHwDecChange = { mode -> controller.player.videoRenderer.setHwDecMode(mode) }
        )
    }
}

fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
