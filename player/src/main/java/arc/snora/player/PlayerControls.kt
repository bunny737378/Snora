package arc.snora.player

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlayerControls(
    state: PlaybackState,
    isHudVisible: Boolean,
    gestureState: GestureOverlayState,
    title: String,
    onBack: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekRelative: (Double) -> Unit,
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
    onPickSubtitle: () -> Unit,
    onPickAudio: () -> Unit,
    onToggleOrientation: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var activeMenu by remember { mutableStateOf<String?>(null) }
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    Box(modifier = modifier.fillMaxSize()) {

        // Subtitle overlay: Only visible when subtitles are NOT disabled (-1)
        if (state.selectedSubtitleTrackId != -1 && state.currentSubtitleText.isNotBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (isHudVisible || state.slideSeekState.isActive) 100.dp else 24.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = state.currentSubtitleText,
                    color = Color.Yellow,
                    fontSize = 17.sp,
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

        // Locked state
        if (state.isControlsLocked) {
            if (isHudVisible) {
                IconButton(
                    onClick = onToggleLock,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(20.dp)
                        .size(46.dp)
                        .background(Color(0x99000000), CircleShape)
                        .testTag("unlock_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Unlock Controls",
                        tint = Color(0xFF2979FF)
                    )
                }
            }
            return@Box
        }

        // Slide-to-Seek Real-time Floating Overlay & Timeline Thumb
        if (state.slideSeekState.isActive) {
            SlideSeekFloatingOverlay(
                slideState = state.slideSeekState,
                durationMs = state.durationMs,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Normal HUD Controls
        if (isHudVisible && activeMenu == null && !state.slideSeekState.isActive) {

            // Top Bar with soft shadow gradient
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
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

                // Title: Up to 2 lines in horizontal mode, dull color on 2nd line
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .widthIn(max = if (isLandscape) 360.dp else 220.dp)
                        .padding(horizontal = 6.dp)
                ) {
                    val splitTitle = title.split(" - ", " / ", " | ")
                    if (splitTitle.size > 1 && isLandscape) {
                        Text(
                            text = splitTitle[0].trim(),
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = splitTitle.drop(1).joinToString(" - ").trim(),
                            color = Color(0x99FFFFFF),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Text(
                            text = title,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = if (isLandscape) 2 else 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

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

                // Audio Track Menu Button
                IconButton(
                    onClick = { activeMenu = "audio" },
                    modifier = Modifier.size(36.dp).testTag("audio_menu_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Audio Track",
                        tint = Color.White,
                        modifier = Modifier.size(21.dp)
                    )
                }

                // Subtitle Menu Button
                IconButton(
                    onClick = { activeMenu = "subtitle" },
                    modifier = Modifier.size(36.dp).testTag("subtitle_menu_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Subtitles,
                        contentDescription = "Subtitle Track",
                        tint = Color.White,
                        modifier = Modifier.size(21.dp)
                    )
                }

                // Chapter Menu Button
                if (state.chapters.isNotEmpty()) {
                    IconButton(
                        onClick = { activeMenu = "chapter" },
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

                // Aspect Ratio Button: Single click cycles, Hold 1 sec opens menu
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .combinedClickable(
                            onClick = { onCycleAspectRatio() },
                            onLongClick = { activeMenu = "aspect" }
                        )
                        .testTag("aspect_ratio_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AspectRatio,
                        contentDescription = "Aspect Ratio",
                        tint = Color.White,
                        modifier = Modifier.size(21.dp)
                    )
                }

                // Lock Controls Button
                IconButton(
                    onClick = onToggleLock,
                    modifier = Modifier.size(36.dp).testTag("lock_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.LockOpen,
                        contentDescription = "Lock Controls",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Center Play / Pause button (No dark shadow circle behind it)
            Box(
                modifier = Modifier.align(Alignment.Center),
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier.size(60.dp).testTag("play_pause_button")
                ) {
                    Icon(
                        imageVector = if (state.status == PlaybackStatus.PLAYING) {
                            Icons.Default.Pause
                        } else {
                            Icons.Default.PlayArrow
                        },
                        contentDescription = "Play or Pause",
                        tint = Color.White,
                        modifier = Modifier.size(46.dp)
                    )
                }
            }

            // Bottom Bar with soft shadow gradient
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                Color(0x44000000),
                                Color(0x99000000)
                            )
                        )
                    )
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                var isScrubbingLocal by remember { mutableStateOf(false) }
                var scrubPosition by remember { mutableStateOf(0f) }

                val effectivePosition = if (isScrubbingLocal) {
                    scrubPosition.toLong()
                } else {
                    state.positionMs
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${formatTimestamp(effectivePosition)} / ${formatTimestamp(state.durationMs)}",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Speed button
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { activeMenu = "speed" }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .testTag("speed_button")
                        ) {
                            Text(
                                text = "${state.speed}x",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        // Fullscreen enter/exit toggle button
                        IconButton(
                            onClick = onToggleOrientation,
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("fullscreen_toggle_button")
                        ) {
                            Icon(
                                imageVector = if (isLandscape) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                contentDescription = if (isLandscape) "Exit Fullscreen" else "Enter Fullscreen",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                Slider(
                    value = effectivePosition.toFloat().coerceIn(0f, state.durationMs.toFloat().coerceAtLeast(1f)),
                    onValueChange = { pos ->
                        isScrubbingLocal = true
                        scrubPosition = pos
                        onScrubbingChange(true)
                    },
                    onValueChangeFinished = {
                        isScrubbingLocal = false
                        onScrubbingChange(false)
                        onSeek(scrubPosition.toLong())
                    },
                    valueRange = 0f..state.durationMs.toFloat().coerceAtLeast(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF2979FF),
                        activeTrackColor = Color(0xFF2979FF),
                        inactiveTrackColor = Color(0x55FFFFFF)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .testTag("player_timeline_slider")
                )
            }
        }

        // Side Menus with soft gradient shadow background, no black scrim on video, no click-through
        if (activeMenu != null) {
            // Transparent backdrop: clicking outside closes the menu, doesn't darken the video
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { activeMenu = null }
            )

            val menuModifier = if (isLandscape) {
                Modifier
                    .width(360.dp)
                    .fillMaxHeight()
                    .align(Alignment.CenterEnd)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Transparent,
                                Color(0x5508090C),
                                Color(0xDD08090C),
                                Color(0xFA08090C)
                            )
                        )
                    )
            } else {
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                Color(0x5508090C),
                                Color(0xEE08090C),
                                Color(0xFA08090C)
                            )
                        ),
                        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                    )
            }

            // Consumes touches so underlying video / player is never clicked accidentally
            Box(
                modifier = menuModifier
                    .pointerInput(Unit) {
                        detectTapGestures { }
                    }
                    .testTag("side_menu_panel")
            ) {
                when (activeMenu) {
                    "subtitle" -> SubtitleSideMenu(
                        state = state,
                        onSelectSubtitle = onSelectSubtitle,
                        onSubDelayAdjust = onSubDelayAdjust,
                        onPickSubtitle = onPickSubtitle
                    )
                    "audio" -> AudioSideMenu(
                        state = state,
                        onSelectAudio = onSelectAudio,
                        onAudioDelayAdjust = onAudioDelayAdjust,
                        onPickAudio = onPickAudio
                    )
                    "aspect" -> AspectRatioSideMenu(
                        currentMode = state.aspectRatioMode,
                        onSelectAspectRatio = onSelectAspectRatio
                    )
                    "speed" -> SpeedSideMenu(
                        currentSpeed = state.speed,
                        onSetSpeed = onSetSpeed
                    )
                    "chapter" -> ChapterSideMenu(
                        chapters = state.chapters,
                        currentPositionMs = state.positionMs,
                        onSelectChapter = { timeMs ->
                            onSelectChapter(timeMs)
                            activeMenu = null
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun SlideSeekFloatingOverlay(
    slideState: SlideSeekState,
    durationMs: Long,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        val totalWidth = maxWidth
        val fraction = slideState.seekFraction.coerceIn(0f, 1f)

        // Floating 16:9 thumbnail and timestamp box above the thumb
        val cardWidth = 130.dp
        val cardHeight = 73.dp
        val maxOffsetPx = (totalWidth - cardWidth)
        val xOffset = (maxOffsetPx * fraction).coerceIn(0.dp, totalWidth - cardWidth)

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset { IntOffset(xOffset.roundToPx(), 0) }
                .padding(bottom = 60.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 16:9 Thumbnail Box in Crop mode
            Box(
                modifier = Modifier
                    .size(width = cardWidth, height = cardHeight)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xDD0D0E12))
                    .border(1.5.dp, Color(0x66FFFFFF), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (slideState.thumbnailBitmap != null) {
                    Image(
                        bitmap = slideState.thumbnailBitmap.asImageBitmap(),
                        contentDescription = "Seek Frame Thumbnail",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text(
                        text = "Preview...",
                        color = Color(0x99FFFFFF),
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Duration text just below thumbnail box and above thumb
            Box(
                modifier = Modifier
                    .background(Color(0xCC000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "${formatTimestamp(slideState.targetMs)} / ${formatTimestamp(durationMs)}",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Timeline bar at the bottom with thumb circle moving in real time
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0x88000000))
                    )
                )
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                // Background Track
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0x55FFFFFF))
                )

                // Active Played Track
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFF2979FF))
                )

                // YouTube style circular thumb moving with slide
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .align(Alignment.CenterStart),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2979FF))
                            .border(2.dp, Color.White, CircleShape)
                    )
                }
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
                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
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
                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
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
                    tint = Color(0xFFFFD54F),
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
                            .background(Color(0xFFFFD54F))
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
                    tint = Color(0xFFFFD54F),
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
                            .background(Color(0xFFFFD54F))
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
            .padding(20.dp)
    ) {
        Text(
            text = "Subtitle",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(14.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            item {
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
                            .background(
                                if (state.selectedSubtitleTrackId == -1) Color(0xFF2979FF) else Color.Transparent,
                                RoundedCornerShape(3.dp)
                            )
                            .border(1.5.dp, Color(0xFF2979FF), RoundedCornerShape(3.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (state.selectedSubtitleTrackId == -1) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Disable Subtitles",
                        color = if (state.selectedSubtitleTrackId == -1) Color(0xFF2979FF) else Color.White,
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
                            .background(
                                if (isSelected) Color(0xFF2979FF) else Color.Transparent,
                                RoundedCornerShape(3.dp)
                            )
                            .border(1.5.dp, Color(0xFF2979FF), RoundedCornerShape(3.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = track.title,
                        color = if (isSelected) Color(0xFF2979FF) else Color.White,
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

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0x33FFFFFF))
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onPickSubtitle() }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = "Open external subtitle...", color = Color.White, fontSize = 14.sp)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
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
            .padding(20.dp)
    ) {
        Text(
            text = "Audio Track",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(14.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
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
                        color = if (isMuted) Color(0xFF2979FF) else Color.White,
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
                        color = if (isSelected) Color(0xFF2979FF) else Color.White,
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

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0x33FFFFFF))
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onPickAudio() }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = "Open external audio...", color = Color.White, fontSize = 14.sp)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
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
            .padding(20.dp)
    ) {
        Text(
            text = "Aspect Ratio",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(14.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
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
                    Column {
                        Text(
                            text = mode.label,
                            color = if (isSelected) Color(0xFF2979FF) else Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = mode.description,
                            color = Color(0x88FFFFFF),
                            fontSize = 12.sp
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
            .padding(20.dp)
    ) {
        Text(
            text = "Playback Speed",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(14.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
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
                        color = if (isSelected) Color(0xFF2979FF) else Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
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
            .padding(20.dp)
    ) {
        Text(
            text = "Chapters",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(14.dp))

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
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(chapters) { ch ->
                    val isCurrent = currentPositionMs in ch.startMs..ch.endMs
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
                                color = if (isCurrent) Color(0xFF2979FF) else Color.White,
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
