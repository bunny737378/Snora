package arc.snora.player

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlayerControls(
    state: PlaybackState,
    isHudVisible: Boolean,
    gestureState: GestureOverlayState,
    doubleTapSeekState: DoubleTapSeekState = DoubleTapSeekState(),
    activeMenu: String? = null,
    onSetActiveMenu: (String?) -> Unit = {},
    title: String,
    onBack: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekRelative: (Double) -> Unit,
    onSeekStep: (Boolean) -> Unit = { forward -> onSeekRelative(if (forward) 10.0 else -10.0) },
    onSetSpeed: (Float) -> Unit,
    onSelectAspectRatio: (AspectRatioMode) -> Unit,
    onCycleAspectRatio: () -> Unit,
    onToggleLock: () -> Unit,
    onSelectSubtitle: (Int) -> Unit,
    onSelectAudio: (Int) -> Unit,
    onSelectChapter: (Long) -> Unit,
    onSubDelayAdjust: (Double) -> Unit,
    onAudioDelayAdjust: (Double) -> Unit,
    onToggleHwDec: () -> Unit,
    onScrubbingChange: (Boolean) -> Unit,
    onPreviewSeek: (Long, Float, Boolean) -> Unit = { _, _, _ -> },
    onPickSubtitle: () -> Unit,
    onPickAudio: () -> Unit,
    onToggleOrientation: () -> Unit = {},
    onReplay: () -> Unit = { onSeek(0L); onTogglePlayPause() },
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val coroutineScope = rememberCoroutineScope()
    var aspectToastText by remember { mutableStateOf<String?>(null) }
    val hudProgress by animateFloatAsState(
        targetValue = if (isHudVisible && activeMenu == null) 1f else 0f,
        animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing),
        label = "classic_hud_visibility"
    )

    Box(modifier = modifier.fillMaxSize()) {

        // Compact circular seek feedback, independent from HUD visibility.
        ClassicSeekPulseOverlay(state = doubleTapSeekState)

        // Subtitle overlay: null appearance deliberately preserves player defaults.
        if (state.selectedSubtitleTrackId != -1 && state.currentSubtitleText.isNotBlank()) {
            val appearance = state.subtitleAppearance
            val subtitleTextColor = appearance?.let { Color(it.textColorArgb) } ?: Color.Yellow
            val subtitleBackgroundColor = appearance?.let {
                Color(it.backgroundColorArgb).copy(alpha = it.backgroundOpacity)
            } ?: Color(0xCC000000)
            val subtitleModifier = if (appearance == null) {
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (isHudVisible || state.slideSeekState.isActive) 100.dp else 24.dp)
            } else {
                Modifier
                    .align(
                        BiasAlignment(
                            horizontalBias = appearance.horizontalPosition * 2f - 1f,
                            verticalBias = (1f - appearance.verticalPosition) * 2f - 1f
                        )
                    )
                    .padding(horizontal = 20.dp, vertical = 20.dp)
            }

            Box(
                modifier = subtitleModifier
                    .background(subtitleBackgroundColor, RoundedCornerShape(6.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = state.currentSubtitleText,
                    color = subtitleTextColor,
                    fontSize = (appearance?.fontSizeSp ?: SubtitleAppearance.DEFAULT_FONT_SIZE_SP).sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Gesture Overlay (Volume & Brightness)
        if (gestureState.isVisible && !isHudVisible && activeMenu == null && !state.slideSeekState.isActive) {
            val gestureAlignment = when {
                !isLandscape -> Alignment.TopCenter
                gestureState.iconType == GestureIconType.BRIGHTNESS -> Alignment.CenterEnd
                gestureState.iconType == GestureIconType.VOLUME -> Alignment.CenterStart
                else -> Alignment.Center
            }

            val gesturePadding = when {
                !isLandscape -> Modifier.padding(top = 56.dp)
                gestureState.iconType == GestureIconType.BRIGHTNESS -> Modifier.padding(end = 24.dp)
                gestureState.iconType == GestureIconType.VOLUME -> Modifier.padding(start = 24.dp)
                else -> Modifier
            }

            Box(
                modifier = Modifier
                    .align(gestureAlignment)
                    .then(gesturePadding)
            ) {
                if (gestureState.iconType == GestureIconType.VOLUME) {
                    VolumeGesturePill(
                        text = gestureState.text,
                        level = gestureState.volumeLevel,
                        isBoost = gestureState.isBoost,
                        isLandscape = isLandscape
                    )
                } else if (gestureState.iconType == GestureIconType.BRIGHTNESS) {
                    BrightnessGesturePill(
                        text = gestureState.text,
                        progress = gestureState.progress,
                        isLandscape = isLandscape
                    )
                } else if (gestureState.iconType == GestureIconType.SEEK) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xEE111317), RoundedCornerShape(12.dp))
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = gestureState.text,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Material 3 Expressive contained loading indicator for initial load and rebuffering.
        if (state.status == PlaybackStatus.BUFFERING) {
            ContainedLoadingIndicator(
                size = 56.dp,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Locked state: unlock button appears only when HUD is active (auto-hides after 4s, toggles on screen tap)
        if (state.isControlsLocked) {
            if (isHudVisible) {
                IconButton(
                    onClick = onToggleLock,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(20.dp)
                        .size(46.dp)
                        .background(Color(0xCC0D0E12), CircleShape)
                        .border(1.dp, Color(0x33FFFFFF), CircleShape)
                        .testTag("unlock_button")
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lock),
                        contentDescription = "Unlock Controls",
                        tint = Color(0xFF2979FF),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            return@Box
        }

        // On-screen Aspect Ratio Toast Badge
        if (aspectToastText != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 62.dp)
                    .background(Color(0xDD111317), RoundedCornerShape(16.dp))
                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = aspectToastText ?: "",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Classic HUD: quick opacity plus small directional travel, never a heavy sheet animation.
        if (hudProgress > 0.001f) {

            // Top Bar with soft shadow gradient
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .graphicsLayer {
                        alpha = hudProgress
                        translationY = -48f * (1f - hudProgress)
                    }
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0x99000000),
                                Color(0x44000000),
                                Color.Transparent
                            )
                        )
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Back button: simple '<' without tail
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(38.dp).testTag("player_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }

                // Centered, quiet two-line title between stable navigation actions.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    PlayerTitle(title = title)
                }

                // Right-anchored icons row (fixed position, never moves on short title)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    // HW / SW Decoder text (No box, plain text)
                    Text(
                        text = if (state.hwDecMode == HwDecMode.AUTO && !state.hwDecFailed) "HW" else "SW",
                        color = if (state.hwDecFailed) Color(0x66FFFFFF) else Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable(enabled = !state.hwDecFailed) { onToggleHwDec() }
                            .padding(horizontal = 6.dp, vertical = 6.dp)
                            .testTag("hw_sw_decoder_button")
                    )

                    Spacer(modifier = Modifier.width(4.dp))

                    // Chapter Menu Button
                    if (state.chapters.isNotEmpty()) {
                        IconButton(
                            onClick = { onSetActiveMenu("chapter") },
                            modifier = Modifier.size(36.dp).testTag("chapter_menu_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.FormatListBulleted,
                                contentDescription = "Chapters",
                                tint = Color.White,
                                modifier = Modifier.size(21.dp)
                            )
                        }
                    }

                    // Lock Controls Button
                    IconButton(
                        onClick = onToggleLock,
                        modifier = Modifier.size(36.dp).testTag("lock_button")
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_unlock),
                            contentDescription = "Lock Controls",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Classic center transport: transparent 10-second seek actions around play/pause.
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .graphicsLayer {
                        alpha = hudProgress
                        scaleX = 0.94f + (0.06f * hudProgress)
                        scaleY = 0.94f + (0.06f * hudProgress)
                    },
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.status == PlaybackStatus.ENDED) {
                    IconButton(
                        onClick = onReplay,
                        modifier = Modifier.size(68.dp).testTag("replay_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Replay,
                            contentDescription = "Replay Video",
                            tint = Color.White,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                } else {
                    CenterSeekButton(
                        isForward = false,
                        enabled = state.durationMs > 0L,
                        onClick = { onSeekStep(false) },
                        pulseActive = doubleTapSeekState.isVisible && !doubleTapSeekState.isForward
                    )

                    if (state.status != PlaybackStatus.BUFFERING) {
                        IconButton(
                            onClick = onTogglePlayPause,
                            modifier = Modifier.size(68.dp).testTag("play_pause_button")
                        ) {
                            Crossfade(
                                targetState = state.status == PlaybackStatus.PLAYING,
                                animationSpec = tween(durationMillis = 160),
                                label = "play_pause_transition"
                            ) { isPlaying ->
                                Icon(
                                    painter = painterResource(
                                        if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                                    ),
                                    contentDescription = if (isPlaying) "Pause" else "Play",
                                    tint = Color.White,
                                    modifier = Modifier.size(64.dp)
                                )
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.size(68.dp))
                    }

                    CenterSeekButton(
                        isForward = true,
                        enabled = state.durationMs > 0L,
                        onClick = { onSeekStep(true) },
                        pulseActive = doubleTapSeekState.isVisible && doubleTapSeekState.isForward
                    )
                }
            }

            // Bottom transport and centered action buttons with comfortable inset.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .graphicsLayer {
                        alpha = hudProgress
                        translationY = 48f * (1f - hudProgress)
                    }
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                Color(0x44000000),
                                Color(0x99000000)
                            )
                        )
                    )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, top = 8.dp)
                        .padding(bottom = if (isLandscape) 18.dp else 26.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatTimestamp(state.positionMs),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        SleekSeekBar(
                            positionMs = state.positionMs,
                            durationMs = state.durationMs,
                            onSeek = onSeek,
                            onScrubbingChange = onScrubbingChange,
                            onPreviewSeek = onPreviewSeek,
                            modifier = Modifier.weight(1f)
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Text(
                            text = formatTimestamp(state.durationMs),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )

                        if (!isLandscape) {
                            Spacer(modifier = Modifier.width(6.dp))
                            IconButton(
                                onClick = onToggleOrientation,
                                modifier = Modifier
                                    .size(34.dp)
                                    .testTag("fullscreen_toggle_button")
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_fullscreen_enter),
                                    contentDescription = "Enter Fullscreen",
                                    tint = Color.White,
                                    modifier = Modifier.size(23.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val compactButtons = maxWidth < 380.dp
                        Row(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PlayerActionButton(
                                iconRes = R.drawable.ic_audio,
                                label = "Audio",
                                contentDescription = "Audio Track",
                                onClick = { onSetActiveMenu("audio") },
                                compact = compactButtons,
                                modifier = Modifier.testTag("audio_menu_button")
                            )
                            PlayerActionButton(
                                iconRes = R.drawable.ic_captions,
                                label = "Subtitles",
                                contentDescription = "Subtitle Track",
                                onClick = { onSetActiveMenu("subtitle") },
                                isActive = state.selectedSubtitleTrackId != -1,
                                compact = compactButtons,
                                modifier = Modifier.testTag("subtitle_menu_button")
                            )
                            PlayerActionButton(
                                iconRes = R.drawable.ic_speed,
                                label = speedButtonLabel(state.speed),
                                contentDescription = "Playback Speed",
                                onClick = { onSetActiveMenu("speed") },
                                isActive = state.speed != 1.0f,
                                compact = compactButtons,
                                modifier = Modifier.testTag("speed_button")
                            )
                            PlayerActionButton(
                                iconRes = R.drawable.ic_aspect_ratio,
                                label = state.aspectRatioMode.label,
                                contentDescription = "Aspect Ratio",
                                onClick = {
                                    val modes = AspectRatioMode.values()
                                    val nextIndex = (state.aspectRatioMode.ordinal + 1) % modes.size
                                    val nextMode = modes[nextIndex]
                                    onCycleAspectRatio()
                                    aspectToastText = nextMode.label
                                    coroutineScope.launch {
                                        delay(1200)
                                        if (aspectToastText == nextMode.label) {
                                            aspectToastText = null
                                        }
                                    }
                                },
                                onLongClick = { onSetActiveMenu("aspect") },
                                isActive = state.aspectRatioMode != AspectRatioMode.FIT,
                                compact = compactButtons,
                                modifier = Modifier.testTag("aspect_ratio_button")
                            )
                        }
                    }
                }
            }
        }

        // Gesture slide-seek overlay is shown at the bottom only when HUD is hidden
        if (state.slideSeekState.isActive && activeMenu == null && !isHudVisible) {
            SlideSeekFloatingOverlay(
                slideState = state.slideSeekState,
                currentPosMs = state.positionMs,
                durationMs = state.durationMs,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
            )
        }

        // Side Menus with soft gradient shadow background, pure transparent panel, animated slide in/out
        AnimatedVisibility(
            visible = activeMenu != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            // Soft gradient shadow extending past the menu with uniform opacity over the entire menu area
            val scrimBrush = if (isLandscape) {
                Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0.0f to Color.Transparent,
                        0.46f to Color.Transparent,
                        0.58f to Color(0x99000000),
                        1.0f to Color(0x99000000)
                    )
                )
            } else {
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to Color.Transparent,
                        0.20f to Color.Transparent,
                        0.28f to Color(0x99000000),
                        1.0f to Color(0x99000000)
                    )
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(scrimBrush)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onSetActiveMenu(null) }
            )
        }

        AnimatedVisibility(
            visible = activeMenu != null,
            enter = slideInHorizontally(
                animationSpec = tween(220, easing = FastOutSlowInEasing),
                initialOffsetX = { it }
            ),
            exit = slideOutHorizontally(
                animationSpec = tween(220, easing = FastOutSlowInEasing),
                targetOffsetX = { it }
            ),
            modifier = Modifier.align(if (isLandscape) Alignment.CenterEnd else Alignment.BottomCenter)
        ) {
            val menuModifier = if (isLandscape) {
                Modifier
                    .width(340.dp)
                    .fillMaxHeight()
            } else {
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.72f)
            }

            Box(
                modifier = menuModifier
                    .background(Color.Transparent)
                    .pointerInput(Unit) {
                        detectTapGestures { }
                    }
                    .testTag("side_menu_panel")
            ) {
                when (activeMenu) {
                    "subtitle" -> SubtitleSideMenu(
                        state = state,
                        onSelectSubtitle = { trackId ->
                            onSelectSubtitle(trackId)
                            onSetActiveMenu(null)
                        },
                        onSubDelayAdjust = onSubDelayAdjust,
                        onPickSubtitle = {
                            onPickSubtitle()
                            onSetActiveMenu(null)
                        }
                    )
                    "audio" -> AudioSideMenu(
                        state = state,
                        onSelectAudio = { trackId ->
                            onSelectAudio(trackId)
                            onSetActiveMenu(null)
                        },
                        onAudioDelayAdjust = onAudioDelayAdjust,
                        onPickAudio = {
                            onPickAudio()
                            onSetActiveMenu(null)
                        }
                    )
                    "aspect" -> AspectRatioSideMenu(
                        currentMode = state.aspectRatioMode,
                        onSelectAspectRatio = { mode ->
                            onSelectAspectRatio(mode)
                            onSetActiveMenu(null)
                        }
                    )
                    "speed" -> SpeedSideMenu(
                        currentSpeed = state.speed,
                        onSetSpeed = { speed ->
                            onSetSpeed(speed)
                            onSetActiveMenu(null)
                        }
                    )
                    "chapter" -> ChapterSideMenu(
                        chapters = state.chapters,
                        currentPositionMs = state.positionMs,
                        onSelectChapter = { timeMs ->
                            onSelectChapter(timeMs)
                            onSetActiveMenu(null)
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayerActionButton(
    iconRes: Int,
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    compact: Boolean = false,
    isActive: Boolean = false
) {
    val contentColor = if (isActive) Color(0xFF64A8FF) else Color.White
    Box(
        modifier = modifier
            .height(44.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = if (compact) 6.dp else 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = contentDescription,
                tint = contentColor,
                modifier = Modifier.size(if (compact) 16.dp else 19.dp)
            )
            Spacer(modifier = Modifier.width(if (compact) 4.dp else 5.dp))
            Text(
                text = label,
                color = contentColor,
                fontSize = if (compact) 10.sp else 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CenterSeekButton(
    isForward: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    pulseActive: Boolean = false
) {
    val contentAlpha by animateFloatAsState(
        targetValue = if (pulseActive) 0f else 1f,
        animationSpec = tween(160),
        label = if (isForward) "forward_pulse_crossfade" else "rewind_pulse_crossfade"
    )
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .graphicsLayer { alpha = contentAlpha }
            .size(64.dp)
            .testTag(if (isForward) "forward_10_button" else "rewind_10_button")
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Replay,
                contentDescription = if (isForward) "Forward 10 seconds" else "Rewind 10 seconds",
                tint = if (enabled) Color.White else Color(0x55FFFFFF),
                modifier = Modifier
                    .size(36.dp)
                    .graphicsLayer { scaleX = if (isForward) -1f else 1f }
            )
            Text(
                text = "10 sec",
                color = if (enabled) Color(0xEEFFFFFF) else Color(0x55FFFFFF),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 11.sp
            )
        }
    }
}

private fun speedButtonLabel(speed: Float): String {
    if (kotlin.math.abs(speed - 1.0f) < 0.001f) return "Speed"
    val value = String.format(Locale.US, "%.2f", speed)
        .trimEnd('0')
        .trimEnd('.')
    return "${value}×"
}

@Composable
private fun PlayerTitle(title: String) {
    // Never derive styling from TextLayoutResult here: making the second line
    // smaller changes the first line break, which then changes the styling
    // again and causes visible title flicker. Split once from the source text.
    val (firstLine, secondLine) = remember(title) { splitPlayerTitle(title) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = firstLine,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 19.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        if (!secondLine.isNullOrEmpty()) {
            Text(
                text = secondLine,
                color = Color(0x88FFFFFF),
                fontSize = 10.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private fun splitPlayerTitle(title: String): Pair<String, String?> {
    val source = title.trim()
    if (source.isEmpty()) return "Video" to null

    val explicitBreak = source.indexOf('\n')
    if (explicitBreak >= 0) {
        return source.substring(0, explicitBreak).trim() to
            source.substring(explicitBreak + 1).trim().takeIf { it.isNotEmpty() }
    }
    if (source.length <= 26) return source to null

    val maximumFirstLine = 24
    val minimumFirstLine = 12
    val lastWordBreak = source.lastIndexOf(' ', maximumFirstLine)
    val nextWordBreak = source.indexOf(' ', maximumFirstLine)
    val breakAt = when {
        lastWordBreak >= minimumFirstLine -> lastWordBreak
        nextWordBreak in maximumFirstLine until source.length - 6 -> nextWordBreak
        else -> maximumFirstLine
    }

    val first = source.substring(0, breakAt).trim()
    val second = source.substring(breakAt).trim()
    return first to second.takeIf { it.isNotEmpty() }
}

@Composable
fun SlideSeekFloatingOverlay(
    slideState: SlideSeekState,
    currentPosMs: Long,
    durationMs: Long,
    modifier: Modifier = Modifier
) {
    val fraction = slideState.seekFraction.coerceIn(0f, 1f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color(0x99000000))
                )
            )
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatTimestamp(currentPosMs),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.width(8.dp))

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .height(24.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                val trackWidthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                val density = androidx.compose.ui.platform.LocalDensity.current
                val thumbRadiusPx = with(density) { 7.dp.toPx() }
                val thumbCenterPx = (fraction * trackWidthPx).coerceIn(thumbRadiusPx, trackWidthPx - thumbRadiusPx)
                val pillWidthPx = with(density) { 64.dp.toPx() }
                val pillOffsetPx = (thumbCenterPx - pillWidthPx / 2f).coerceIn(0f, trackWidthPx - pillWidthPx)

                // Background Track
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(Color(0x55FFFFFF))
                )

                // Active Played Track
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(3.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(Color(0xFF2979FF))
                )

                // YouTube style circular thumb moving with slide
                Box(
                    modifier = Modifier
                        .offset { IntOffset((thumbCenterPx - thumbRadiusPx).toInt(), 0) }
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2979FF))
                )

                // Floating timestamp badge strictly centered above the circle thumb
                Box(
                    modifier = Modifier
                        .offset { IntOffset(pillOffsetPx.toInt(), -with(density) { 34.dp.roundToPx() }) }
                        .background(Color(0xDD0D0E12), RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = formatTimestamp(slideState.targetMs),
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = formatTimestamp(durationMs),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun SleekSeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    onScrubbingChange: (Boolean) -> Unit,
    onPreviewSeek: (Long, Float, Boolean) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf(0f) }

    val safeDuration = durationMs.coerceAtLeast(1L)
    val displayFraction = if (isDragging) dragFraction else (positionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)

    BoxWithConstraints(
        modifier = modifier
            .height(28.dp)
            .pointerInput(safeDuration) {
                detectTapGestures(
                    onTap = { offset ->
                        val f = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        val target = (f * safeDuration).toLong()
                        onPreviewSeek(target, f, true)
                        onSeek(target)
                        onPreviewSeek(target, f, false)
                    }
                )
            }
            .pointerInput(safeDuration) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        dragFraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        onScrubbingChange(true)
                        onPreviewSeek((dragFraction * safeDuration).toLong(), dragFraction, true)
                    },
                    onDragEnd = {
                        isDragging = false
                        val target = (dragFraction * safeDuration).toLong()
                        onPreviewSeek(target, dragFraction, false)
                        onScrubbingChange(false)
                        onSeek(target)
                    },
                    onDragCancel = {
                        isDragging = false
                        val target = (dragFraction * safeDuration).toLong()
                        onPreviewSeek(target, dragFraction, false)
                        onScrubbingChange(false)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        dragFraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                        onPreviewSeek((dragFraction * safeDuration).toLong(), dragFraction, true)
                    }
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)

        // Background Track
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(1.5.dp))
                .background(Color(0x55FFFFFF))
        )

        // Active Track
        Box(
            modifier = Modifier
                .fillMaxWidth(displayFraction)
                .height(3.dp)
                .clip(RoundedCornerShape(1.5.dp))
                .background(Color(0xFF2979FF))
        )

        // Thumb circle
        val density = androidx.compose.ui.platform.LocalDensity.current
        val thumbRadiusPx = with(density) { 6.5.dp.toPx() }
        val thumbCenterPx = (displayFraction * widthPx).coerceIn(thumbRadiusPx, widthPx - thumbRadiusPx)
        val thumbOffsetPx = thumbCenterPx - thumbRadiusPx

        Box(
            modifier = Modifier
                .offset { IntOffset(thumbOffsetPx.toInt(), 0) }
                .size(13.dp)
                .clip(CircleShape)
                .background(Color(0xFF2979FF))
        )

        // Floating timestamp badge strictly centered above the circle thumb during drag
        if (isDragging) {
            val pillWidthPx = with(density) { 64.dp.toPx() }
            val pillOffsetPx = (thumbCenterPx - pillWidthPx / 2f).coerceIn(0f, widthPx - pillWidthPx)
            Box(
                modifier = Modifier
                    .offset { IntOffset(pillOffsetPx.toInt(), -with(density) { 34.dp.roundToPx() }) }
                    .background(Color(0xDD0D0E12), RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = formatTimestamp((displayFraction * safeDuration).toLong()),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun ClassicSeekPulseOverlay(
    state: DoubleTapSeekState,
    modifier: Modifier = Modifier
) {
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(state.isVisible, state.isForward, state.seconds) {
        if (state.isVisible) {
            pulse.snapTo(0f)
            pulse.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing)
            )
        }
    }

    val direction = if (state.isForward) 1f else -1f
    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = state.isVisible,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = if (state.isForward) 96.dp else (-96).dp),
            enter = fadeIn(animationSpec = tween(80)) +
                scaleIn(initialScale = 0.86f, animationSpec = tween(100)),
            exit = fadeOut(animationSpec = tween(160)) +
                scaleOut(targetScale = 1.06f, animationSpec = tween(160))
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier.size(96.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val progress = pulse.value
                        drawCircle(
                            color = Color(0x38000000),
                            radius = 30.dp.toPx()
                        )
                        if (progress < 1f) {
                            drawCircle(
                                color = Color(0xFF64A8FF).copy(alpha = (1f - progress) * 0.8f),
                                radius = 30.dp.toPx() + (14.dp.toPx() * progress),
                                style = Stroke(width = 2.dp.toPx())
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.Replay,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .size(48.dp)
                            .graphicsLayer {
                                val wave = sin(pulse.value * PI).toFloat()
                                val scale = 1f + (0.14f * wave)
                                scaleX = -direction * scale
                                scaleY = scale
                                rotationZ = direction * 24f * wave
                            }
                    )
                }
                Text(
                    text = if (state.isForward) "+${state.seconds}s" else "−${state.seconds}s",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.graphicsLayer {
                        translationX = direction * 18f * pulse.value
                        alpha = 1f - (0.2f * pulse.value)
                    }
                )
            }
        }
    }
}

