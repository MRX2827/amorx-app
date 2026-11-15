package amorx

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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
    primary = Color(0xFFF7F7F7), onPrimary = Color(0xFF0C0C0C),
    primaryContainer = Color(0xFF222222), onPrimaryContainer = Color(0xFFF7F7F7),
    secondary = Color(0xFFDBDBDB), onSecondary = Color(0xFF151515),
    secondaryContainer = Color(0xFF222222), onSecondaryContainer = Color(0xFFF0F0F0),
    tertiary = Color(0xFFC9C9C9), onTertiary = Color(0xFF151515),
    tertiaryContainer = Color(0xFF292929), onTertiaryContainer = Color(0xFFE8E8E8),
    error = Color(0xFFFFADAA), onError = Color(0xFF620F16),
    errorContainer = Color(0xFF581B1B), onErrorContainer = Color(0xFFFFDDDB),
    background = Color(0xFF0C0C0C), onBackground = Color(0xFFF7F7F7),
    surface = Color(0xFF0C0C0C), onSurface = Color(0xFFF7F7F7),
    surfaceTint = Color.Transparent,
    surfaceVariant = Color(0xFF191919), onSurfaceVariant = Color(0xFFAAAAAA),
    outline = Color(0xFF737373), outlineVariant = Color(0xFF282828),
    surfaceContainerLowest = Color(0xFF0B0B0B), surfaceContainerLow = Color(0xFF111111),
    surfaceContainer = Color(0xFF171717), surfaceContainerHigh = Color(0xFF202020),
    surfaceContainerHighest = Color(0xFF282828),
    inverseSurface = Color(0xFFF7F7F7), inverseOnSurface = Color(0xFF0C0C0C),
    inversePrimary = Color(0xFF313131)
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
