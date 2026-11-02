package arc.snora.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleAppearanceTest {

    @Test
    fun untouchedAppearanceRemainsDefault() {
        assertTrue(SubtitleAppearance().isDefault())
    }

    @Test
    fun customTextColourRequiresPlayerCustomization() {
        val appearance = SubtitleAppearance(textColorArgb = 0xFFFFFFFF.toInt())
        assertFalse(appearance.isDefault())
    }

    @Test
    fun customBackgroundColourRequiresPlayerCustomization() {
        val appearance = SubtitleAppearance(backgroundColorArgb = 0xFF2979FF.toInt())
        assertFalse(appearance.isDefault())
    }

    @Test
    fun customBackgroundOpacityRequiresPlayerCustomization() {
        val appearance = SubtitleAppearance(backgroundOpacity = 0.35f)
        assertFalse(appearance.isDefault())
    }
}