@Composable
fun VolumeGesturePill(
    text: String,
    level: Int,
    isBoost: Boolean,
    isLandscape: Boolean
) {
    val barColorBrush = Brush.verticalGradient(
        0.0f to Color(0xFFFF5722),
        0.45f to Color(0xFFFF7043),
        0.55f to Color(0xFF2979FF),
        1.0f to Color(0xFF1E88E5)
    )

    if (!isLandscape) {
        // Portrait mode: Horizontal pill at the top
        Box(
            modifier = Modifier
                .background(Color(0xDD0D0E12), RoundedCornerShape(20.dp))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_audio),
                    contentDescription = null,
                    tint = if (isBoost) Color(0xFFFF5722) else Color(0xFF2979FF),
                    modifier = Modifier.size(20.dp)
                )

                Spacer(modifier = Modifier.width(10.dp))

                Box(
                    modifier = Modifier
                        .width(90.dp)
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0x44FFFFFF))
                ) {
                    val fillRatio = (level.toFloat() / 30f).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fillRatio)
                            .background(if (isBoost) Color(0xFFFF7043) else Color(0xFF2979FF))
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Text(
                    text = text,
                    color = if (isBoost) Color(0xFFFFAB91) else Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(36.dp),
                    textAlign = TextAlign.End
                )
            }
        }
    } else {
        // Landscape mode: Vertical pill on side with fixed compact width
        Box(
            modifier = Modifier
                .width(44.dp)
                .background(Color(0xDD0D0E12), RoundedCornerShape(12.dp))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(12.dp))
                .padding(vertical = 12.dp, horizontal = 4.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_audio),
                    contentDescription = null,
                    tint = if (isBoost) Color(0xFFFF5722) else Color(0xFF2979FF),
                    modifier = Modifier.size(20.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .width(5.dp)
                        .height(85.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0x44FFFFFF))
                ) {
                    val fillRatio = (level.toFloat() / 30f).coerceIn(0f, 1f)
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val barHeight = size.height * fillRatio
                        drawRect(
                            brush = barColorBrush,
                            topLeft = Offset(0f, size.height - barHeight),
                            size = Size(size.width, barHeight)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = text,
                    color = if (isBoost) Color(0xFFFFAB91) else Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(38.dp),
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun BrightnessGesturePill(
    text: String,
    progress: Float,
    isLandscape: Boolean
) {
    if (!isLandscape) {
        // Portrait mode: Horizontal pill at the top
        Box(
            modifier = Modifier
                .background(Color(0xDD0D0E12), RoundedCornerShape(20.dp))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.BrightnessMedium,
                    contentDescription = null,
                    tint = Color(0xFF2979FF),
                    modifier = Modifier.size(20.dp)
                )

                Spacer(modifier = Modifier.width(10.dp))

                Box(
                    modifier = Modifier
                        .width(90.dp)
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0x44FFFFFF))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .background(Color(0xFF2979FF))
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Text(
                    text = text,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(36.dp),
                    textAlign = TextAlign.End
                )
            }
        }
    } else {
        // Landscape mode: Vertical pill on side with fixed compact width
        Box(
            modifier = Modifier
                .width(44.dp)
                .background(Color(0xDD0D0E12), RoundedCornerShape(12.dp))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(12.dp))
                .padding(vertical = 12.dp, horizontal = 4.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.BrightnessMedium,
                    contentDescription = null,
                    tint = Color(0xFF2979FF),
                    modifier = Modifier.size(20.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .width(5.dp)
                        .height(85.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0x44FFFFFF))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(progress.coerceIn(0f, 1f))
                            .align(Alignment.BottomCenter)
                            .background(Color(0xFF2979FF))
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = text,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(38.dp),
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun SubtitleSideMenu(
    state: PlaybackState,
    onSelectSubtitle: (Int) -> Unit,
    onSubDelayAdjust: (Double) -> Unit,
    onPickSubtitle: () -> Unit
) {
    val subTracks = state.tracks.filter { it.type == TrackType.SUBTITLE }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 16.dp, start = 16.dp, end = 16.dp, bottom = 4.dp)
    ) {
        Text(
            text = "Subtitle",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(10.dp))

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    val isNoneSelected = state.selectedSubtitleTrackId == -1
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectSubtitle(-1) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .border(1.5.dp, if (isNoneSelected) Color(0xFF2979FF) else Color(0x88FFFFFF), CircleShape)
                                .background(Color.Transparent, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isNoneSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(Color(0xFF2979FF), CircleShape)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Disable Subtitles",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                items(subTracks) { track ->
                    val isSelected = track.id == state.selectedSubtitleTrackId || track.isSelected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectSubtitle(track.id) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .border(1.5.dp, if (isSelected) Color(0xFF2979FF) else Color(0x88FFFFFF), CircleShape)
                                .background(Color.Transparent, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(Color(0xFF2979FF), CircleShape)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = track.title,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (subTracks.isEmpty()) {
                    item {
                        Text(
                            text = "No internal subtitles found",
                            color = Color(0x99FFFFFF),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0x33FFFFFF))
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onPickSubtitle() }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = "Open external subtitle...", color = Color.White, fontSize = 14.sp)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Synchronization", color = Color.White, fontSize = 14.sp)

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { onSubDelayAdjust(-0.1) },
                    modifier = Modifier
                        .size(32.dp)
                        .background(Color(0x33FFFFFF), CircleShape)
                ) {
                    Icon(Icons.Default.Remove, contentDescription = "Decrease Delay", tint = Color.White, modifier = Modifier.size(16.dp))
                }

                Text(
                    text = String.format(Locale.US, "%+.1fs", state.subDelaySec),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(Color(0x44000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )

                IconButton(
                    onClick = { onSubDelayAdjust(0.1) },
                    modifier = Modifier
                        .size(32.dp)
                        .background(Color(0x33FFFFFF), CircleShape)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Increase Delay", tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
fun AudioSideMenu(
    state: PlaybackState,
    onSelectAudio: (Int) -> Unit,
    onAudioDelayAdjust: (Double) -> Unit,
    onPickAudio: () -> Unit
) {
    val audioTracks = state.tracks.filter { it.type == TrackType.AUDIO }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 16.dp, start = 16.dp, end = 16.dp, bottom = 4.dp)
    ) {
        Text(
            text = "Audio Track",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(10.dp))

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    val isMuted = state.selectedAudioTrackId == -1
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectAudio(-1) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .border(1.5.dp, if (isMuted) Color(0xFF2979FF) else Color(0x88FFFFFF), CircleShape)
                                .background(Color.Transparent, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isMuted) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(Color(0xFF2979FF), CircleShape)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Disable Audio",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                items(audioTracks) { track ->
                    val isSelected = track.id == state.selectedAudioTrackId || track.isSelected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectAudio(track.id) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .border(1.5.dp, if (isSelected) Color(0xFF2979FF) else Color(0x88FFFFFF), CircleShape)
                                .background(Color.Transparent, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(Color(0xFF2979FF), CircleShape)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = track.title,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (audioTracks.isEmpty()) {
                    item {
                        Text(
                            text = "No internal audio tracks found",
                            color = Color(0x99FFFFFF),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0x33FFFFFF))
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onPickAudio() }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = "Open external audio...", color = Color.White, fontSize = 14.sp)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Synchronization", color = Color.White, fontSize = 14.sp)

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { onAudioDelayAdjust(-0.1) },
                    modifier = Modifier
                        .size(32.dp)
                        .background(Color(0x33FFFFFF), CircleShape)
                ) {
                    Icon(Icons.Default.Remove, contentDescription = "Decrease Delay", tint = Color.White, modifier = Modifier.size(16.dp))
                }

                Text(
                    text = String.format(Locale.US, "%+.1fs", state.audioDelaySec),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(Color(0x44000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )

                IconButton(
                    onClick = { onAudioDelayAdjust(0.1) },
                    modifier = Modifier
                        .size(32.dp)
                        .background(Color(0x33FFFFFF), CircleShape)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Increase Delay", tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
fun AspectRatioSideMenu(
    currentMode: AspectRatioMode,
    onSelectAspectRatio: (AspectRatioMode) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 16.dp, start = 16.dp, end = 16.dp, bottom = 4.dp)
    ) {
        Text(
            text = "Aspect Ratio",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(10.dp))

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(AspectRatioMode.values()) { mode ->
                    val isSelected = mode == currentMode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectAspectRatio(mode) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .border(1.5.dp, if (isSelected) Color(0xFF2979FF) else Color(0x88FFFFFF), CircleShape)
                                .background(Color.Transparent, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(Color(0xFF2979FF), CircleShape)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = mode.label,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SpeedSideMenu(
    currentSpeed: Float,
    onSetSpeed: (Float) -> Unit
) {
    val speeds = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 16.dp, start = 16.dp, end = 16.dp, bottom = 4.dp)
    ) {
        Text(
            text = "Playback Speed",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(10.dp))

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(speeds) { spd ->
                    val isSelected = kotlin.math.abs(spd - currentSpeed) < 0.05f
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSetSpeed(spd) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .border(1.5.dp, if (isSelected) Color(0xFF2979FF) else Color(0x88FFFFFF), CircleShape)
                                .background(Color.Transparent, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(Color(0xFF2979FF), CircleShape)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = if (spd == 1.0f) "1.0x (Normal)" else "${spd}x",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ChapterSideMenu(
    chapters: List<ChapterItem>,
    currentPositionMs: Long,
    onSelectChapter: (Long) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 16.dp, start = 16.dp, end = 16.dp, bottom = 4.dp)
    ) {
        Text(
            text = "Chapters",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(10.dp))

        if (chapters.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No chapters found in this video",
                    color = Color(0x99FFFFFF),
                    fontSize = 14.sp
                )
            }
        } else {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(chapters) { ch ->
                        val isCurrent = currentPositionMs >= ch.startMs && currentPositionMs < ch.endMs
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectChapter(ch.startMs) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = ch.title,
                                    color = if (isCurrent) Color(0xFF2979FF) else Color(0xEEFFFFFF),
                                    fontSize = 14.sp,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${formatTimestamp(ch.startMs)} - ${formatTimestamp(ch.endMs)}",
                                    color = Color(0x88FFFFFF),
                                    fontSize = 12.sp
                                )
                            }

                            if (isCurrent) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Active Chapter",
                                    tint = Color(0xFF2979FF),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

fun formatTimestamp(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%02d:%02d", m, s)
    }
}
