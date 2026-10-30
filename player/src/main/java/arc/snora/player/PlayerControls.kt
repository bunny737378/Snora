package arc.snora.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
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
    modifier: Modifier = Modifier
) {
    var showTrackDialog by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = gestureState.isVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xCC1A1A24),
                contentColor = Color.White
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    when (gestureState.iconType) {
                        GestureIconType.VOLUME -> Icon(
                            imageVector = Icons.Default.VolumeUp,
                            contentDescription = "Volume",
                            modifier = Modifier.size(36.dp)
                        )
                        GestureIconType.BRIGHTNESS -> Icon(
                            imageVector = Icons.Default.BrightnessMedium,
                            contentDescription = "Brightness",
                            modifier = Modifier.size(36.dp)
                        )
                        GestureIconType.SEEK -> Icon(
                            imageVector = Icons.Default.FastForward,
                            contentDescription = "Seek",
                            modifier = Modifier.size(36.dp)
                        )
                        GestureIconType.NONE -> {}
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = gestureState.text,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    if (gestureState.iconType != GestureIconType.SEEK && gestureState.progress > 0f) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { gestureState.progress },
                            modifier = Modifier
                                .width(100.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
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
                    .background(Color(0x77000000))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(Color(0x99000000))
                        .padding(horizontal = 8.dp, vertical = 12.dp),
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
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                    )

                    IconButton(
                        onClick = { showTrackDialog = true },
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
                        onClick = { showInfoDialog = true },
                        modifier = Modifier.testTag("video_info_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Video Information",
                            tint = Color.White
                        )
                    }

                    IconButton(
                        onClick = onToggleLock,
                        modifier = Modifier.testTag("lock_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.LockOpen,
                            contentDescription = "Lock Controls",
                            tint = Color.White
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { onSeekRelative(-10.0) },
                        modifier = Modifier
                            .size(56.dp)
                            .background(Color(0x55000000), CircleShape)
                            .testTag("rewind_10s")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FastRewind,
                            contentDescription = "Rewind 10 Seconds",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    IconButton(
                        onClick = onTogglePlayPause,
                        modifier = Modifier
                            .size(72.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                            .testTag("play_pause_button")
                    ) {
                        Icon(
                            imageVector = if (state.status == PlaybackStatus.PLAYING) {
                                Icons.Default.Pause
                            } else {
                                Icons.Default.PlayArrow
                            },
                            contentDescription = "Play or Pause",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(44.dp)
                        )
                    }

                    IconButton(
                        onClick = { onSeekRelative(10.0) },
                        modifier = Modifier
                            .size(56.dp)
                            .background(Color(0x55000000), CircleShape)
                            .testTag("forward_10s")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FastForward,
                            contentDescription = "Forward 10 Seconds",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(Color(0x99000000))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    var isScrubbing by remember { mutableStateOf(false) }
                    var scrubPosition by remember { mutableStateOf(0f) }

                    val effectivePosition = if (isScrubbing) {
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
                            text = formatTimestamp(effectivePosition),
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )

                        Text(
                            text = formatTimestamp(state.durationMs),
                            color = Color.LightGray,
                            fontSize = 13.sp
                        )
                    }

                    Slider(
                        value = effectivePosition.toFloat().coerceIn(0f, state.durationMs.toFloat().coerceAtLeast(1f)),
                        onValueChange = {
                            isScrubbing = true
                            scrubPosition = it
                        },
                        onValueChangeFinished = {
                            isScrubbing = false
                            onSeek(scrubPosition.toLong())
                        },
                        valueRange = 0f..state.durationMs.toFloat().coerceAtLeast(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = Color.DarkGray
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("player_seekbar")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { showSpeedDialog = true },
                            modifier = Modifier.testTag("speed_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Speed,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${state.speed}x",
                                color = Color.White,
                                fontSize = 13.sp
                            )
                        }

                        Text(
                            text = state.aspectRatioMode.label,
                            color = Color.LightGray,
                            fontSize = 12.sp,
                            modifier = Modifier.clickable { onCycleAspectRatio() }
                        )

                        IconButton(
                            onClick = onToggleOrientation,
                            modifier = Modifier.testTag("orientation_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ScreenRotation,
                                contentDescription = "Rotate Screen",
                                tint = Color.White
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSpeedDialog) {
        AlertDialog(
            onDismissRequest = { showSpeedDialog = false },
            title = { Text("Playback Speed") },
            text = {
                val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
                Column {
                    speeds.forEach { sp ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSetSpeed(sp)
                                    showSpeedDialog = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (state.speed == sp),
                                onClick = {
                                    onSetSpeed(sp)
                                    showSpeedDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("${sp}x")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSpeedDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    if (showInfoDialog) {
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = { Text("Media Information") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Title: ${state.videoInfo.title.ifEmpty { title }}")
                    Text("Resolution: ${state.videoInfo.width} x ${state.videoInfo.height}")
                    Text("Video Codec: ${state.videoInfo.videoCodec.ifEmpty { "N/A" }}")
                    Text("Audio Codec: ${state.videoInfo.audioCodec.ifEmpty { "N/A" }}")
                    Text("Frame Rate: ${String.format(Locale.US, "%.2f", state.videoInfo.fps)} fps")
                    Text("Decoder: ${state.videoInfo.hwdecActive.ifEmpty { state.hwDecMode.label }}")

                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Hardware Decoder Selection:", fontWeight = FontWeight.Bold)
                    HwDecMode.values().forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onHwDecChange(mode) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (state.hwDecMode == mode),
                                onClick = { onHwDecChange(mode) }
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(mode.label, fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) {
                    Text("Done")
                }
            }
        )
    }

    if (showTrackDialog) {
        var selectedTab by remember { mutableIntStateOf(0) }
        AlertDialog(
            onDismissRequest = { showTrackDialog = false },
            title = { Text("Tracks & Synchronization") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    TabRow(selectedTabIndex = selectedTab) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("Subtitles") }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("Audio") }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (selectedTab == 0) {
                        val subTracks = state.tracks.filter { it.type == TrackType.SUBTITLE }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Subtitle Delay: ${String.format(Locale.US, "%.1fs", state.subDelaySec)}")
                            Row {
                                TextButton(onClick = { onSubDelayAdjust(-0.1) }) { Text("-0.1s") }
                                TextButton(onClick = { onSubDelayAdjust(0.1) }) { Text("+0.1s") }
                            }
                        }

                        LazyColumn(modifier = Modifier.height(180.dp)) {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelectSubtitle(0) }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = subTracks.none { it.isSelected },
                                        onClick = { onSelectSubtitle(0) }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Off")
                                }
                            }
                            items(subTracks) { track ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelectSubtitle(track.id) }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = track.isSelected,
                                        onClick = { onSelectSubtitle(track.id) }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "${track.id}: ${track.title.ifEmpty { track.lang.ifEmpty { "Track ${track.id}" } }} [${track.codec}]",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    } else {
                        val audioTracks = state.tracks.filter { it.type == TrackType.AUDIO }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Audio Delay: ${String.format(Locale.US, "%.1fs", state.audioDelaySec)}")
                            Row {
                                TextButton(onClick = { onAudioDelayAdjust(-0.1) }) { Text("-0.1s") }
                                TextButton(onClick = { onAudioDelayAdjust(0.1) }) { Text("+0.1s") }
                            }
                        }

                        LazyColumn(modifier = Modifier.height(180.dp)) {
                            items(audioTracks) { track ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelectAudio(track.id) }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = track.isSelected,
                                        onClick = { onSelectAudio(track.id) }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "${track.id}: ${track.title.ifEmpty { track.lang.ifEmpty { "Audio Track ${track.id}" } }} [${track.codec}]",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTrackDialog = false }) {
                    Text("Close")
                }
            }
        )
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
