package arc.snora.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import arc.snora.player.SubtitleAppearance
import kotlin.math.roundToInt

private val SubtitleColorPalette = listOf(
    0xFFFFFFFF.toInt(),
    0xFFFFFF00.toInt(),
    0xFFFF5252.toInt(),
    0xFF69F0AE.toInt(),
    0xFF40C4FF.toInt(),
    0xFF2979FF.toInt(),
    0xFFE040FB.toInt(),
    0xFF000000.toInt()
)

@Composable
fun SubtitleSettingsScreen(
    appearance: SubtitleAppearance,
    onAppearanceChange: (SubtitleAppearance) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val longSide = maxOf(configuration.screenWidthDp, configuration.screenHeightDp).coerceAtLeast(1)
    val shortSide = minOf(configuration.screenWidthDp, configuration.screenHeightDp).coerceAtLeast(1)
    // The dummy device always represents landscape playback, even when this
    // settings page itself is being viewed in portrait.
    val previewAspectRatio = (longSide.toFloat() / shortSide.toFloat()).coerceIn(1.3f, 2.4f)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("subtitle_settings_back")) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back"
                )
            }
            Text(
                text = "Subtitle Layout",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { onAppearanceChange(SubtitleAppearance()) }) {
                Text("Reset")
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text(
                text = "Landscape preview on your screen",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(previewAspectRatio)
                    .clip(RoundedCornerShape(22.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF273248), Color(0xFF111722))
                        )
                    )
                    .border(1.dp, Color(0xFF35415A), RoundedCornerShape(22.dp))
                    .testTag("subtitle_dummy_screen")
            ) {
                Text(
                    text = "VIDEO PREVIEW",
                    color = Color(0x22FFFFFF),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    modifier = Modifier.align(Alignment.Center)
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                ) {
                    val alignment = BiasAlignment(
                        horizontalBias = appearance.horizontalPosition * 2f - 1f,
                        verticalBias = (1f - appearance.verticalPosition) * 2f - 1f
                    )
                    Text(
                        text = "This is a sample subtitle\nIt updates as you move the sliders",
                        color = Color(appearance.textColorArgb),
                        fontSize = appearance.fontSizeSp.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        lineHeight = (appearance.fontSizeSp + 4f).sp,
                        modifier = Modifier
                            .align(alignment)
                            .background(
                                Color(appearance.backgroundColorArgb).copy(
                                    alpha = appearance.backgroundOpacity
                                ),
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                            .testTag("subtitle_live_preview")
                    )
                }
            }

            Text(
                text = "Changes are saved automatically",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SubtitleSettingSlider(
                label = "LEFT / RIGHT",
                valueLabel = "${(appearance.horizontalPosition * 100f).roundToInt()}%",
                value = appearance.horizontalPosition,
                onValueChange = {
                    onAppearanceChange(appearance.copy(horizontalPosition = it))
                },
                testTag = "subtitle_horizontal_slider"
            )

            SubtitleSettingSlider(
                label = "DOWN / UP",
                valueLabel = "${(appearance.verticalPosition * 100f).roundToInt()}% up",
                value = appearance.verticalPosition,
                onValueChange = {
                    onAppearanceChange(appearance.copy(verticalPosition = it))
                },
                testTag = "subtitle_vertical_slider"
            )

            val sizeFraction = (
                (appearance.fontSizeSp - SubtitleAppearance.MIN_FONT_SIZE_SP) /
                    (SubtitleAppearance.MAX_FONT_SIZE_SP - SubtitleAppearance.MIN_FONT_SIZE_SP)
            ).coerceIn(0f, 1f)
            SubtitleSettingSlider(
                label = "SUBTITLE SIZE",
                valueLabel = "${appearance.fontSizeSp.roundToInt()} sp",
                value = sizeFraction,
                onValueChange = { fraction ->
                    val size = SubtitleAppearance.MIN_FONT_SIZE_SP + fraction *
                        (SubtitleAppearance.MAX_FONT_SIZE_SP - SubtitleAppearance.MIN_FONT_SIZE_SP)
                    onAppearanceChange(appearance.copy(fontSizeSp = size))
                },
                testTag = "subtitle_size_slider"
            )

            SubtitleColorSetting(
                label = "TEXT COLOUR",
                colorArgb = appearance.textColorArgb,
                onColorChange = { onAppearanceChange(appearance.copy(textColorArgb = it)) },
                testTag = "subtitle_text_colour"
            )

            SubtitleColorSetting(
                label = "BACKGROUND BOX COLOUR",
                colorArgb = appearance.backgroundColorArgb,
                onColorChange = { onAppearanceChange(appearance.copy(backgroundColorArgb = it)) },
                testTag = "subtitle_background_colour"
            )

            SubtitleSettingSlider(
                label = "BACKGROUND BOX OPACITY",
                valueLabel = "${(appearance.backgroundOpacity * 100f).roundToInt()}%",
                value = appearance.backgroundOpacity,
                onValueChange = {
                    onAppearanceChange(appearance.copy(backgroundOpacity = it))
                },
                testTag = "subtitle_background_opacity_slider"
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SubtitleColorSetting(
    label: String,
    colorArgb: Int,
    onColorChange: (Int) -> Unit,
    testTag: String
) {
    var showCustomRgb by remember(label) { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .width(42.dp)
                    .height(24.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(colorArgb))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                    .testTag("${testTag}_preview")
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SubtitleColorPalette.forEachIndexed { index, paletteColor ->
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Color(paletteColor))
                        .border(
                            width = if (paletteColor == colorArgb) 3.dp else 1.dp,
                            color = if (paletteColor == colorArgb) {
                                Color(0xFF2979FF)
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                            shape = CircleShape
                        )
                        .clickable { onColorChange(paletteColor) }
                        .testTag("${testTag}_swatch_$index")
                )
            }
        }

        TextButton(
            onClick = { showCustomRgb = !showCustomRgb },
            modifier = Modifier.testTag("${testTag}_custom_toggle")
        ) {
            Text(if (showCustomRgb) "Hide custom RGB" else "Custom RGB")
        }

        if (showCustomRgb) {
            val red = (colorArgb shr 16) and 0xFF
            val green = (colorArgb shr 8) and 0xFF
            val blue = colorArgb and 0xFF

            SubtitleSettingSlider(
                label = "RED",
                valueLabel = red.toString(),
                value = red / 255f,
                onValueChange = {
                    onColorChange(colorWithChannels(it, red = true, colorArgb = colorArgb))
                },
                testTag = "${testTag}_red_slider"
            )
            SubtitleSettingSlider(
                label = "GREEN",
                valueLabel = green.toString(),
                value = green / 255f,
                onValueChange = {
                    onColorChange(colorWithChannels(it, green = true, colorArgb = colorArgb))
                },
                testTag = "${testTag}_green_slider"
            )
            SubtitleSettingSlider(
                label = "BLUE",
                valueLabel = blue.toString(),
                value = blue / 255f,
                onValueChange = {
                    onColorChange(colorWithChannels(it, blue = true, colorArgb = colorArgb))
                },
                testTag = "${testTag}_blue_slider"
            )
        }
    }
}

private fun colorWithChannels(
    fraction: Float,
    red: Boolean = false,
    green: Boolean = false,
    blue: Boolean = false,
    colorArgb: Int
): Int {
    val channel = (fraction.coerceIn(0f, 1f) * 255f).roundToInt()
    val oldRed = (colorArgb shr 16) and 0xFF
    val oldGreen = (colorArgb shr 8) and 0xFF
    val oldBlue = colorArgb and 0xFF
    return (0xFF shl 24) or
        ((if (red) channel else oldRed) shl 16) or
        ((if (green) channel else oldGreen) shl 8) or
        (if (blue) channel else oldBlue)
}

@Composable
private fun SubtitleSettingSlider(
    label: String,
    valueLabel: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    testTag: String
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = valueLabel,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF2979FF),
                activeTrackColor = Color(0xFF2979FF),
                inactiveTrackColor = Color(0xFF35415A)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(testTag)
        )
    }
}
