package arc.snora.player

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

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
    onToggleLock: () -> Unit,
    onToggleOrientation: () -> Unit,
    onSelectSubtitle: (Int) -> Unit,
    onSelectAudio: (Int) -> Unit,
    onSubDelayAdjust: (Double) -> Unit,
    onAudioDelayAdjust: (Double) -> Unit,
    onToggleHwDec: () -> Unit,
    onScrubbingChange: (Boolean) -> Unit,
    onPickSubtitle: () -> Unit,
    onPickAudio: () -> Unit,
    modifier: Modifier = Modifier
) {
    var activeMenu by remember { mutableStateOf<String?>(null) }
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    Box(modifier = modifier.fillMaxSize()) {

        if (state.currentSubtitleText.isNotBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (isHudVisible) 95.dp else 24.dp)
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

        if (gestureState.isVisible && !isHudVisible && activeMenu == null) {
            val gestureAlignment = when {
                !isLandscape -> Alignment.TopCenter
                gestureState.iconType == GestureIconType.BRIGHTNESS -> Alignment.CenterEnd
                gestureState.iconType == GestureIconType.VOLUME -> Alignment.CenterStart
                else -> Alignment.Center
            }

            val gesturePadding = when {
                !isLandscape -> Modifier.padding(top = 70.dp)
                gestureState.iconType == GestureIconType.BRIGHTNESS -> Modifier.padding(end = 36.dp)
                gestureState.iconType == GestureIconType.VOLUME -> Modifier.padding(start = 36.dp)
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

        if (isHudVisible && activeMenu == null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Color.Transparent)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(40.dp).testTag("player_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }

                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .border(
                            width = 1.dp,
                            color = if (state.hwDecFailed) Color(0x66FFFFFF) else Color(0xBBFFFFFF),
                            shape = RoundedCornerShape(4.dp)
                        )
                        .clickable(enabled = !state.hwDecFailed) {
                            onToggleHwDec()
                        }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                        .testTag("hw_sw_decoder_button")
                ) {
                    Text(
                        text = if (state.hwDecMode == HwDecMode.AUTO && !state.hwDecFailed) "HW" else "SW",
                        color = if (state.hwDecFailed) Color(0x88FFFFFF) else Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                IconButton(
                    onClick = { activeMenu = "audio" },
                    modifier = Modifier.size(38.dp).testTag("audio_menu_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Audio Track",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }

                IconButton(
                    onClick = { activeMenu = "subtitle" },
                    modifier = Modifier.size(38.dp).testTag("subtitle_menu_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Subtitles,
                        contentDescription = "Subtitle Track",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }

                IconButton(
                    onClick = { activeMenu = "aspect" },
                    modifier = Modifier.size(38.dp).testTag("aspect_ratio_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.AspectRatio,
                        contentDescription = "Aspect Ratio",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }

                IconButton(
                    onClick = { activeMenu = "more" },
                    modifier = Modifier.size(38.dp).testTag("more_options_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More Options",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }

                IconButton(
                    onClick = onToggleLock,
                    modifier = Modifier.size(38.dp).testTag("lock_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.LockOpen,
                        contentDescription = "Lock Controls",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Box(
                modifier = Modifier.align(Alignment.Center),
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier
                        .size(62.dp)
                        .background(Color(0x77000000), CircleShape)
                        .testTag("play_pause_button")
                ) {
                    Icon(
                        imageVector = if (state.status == PlaybackStatus.PLAYING) {
                            Icons.Default.Pause
                        } else {
                            Icons.Default.PlayArrow
                        },
                        contentDescription = "Play or Pause",
                        tint = Color.White,
                        modifier = Modifier.size(38.dp)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color.Transparent)
                    .padding(horizontal = 16.dp, vertical = 4.dp)
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

                        IconButton(
                            onClick = onToggleOrientation,
                            modifier = Modifier.size(36.dp).testTag("orientation_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ScreenRotation,
                                contentDescription = "Rotate Screen",
                                tint = Color.White,
                                modifier = Modifier.size(19.dp)
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
                        inactiveTrackColor = Color(0x66FFFFFF)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .testTag("player_timeline_slider")
                )
            }
        }

        if (activeMenu != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x66000000))
                    .clickable { activeMenu = null }
            )

            val menuModifier = if (isLandscape) {
                Modifier
                    .width(360.dp)
                    .fillMaxHeight()
                    .align(Alignment.CenterEnd)
                    .background(Color(0xF2101216))
            } else {
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Color(0xF2101216),
                        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                    )
            }

            Box(
                modifier = menuModifier
                    .clickable(enabled = false) {}
                    .testTag("side_menu_panel")
            ) {
                when (activeMenu) {
                    "subtitle" -> SubtitleSideMenu(
                        state = state,
                        onClose = { activeMenu = null },
                        onSelectSubtitle = onSelectSubtitle,
                        onSubDelayAdjust = onSubDelayAdjust,
                        onPickSubtitle = onPickSubtitle
                    )
                    "audio" -> AudioSideMenu(
                        state = state,
                        onClose = { activeMenu = null },
                        onSelectAudio = onSelectAudio,
                        onAudioDelayAdjust = onAudioDelayAdjust,
                        onPickAudio = onPickAudio
                    )
                    "aspect" -> AspectRatioSideMenu(
                        currentMode = state.aspectRatioMode,
                        onClose = { activeMenu = null },
                        onSelectAspectRatio = onSelectAspectRatio
                    )
                    "speed" -> SpeedSideMenu(
                        currentSpeed = state.speed,
                        onClose = { activeMenu = null },
                        onSetSpeed = onSetSpeed
                    )
                    "more" -> MoreOptionsSideMenu(
                        state = state,
                        onClose = { activeMenu = null },
                        onOpenMenu = { menu -> activeMenu = menu },
                        onToggleHwDec = onToggleHwDec
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

    Box(
        modifier = Modifier
            .background(Color(0xDD0D0E12), RoundedCornerShape(14.dp))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 14.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = null,
                tint = if (isBoost) Color(0xFFFF5722) else Color(0xFF2979FF),
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .width(7.dp)
                    .height(95.dp)
                    .clip(RoundedCornerShape(4.dp))
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

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = text,
                color = if (isBoost) Color(0xFFFFAB91) else Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun BrightnessGesturePill(
    text: String,
    progress: Float,
    isLandscape: Boolean
) {
    Box(
        modifier = Modifier
            .background(Color(0xDD0D0E12), RoundedCornerShape(14.dp))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 14.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.BrightnessMedium,
                contentDescription = null,
                tint = Color(0xFFFFD54F),
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .width(7.dp)
                    .height(95.dp)
                    .clip(RoundedCornerShape(4.dp))
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

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = text,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun SubtitleSideMenu(
    state: PlaybackState,
    onClose: () -> Unit,
    onSelectSubtitle: (Int) -> Unit,
    onSubDelayAdjust: (Double) -> Unit,
    onPickSubtitle: () -> Unit
) {
    val subTracks = state.tracks.filter { it.type == TrackType.SUBTITLE }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Subtitle",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
        }

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
                        text = "No internal subtitles detected in this video",
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
                    text = String.format(Locale.US, "%.1fs", state.subDelaySec),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp)
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
    onClose: () -> Unit,
    onSelectAudio: (Int) -> Unit,
    onAudioDelayAdjust: (Double) -> Unit,
    onPickAudio: () -> Unit
) {
    val audioTracks = state.tracks.filter { it.type == TrackType.AUDIO }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Audio Track",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
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
                            .background(Color.Transparent, CircleShape)
                            .border(2.dp, if (isSelected) Color(0xFF2979FF) else Color(0x88FFFFFF), CircleShape),
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

            item {
                val isDisableSelected = state.selectedAudioTrackId == -1
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
                            .background(Color.Transparent, CircleShape)
                            .border(2.dp, if (isDisableSelected) Color(0xFF2979FF) else Color(0x88FFFFFF), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isDisableSelected) {
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
                        color = if (isDisableSelected) Color(0xFF2979FF) else Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
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
                    text = String.format(Locale.US, "%.1fs", state.audioDelaySec),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp)
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
    onClose: () -> Unit,
    onSelectAspectRatio: (AspectRatioMode) -> Unit
) {
    val modes = listOf(
        AspectRatioMode.FILL,
        AspectRatioMode.FIT,
        AspectRatioMode.CROP,
        AspectRatioMode.RATIO_16_9,
        AspectRatioMode.RATIO_1_1,
        AspectRatioMode.RATIO_4_3
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Aspect Ratio",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(modes) { mode ->
                val isSelected = mode == currentMode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onSelectAspectRatio(mode)
                            onClose()
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .background(Color.Transparent, CircleShape)
                            .border(2.dp, if (isSelected) Color(0xFF2979FF) else Color(0x88FFFFFF), CircleShape),
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
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = mode.label,
                            color = if (isSelected) Color(0xFF2979FF) else Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = mode.description,
                            color = Color(0x99FFFFFF),
                            fontSize = 11.sp
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
    onClose: () -> Unit,
    onSetSpeed: (Float) -> Unit
) {
    val speeds = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f, 4.0f)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Playback Speed",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(speeds) { sp ->
                val isSelected = (currentSpeed - sp).let { it > -0.05f && it < 0.05f }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onSetSpeed(sp)
                            onClose()
                        }
                        .padding(vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .background(Color.Transparent, CircleShape)
                            .border(2.dp, if (isSelected) Color(0xFF2979FF) else Color(0x88FFFFFF), CircleShape),
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
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        text = "${sp}x",
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
fun MoreOptionsSideMenu(
    state: PlaybackState,
    onClose: () -> Unit,
    onOpenMenu: (String) -> Unit,
    onToggleHwDec: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Options",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            RoundMenuButton(
                icon = Icons.Default.AspectRatio,
                label = "Aspect Ratio",
                onClick = { onOpenMenu("aspect") }
            )
            RoundMenuButton(
                icon = Icons.Default.Speed,
                label = "${state.speed}x",
                onClick = { onOpenMenu("speed") }
            )
            RoundMenuButton(
                icon = Icons.Default.Tune,
                label = if (state.hwDecMode == HwDecMode.AUTO && !state.hwDecFailed) "HW Decoder" else "SW Decoder",
                onClick = onToggleHwDec
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            RoundMenuButton(
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                label = "Audio",
                onClick = { onOpenMenu("audio") }
            )
            RoundMenuButton(
                icon = Icons.Default.Subtitles,
                label = "Subtitle",
                onClick = { onOpenMenu("subtitle") }
            )
            RoundMenuButton(
                icon = Icons.Default.Sync,
                label = "Sync Audio",
                onClick = { onOpenMenu("audio") }
            )
        }
    }
}

@Composable
fun RoundMenuButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(86.dp)
            .clickable { onClick() }
            .padding(vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(Color(0x33FFFFFF), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

fun formatTimestamp(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
