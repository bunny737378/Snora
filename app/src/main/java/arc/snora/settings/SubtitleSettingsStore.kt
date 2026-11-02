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
            ).coerceIn(SubtitleAppearance.MIN_FONT_SIZE_SP, SubtitleAppearance.MAX_FONT_SIZE_SP)
        )
    }

    fun save(appearance: SubtitleAppearance) {
        preferences.edit()
            .putFloat(KEY_HORIZONTAL, appearance.horizontalPosition)
            .putFloat(KEY_VERTICAL, appearance.verticalPosition)
            .putFloat(KEY_SIZE, appearance.fontSizeSp)
            .apply()
    }

    private companion object {
        const val KEY_HORIZONTAL = "horizontal_position"
        const val KEY_VERTICAL = "vertical_position"
        const val KEY_SIZE = "font_size"
    }
}
