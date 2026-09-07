package cloud.kosch.scenicpath

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ScenicColors = lightColorScheme(
    primary = Color(0xFF0F6B45),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDF4E7),
    secondary = Color(0xFF2E6F86),
    tertiary = Color(0xFF8A5A2B),
    background = Color(0xFFF8FAF7),
    surface = Color(0xFFFFFFFF),
)

private val ScenicNightColors = darkColorScheme(
    primary = Color(0xFF8ED8AF),
    onPrimary = Color(0xFF003821),
    primaryContainer = Color(0xFF075236),
    onPrimaryContainer = Color(0xFFAAF2CB),
    secondary = Color(0xFF99D1E7),
    secondaryContainer = Color(0xFF164C5F),
    onSecondaryContainer = Color(0xFFC3EBFC),
    background = Color(0xFF101714),
    surface = Color(0xFF101714),
    onSurface = Color(0xFFE0E8E1),
    surfaceContainerLow = Color(0xFF17201B),
    surfaceContainerHigh = Color(0xFF27322C),
)

@Composable
fun ScenicPathTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) ScenicNightColors else ScenicColors, content = content)
}
