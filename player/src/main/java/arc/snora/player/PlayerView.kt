package arc.snora.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val playbackState by controller.player.playbackState.collectAsState()
    val isHudVisible by controller.isHudVisible.collectAsState()
    val gestureState by controller.gestureState.collectAsState()
    val doubleTapSeekState by controller.doubleTapSeekState.collectAsState()

    var activeMenu by remember { mutableStateOf<String?>(null) }

    // Physical Back Button navigation priority:
    // 1. If side menu is open, back closes the menu
    // 2. If landscape mode, back returns to portrait mode
    // 3. Otherwise outer back handler handles it
    BackHandler(enabled = activeMenu != null) {
        activeMenu = null
    }

    BackHandler(enabled = activeMenu == null && isLandscape) {
        controller.toggleOrientation(activity)
    }

    val subtitlePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            controller.player.subtitleManager.loadExternalSubtitle(it.toString())
        }
    }

    val audioPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            controller.player.audioManager.loadExternalAudio(it.toString())
        }
    }

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
            .pointerInput(playbackState.isControlsLocked, activeMenu) {
                if (activeMenu == null) {
                    detectTapGestures(
                        onTap = { controller.onScreenTap() },
                        onDoubleTap = { offset ->
                            if (!playbackState.isControlsLocked) {
                                val isRightHalf = offset.x > size.width / 2
                                controller.onDoubleTapSeek(isRightHalf)
                            }
                        }
                    )
                }
            }
            .pointerInput(playbackState.isControlsLocked, activeMenu) {
                if (!playbackState.isControlsLocked && activeMenu == null) {
                    var dragMode = 0 // 0: None, 1: Horizontal Slide Seek, 2: Vertical Gesture
                    var slideStartPosMs = 0L
                    var slideDragDistanceX = 0f
                    val totalDuration = playbackState.durationMs.coerceAtLeast(1L)

                    detectDragGestures(
                        onDragStart = {
                            dragMode = 0
                        },
                        onDragEnd = {
                            if (dragMode == 1) {
                                controller.onSlideSeekEnd()
                            }
                            dragMode = 0
                        },
                        onDragCancel = {
                            if (dragMode == 1) {
                                controller.onSlideSeekEnd()
                            }
                            dragMode = 0
                        },
                        onDrag = { change, dragAmount ->
                            if (dragMode == 0) {
                                if (abs(dragAmount.x) > abs(dragAmount.y) * 1.1f && abs(dragAmount.x) > 6f) {
                                    dragMode = 1
                                    slideStartPosMs = playbackState.positionMs
                                    slideDragDistanceX = 0f
                                    controller.onSlideSeekStart()
                                } else if (abs(dragAmount.y) > abs(dragAmount.x) * 1.1f && abs(dragAmount.y) > 6f) {
                                    dragMode = 2
                                }
                            }

                            if (dragMode == 1) {
                                change.consume()
                                slideDragDistanceX += dragAmount.x
                                val deltaMs = ((slideDragDistanceX / size.width.toFloat()) * 60_000f).toLong()
                                val targetMs = (slideStartPosMs + deltaMs).coerceIn(0L, totalDuration)
                                val fraction = (targetMs.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
                                controller.onSlideSeek(targetMs, fraction)
                            } else if (dragMode == 2) {
                                change.consume()
                                val isLeftHalf = change.position.x < size.width / 2
                                val deltaPercent = -dragAmount.y / (size.height * 0.75f)

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
                                controller.player.onSurfaceChanged(width, height)
                            }

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                controller.player.detachSurface()
                            }
                        })
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        PlayerControls(
            state = playbackState,
            isHudVisible = isHudVisible,
            gestureState = gestureState,
            doubleTapSeekState = doubleTapSeekState,
            activeMenu = activeMenu,
            onSetActiveMenu = { activeMenu = it },
            title = title,
            onBack = {
                if (isLandscape) {
                    controller.toggleOrientation(activity)
                } else {
                    onBack()
                }
            },
            onTogglePlayPause = { controller.player.togglePlayPause() },
            onSeek = { targetMs -> controller.player.seekTo(targetMs) },
            onSeekRelative = { deltaSec -> controller.player.seekRelative(deltaSec) },
            onSetSpeed = { speed -> controller.player.setSpeed(speed) },
            onSelectAspectRatio = { mode -> controller.player.videoRenderer.setAspectRatioMode(mode) },
            onCycleAspectRatio = { controller.player.videoRenderer.cycleAspectRatioMode() },
            onToggleLock = { controller.toggleLock() },
            onSelectSubtitle = { trackId -> controller.player.subtitleManager.selectSubtitleTrack(trackId) },
            onSelectAudio = { trackId -> controller.player.audioManager.selectAudioTrack(trackId) },
            onSelectChapter = { timeMs -> controller.player.seekTo(timeMs) },
            onSubDelayAdjust = { delta -> controller.player.subtitleManager.adjustSubtitleDelay(delta) },
            onAudioDelayAdjust = { delta -> controller.player.audioManager.adjustAudioDelay(delta) },
            onToggleHwDec = { controller.player.videoRenderer.toggleHwDec() },
            onScrubbingChange = { isScrubbing -> controller.setScrubbing(isScrubbing) },
            onPreviewSeek = { targetMs, fraction, active -> controller.onTimelinePreview(targetMs, fraction, active) },
            onPickSubtitle = { subtitlePicker.launch(arrayOf("*/*")) },
            onPickAudio = { audioPicker.launch(arrayOf("audio/*", "*/*")) },
            onToggleOrientation = { controller.toggleOrientation(activity) },
            onReplay = { controller.replay() }
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
