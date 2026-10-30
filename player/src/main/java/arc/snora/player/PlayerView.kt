package arc.snora.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.abs

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

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
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
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            if (abs(dragAmount.y) > abs(dragAmount.x) * 1.3f) {
                                change.consume()
                                val isLeftHalf = change.position.x < size.width / 2
                                val deltaPercent = -dragAmount.y / (size.height * 0.7f)

                                if (isLeftHalf) {
                                    controller.adjustBrightnessGesture(activity, deltaPercent)
                                } else {
                                    controller.adjustVolumeGesture(deltaPercent)
                                }
                            }
                        }
                    )
                }
            }
    ) {
        val containerWidth = maxWidth
        val containerHeight = maxHeight

        val videoW = if (playbackState.videoInfo.width > 0) playbackState.videoInfo.width.toFloat() else 16f
        val videoH = if (playbackState.videoInfo.height > 0) playbackState.videoInfo.height.toFloat() else 9f
        val videoAspect = videoW / videoH

        val surfaceModifier = when (playbackState.aspectRatioMode) {
            AspectRatioMode.FIT -> {
                val containerAspect = containerWidth.value / containerHeight.value.coerceAtLeast(1f)
                if (videoAspect > containerAspect) {
                    val h = containerWidth.value / videoAspect
                    Modifier.size(containerWidth, h.dp)
                } else {
                    val w = containerHeight.value * videoAspect
                    Modifier.size(w.dp, containerHeight)
                }
            }
            AspectRatioMode.CROP -> {
                val containerAspect = containerWidth.value / containerHeight.value.coerceAtLeast(1f)
                if (videoAspect > containerAspect) {
                    val w = containerHeight.value * videoAspect
                    Modifier.size(w.dp, containerHeight)
                } else {
                    val h = containerWidth.value / videoAspect
                    Modifier.size(containerWidth, h.dp)
                }
            }
            AspectRatioMode.STRETCH -> {
                Modifier.fillMaxSize()
            }
            AspectRatioMode.RATIO_16_9 -> {
                val targetAspect = 16f / 9f
                val containerAspect = containerWidth.value / containerHeight.value.coerceAtLeast(1f)
                if (targetAspect > containerAspect) {
                    val h = containerWidth.value / targetAspect
                    Modifier.size(containerWidth, h.dp)
                } else {
                    val w = containerHeight.value * targetAspect
                    Modifier.size(w.dp, containerHeight)
                }
            }
            AspectRatioMode.RATIO_4_3 -> {
                val targetAspect = 4f / 3f
                val containerAspect = containerWidth.value / containerHeight.value.coerceAtLeast(1f)
                if (targetAspect > containerAspect) {
                    val h = containerWidth.value / targetAspect
                    Modifier.size(containerWidth, h.dp)
                } else {
                    val w = containerHeight.value * targetAspect
                    Modifier.size(w.dp, containerHeight)
                }
            }
        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
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
                modifier = surfaceModifier
            )
        }

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
            onHwDecChange = { mode -> controller.player.videoRenderer.setHwDecMode(mode) },
            onScrubbingChange = { isScrubbing -> controller.setScrubbing(isScrubbing) }
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
