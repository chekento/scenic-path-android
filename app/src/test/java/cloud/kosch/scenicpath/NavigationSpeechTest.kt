package cloud.kosch.scenicpath

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class NavigationSpeechTest {
    @Test
    fun germanSystemLanguageUsesGermanTurnPhrasingAndInflectedMeters() {
        val text = NavigationSpeech.turnAnnouncement(NavigationTurn.LEFT, 260, Locale.GERMANY)
        assertTrue(text.contains("zweihundertfünfzig Metern", ignoreCase = true))
        assertTrue(text.contains("links abbiegen", ignoreCase = true))
        assertFalse(text.contains("meters", ignoreCase = true))
    }

    @Test
    fun englishSystemLanguageKeepsEnglishGuidance() {
        val text = NavigationSpeech.turnAnnouncement(NavigationTurn.RIGHT, 850, Locale.UK)
        assertTrue(text.contains("eight hundred meters", ignoreCase = true))
        assertTrue(text.contains("turn right", ignoreCase = true))
    }
}
