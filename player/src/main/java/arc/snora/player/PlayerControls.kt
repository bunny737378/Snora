package arc.snora.player

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
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
    onCycleAspectRatio: () -> Unit,
    onToggleLock: () -> Unit,
    onToggleOrientation: () -> Unit,
    onSelectSubtitle: (Int) -> Unit,
    onSelectAudio: (Int) -> Unit,
    onSubDelayAdjust: (Double) -> Unit,
    onAudioDelayAdjust: (Double) -> Unit,
    onHwDecChange: (HwDecMode) -> Unit,
    onScrubbingChange: (Boolean) -> Unit,
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
                    .padding(bottom = if (isHudVisible) 110.dp else 40.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(
                    text = state.currentSubtitleText,
                    color = Color.Yellow,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }

        AnimatedVisibility(
            visible = gestureState.isVisible && gestureState.iconType == GestureIconType.BRIGHTNESS,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 24.dp)
        ) {
            VerticalGesturePill(
                icon = Icons.Default.BrightnessMedium,
                text = gestureState.text,
                progress = gestureState.progress
            )
        }

        AnimatedVisibility(
            visible = gestureState.isVisible && gestureState.iconType == GestureIconType.VOLUME,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 24.dp)
        ) {
            VerticalGesturePill(
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                text = gestureState.text,
                progress = gestureState.progress
            )
        }

        AnimatedVisibility(
            visible = gestureState.isVisible && gestureState.iconType == GestureIconType.SEEK,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xCC111318),
                contentColor = Color.White
            ) {
                Text(
                    text = gestureState.text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp)
                )
            }
        }

        if (state.isControlsLocked) {
            AnimatedVisibility(
                visible = isHudVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(24.dp)
            ) {
                IconButton(
                    onClick = onToggleLock,
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color(0x88000000), CircleShape)
                        .testTag("unlock_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Unlock Controls",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            return
        }

        AnimatedVisibility(
            visible = isHudVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x55000000))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(Color(0x88000000))
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("player_back_button")
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

                    if (state.chapters.isNotEmpty()) {
                        IconButton(
                            onClick = { activeMenu = "chapters" },
                            modifier = Modifier.testTag("chapters_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bookmark,
                                contentDescription = "Chapters",
                                tint = Color.White
                            )
                        }
                    }

                    IconButton(
                        onClick = { activeMenu = "tracks" },
                        modifier = Modifier.testTag("tracks_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Subtitles,
                            contentDescription = "Tracks",
                            tint = Color.White
                        )
                    }

                    IconButton(
                        onClick = onCycleAspectRatio,
                        modifier = Modifier.testTag("aspect_ratio_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AspectRatio,
                            contentDescription = "Aspect Ratio",
                            tint = Color.White
                        )
                    }

                    IconButton(
                        onClick = { activeMenu = "info" },
                        modifier = Modifier.testTag("video_info_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Information",
                            tint = Color.White
                        )
                    }

                    IconButton(
                        onClick = onToggleLock,
                        modifier = Modifier.testTag("lock_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.LockOpen,
                            contentDescription = "Lock",
                            tint = Color.White
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
                            .size(64.dp)
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
                            modifier = Modifier.size(52.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(Color(0x88000000))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
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
                            TextButton(
                                onClick = { activeMenu = "speed" },
                                modifier = Modifier.testTag("speed_button")
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
                                modifier = Modifier.testTag("orientation_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ScreenRotation,
                                    contentDescription = "Rotate Screen",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
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
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = Color.Gray
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("player_timeline_slider")
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = activeMenu != null,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                color = Color(0xEE1A1C22),
                contentColor = Color.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("player_menu_surface")
            ) {
                activeMenu?.let { menu ->
                    MenuContent(
                        menuType = menu,
                        state = state,
                        onClose = { activeMenu = null },
                        onSetSpeed = onSetSpeed,
                        onSelectSubtitle = onSelectSubtitle,
                        onSelectAudio = onSelectAudio,
                        onSubDelayAdjust = onSubDelayAdjust,
                        onAudioDelayAdjust = onAudioDelayAdjust,
                        onHwDecChange = onHwDecChange,
                        onSeek = onSeek
                    )
                }
            }
        }
    }
}

@Composable
fun VerticalGesturePill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    progress: Float
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color(0xCC111318),
        contentColor = Color.White
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 16.dp, horizontal = 12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(100.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.Gray.copy(alpha = 0.4f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(progress.coerceIn(0f, 1f))
                        .align(Alignment.BottomCenter)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = text,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

@Composable
fun MenuContent(
    menuType: String,
    state: PlaybackState,
    onClose: () -> Unit,
    onSetSpeed: (Float) -> Unit,
    onSelectSubtitle: (Int) -> Unit,
    onSelectAudio: (Int) -> Unit,
    onSubDelayAdjust: (Double) -> Unit,
    onAudioDelayAdjust: (Double) -> Unit,
    onHwDecChange: (HwDecMode) -> Unit,
    onSeek: (Long) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val title = when (menuType) {
                "speed" -> "Playback Speed"
                "tracks" -> "Audio & Subtitles"
                "chapters" -> "Chapters"
                "info" -> "Decoder & Info"
                else -> ""
            }
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        when (menuType) {
            "speed" -> {
                val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
                speeds.forEach { sp ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSetSpeed(sp)
                                onClose()
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${sp}x",
                            fontSize = 15.sp,
                            color = if (state.speed == sp) MaterialTheme.colorScheme.primary else Color.White,
                            fontWeight = if (state.speed == sp) FontWeight.Bold else FontWeight.Normal
                        )
                        if (state.speed == sp) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
            "chapters" -> {
                LazyColumn(modifier = Modifier.height(240.dp)) {
                    items(state.chapters) { chapter ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSeek(chapter.startMs)
                                    onClose()
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = chapter.title,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "${formatTimestamp(chapter.startMs)} - ${formatTimestamp(chapter.endMs)}",
                                    fontSize = 12.sp,
                                    color = Color.LightGray
                                )
                            }
                        }
                    }
                }
            }
            "tracks" -> {
                var tabIndex by remember { mutableIntStateOf(0) }
                TabRow(
                    selectedTabIndex = tabIndex,
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Tab(
                        selected = tabIndex == 0,
                        onClick = { tabIndex = 0 },
                        text = { Text("Audio", color = Color.White) }
                    )
                    Tab(
                        selected = tabIndex == 1,
                        onClick = { tabIndex = 1 },
                        text = { Text("Subtitles", color = Color.White) }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (tabIndex == 0) {
                    val audioTracks = state.tracks.filter { it.type == TrackType.AUDIO }
                    LazyColumn(modifier = Modifier.height(200.dp)) {
                        items(audioTracks) { track ->
                            val isSelected = track.isSelected || (track.id == state.selectedAudioTrackId)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSelectAudio(track.id)
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = track.title,
                                    fontSize = 14.sp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                } else {
                    val subTracks = state.tracks.filter { it.type == TrackType.SUBTITLE }
                    LazyColumn(modifier = Modifier.height(200.dp)) {
                        item {
                            val isOff = subTracks.none { it.isSelected } && state.selectedSubtitleTrackId == -1
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectSubtitle(-1) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Off",
                                    fontSize = 14.sp,
                                    color = if (isOff) MaterialTheme.colorScheme.primary else Color.White
                                )
                                if (isOff) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                        items(subTracks) { track ->
                            val isSelected = track.isSelected || (track.id == state.selectedSubtitleTrackId)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectSubtitle(track.id) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = track.title,
                                    fontSize = 14.sp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White
                                )
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            "info" -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Decoder Mode:", fontWeight = FontWeight.Bold, color = Color.White)
                    HwDecMode.values().forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onHwDecChange(mode) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (state.hwDecMode == mode),
                                onClick = { onHwDecChange(mode) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(mode.label, fontSize = 13.sp, color = Color.White)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Native Engine: ${FFmpegBridge.getConfigurationInfo()}",
                        fontSize = 12.sp,
                        color = Color.LightGray
                    )
                }
            }
        }
    }
}

fun formatTimestamp(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
