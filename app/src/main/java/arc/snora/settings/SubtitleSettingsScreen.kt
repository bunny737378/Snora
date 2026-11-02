package arc.snora.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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

@Composable
fun SubtitleSettingsScreen(
    appearance: SubtitleAppearance,
    onAppearanceChange: (SubtitleAppearance) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val previewAspectRatio = (
        configuration.screenWidthDp.toFloat() / configuration.screenHeightDp.coerceAtLeast(1).toFloat()
    ).coerceIn(0.45f, 2.4f)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
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
                text = "Preview on your screen",
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
                        color = Color.White,
                        fontSize = appearance.fontSizeSp.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        lineHeight = (appearance.fontSizeSp + 4f).sp,
                        modifier = Modifier
                            .align(alignment)
                            .background(Color(0x88000000), RoundedCornerShape(6.dp))
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

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
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
