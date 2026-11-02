package arc.snora.settings

import android.content.Context
import arc.snora.player.SubtitleAppearance

class SubtitleSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "subtitle_appearance",
        Context.MODE_PRIVATE
    )

    fun load(): SubtitleAppearance {
        return SubtitleAppearance(
            horizontalPosition = preferences.getFloat(
                KEY_HORIZONTAL,
                SubtitleAppearance.DEFAULT_HORIZONTAL_POSITION
            ).coerceIn(0f, 1f),
            verticalPosition = preferences.getFloat(
                KEY_VERTICAL,
                SubtitleAppearance.DEFAULT_VERTICAL_POSITION
            ).coerceIn(0f, 1f),
            fontSizeSp = preferences.getFloat(
                KEY_SIZE,
                SubtitleAppearance.DEFAULT_FONT_SIZE_SP
            ).coerceIn(SubtitleAppearance.MIN_FONT_SIZE_SP, SubtitleAppearance.MAX_FONT_SIZE_SP),
            textColorArgb = preferences.getInt(
                KEY_TEXT_COLOR,
                SubtitleAppearance.DEFAULT_TEXT_COLOR_ARGB
            ),
            backgroundColorArgb = preferences.getInt(
                KEY_BACKGROUND_COLOR,
                SubtitleAppearance.DEFAULT_BACKGROUND_COLOR_ARGB
            ),
            backgroundOpacity = preferences.getFloat(
                KEY_BACKGROUND_OPACITY,
                SubtitleAppearance.DEFAULT_BACKGROUND_OPACITY
            ).coerceIn(0f, 1f)
        )
    }

    fun save(appearance: SubtitleAppearance) {
        preferences.edit()
            .putFloat(KEY_HORIZONTAL, appearance.horizontalPosition)
            .putFloat(KEY_VERTICAL, appearance.verticalPosition)
            .putFloat(KEY_SIZE, appearance.fontSizeSp)
            .putInt(KEY_TEXT_COLOR, appearance.textColorArgb)
            .putInt(KEY_BACKGROUND_COLOR, appearance.backgroundColorArgb)
            .putFloat(KEY_BACKGROUND_OPACITY, appearance.backgroundOpacity)
            .apply()
    }

    private companion object {
        const val KEY_HORIZONTAL = "horizontal_position"
        const val KEY_VERTICAL = "vertical_position"
        const val KEY_SIZE = "font_size"
        const val KEY_TEXT_COLOR = "text_color"
        const val KEY_BACKGROUND_COLOR = "background_color"
        const val KEY_BACKGROUND_OPACITY = "background_opacity"
    }
}
