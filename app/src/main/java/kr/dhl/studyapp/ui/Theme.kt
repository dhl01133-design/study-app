package kr.dhl.studyapp.ui

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Minimal palette with a few accents: blue for selections, classes and "now", green for free time,
 * orange for top-priority tasks, course names in their own color. Red only for deadlines and overdue items.
 */
@Immutable
data class Pal(
    val dark: Boolean,
    val bg: Color, val surface: Color,
    val text: Color, val sub: Color, val faint: Color, val line: Color,
    val accent: Color, val onAccent: Color, val accentSoft: Color,
    val danger: Color, val good: Color, val goodSoft: Color, val warn: Color,
    val busy: Color, val onBusy: Color, val track: Color, val hatch: Color,
)

val LightPal = Pal(
    dark = false,
    bg = Color(0xFFFFFFFF), surface = Color(0xFFFFFFFF),
    text = Color(0xFF111111), sub = Color(0xFF6E6E73), faint = Color(0xFFB4B4B9), line = Color(0xFFECECEE),
    accent = Color(0xFF3B6EF5), onAccent = Color(0xFFFFFFFF), accentSoft = Color(0xFFEEF3FF),
    danger = Color(0xFFE5484D), good = Color(0xFF1F9D63), goodSoft = Color(0xFFE6F6EE), warn = Color(0xFFF08C00),
    busy = Color(0xFF2B2B2E), onBusy = Color(0xFFFFFFFF), track = Color(0xFFF3F3F5), hatch = Color(0xFFD9D9DD),
)

val DarkPal = Pal(
    dark = true,
    bg = Color(0xFF0E0E10), surface = Color(0xFF1A1A1D),
    text = Color(0xFFF2F2F2), sub = Color(0xFF9A9AA0), faint = Color(0xFF55555A), line = Color(0xFF242428),
    accent = Color(0xFF6E9BFF), onAccent = Color(0xFF0E0E10), accentSoft = Color(0xFF18213A),
    danger = Color(0xFFFF6369), good = Color(0xFF4CC98A), goodSoft = Color(0xFF12291E), warn = Color(0xFFFFA94D),
    busy = Color(0xFFD6D6DA), onBusy = Color(0xFF0E0E10), track = Color(0xFF1C1C20), hatch = Color(0xFF3A3A3F),
)

val LocalPal = staticCompositionLocalOf { LightPal }

val pal: Pal
    @Composable @ReadOnlyComposable get() = LocalPal.current

/** mode: "system", "light" or "dark". */
@Composable
fun AppTheme(mode: String, content: @Composable () -> Unit) {
    val dark = when (mode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val p = if (dark) DarkPal else LightPal
    val activity = LocalContext.current as? ComponentActivity
    LaunchedEffect(dark) {
        val t = android.graphics.Color.TRANSPARENT
        val style = if (dark) SystemBarStyle.dark(t) else SystemBarStyle.light(t, t)
        activity?.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = p.accent, onPrimary = p.onAccent, primaryContainer = p.accentSoft, onPrimaryContainer = p.text,
        secondaryContainer = p.accentSoft, onSecondaryContainer = p.text,
        background = p.bg, onBackground = p.text, surface = p.bg, onSurface = p.text,
        surfaceVariant = p.accentSoft, onSurfaceVariant = p.sub, outline = p.faint, outlineVariant = p.line,
        error = p.danger, surfaceContainer = p.bg, surfaceContainerLow = p.bg,
        surfaceContainerHigh = p.surface, surfaceContainerHighest = p.line,
        inverseSurface = p.text, inverseOnSurface = p.bg, inversePrimary = p.faint,
    )
    CompositionLocalProvider(LocalPal provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
