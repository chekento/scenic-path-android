package cloud.kosch.scenicpath

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
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

@Composable
fun ScenicPathTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ScenicColors, content = content)
}
