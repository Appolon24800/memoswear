package dev.appolon.memoswear.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.dynamicColorScheme

/**
 * Brand colors in the tonal structure of the Wear Material 3 dark scheme: the accent roles are
 * light fuchsia with dark text, and the containers are deep so they read as surfaces.
 */
private val FuchsiaScheme = ColorScheme(
    primary = Color(0xFFE879F9),
    primaryDim = Color(0xFFC026D3),
    primaryContainer = Color(0xFF701A75),
    onPrimary = Color(0xFF3B0764),
    onPrimaryContainer = Color(0xFFFAE8FF),
    secondary = Color(0xFFD8B4FE),
    secondaryDim = Color(0xFFA855F7),
    secondaryContainer = Color(0xFF4C1D95),
    onSecondary = Color(0xFF2E1065),
    onSecondaryContainer = Color(0xFFF3E8FF),
    tertiary = Color(0xFFF9A8D4),
    tertiaryDim = Color(0xFFEC4899),
    tertiaryContainer = Color(0xFF831843),
    onTertiary = Color(0xFF500724),
    onTertiaryContainer = Color(0xFFFDF2F8),
)

/** On Wear OS 6 and later the app follows the watch face's colors; elsewhere it uses the brand's. */
@Composable
fun MemosWearTheme(content: @Composable () -> Unit) {
    val scheme = dynamicColorScheme(LocalContext.current) ?: FuchsiaScheme
    MaterialTheme(colorScheme = scheme, content = content)
}
