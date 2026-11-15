package amorx

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Цвета взяты из Rhythm (Color.kt), seed #6750A4
private val LightScheme = lightColorScheme(
    primary = Color(0xFF6750A4), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF), onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEF8), onSecondaryContainer = Color(0xFF1D192B),
    tertiary = Color(0xFF7D5260), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8E4), onTertiaryContainer = Color(0xFF31111D),
    error = Color(0xFFB3261E), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFFAF5FF), onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFAF5FF), onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE4DCF0), onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFF79747E), outlineVariant = Color(0xFFCAC4D0),
    surfaceContainerLowest = Color(0xFFF5EFFE), surfaceContainerLow = Color(0xFFF0E9F9),
    surfaceContainer = Color(0xFFEAE3F3), surfaceContainerHigh = Color(0xFFE4DDED),
    surfaceContainerHighest = Color(0xFFDED6E7),
    inverseSurface = Color(0xFF313033), inverseOnSurface = Color(0xFFF4EFF4),
    inversePrimary = Color(0xFFD0BCFF)
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFD0BCFF), onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B), onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC), onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458), onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFEFB8C8), onTertiary = Color(0xFF492532),
    tertiaryContainer = Color(0xFF633B48), onTertiaryContainer = Color(0xFFFFD8E4),
    error = Color(0xFFF2B8B5), onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF141218), onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF141218), onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF49454F), onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF938F99), outlineVariant = Color(0xFF49454F),
    surfaceContainerLowest = Color(0xFF0D0B10), surfaceContainerLow = Color(0xFF1A1820),
    surfaceContainer = Color(0xFF201E25), surfaceContainerHigh = Color(0xFF2A282F),
    surfaceContainerHighest = Color(0xFF35333A),
    inverseSurface = Color(0xFFE6E1E5), inverseOnSurface = Color(0xFF313033),
    inversePrimary = Color(0xFF6750A4)
)

@Composable
fun AmorxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = when (SettingsStore.current.value.appearance) {
            "dark" -> DarkScheme
            "light" -> LightScheme
            else -> if (isSystemInDarkTheme()) DarkScheme else LightScheme
        },
        content = content
    )
}
