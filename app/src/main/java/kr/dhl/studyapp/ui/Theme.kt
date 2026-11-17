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

/** Clean & minimal palette: white/near-black pages, soft borderless cards, one blue accent. */
@Immutable
data class Pal(
    val dark: Boolean,
    val bg: Color, val card: Color, val tile: Color,
    val text: Color, val sub: Color, val faint: Color,
    val accent: Color, val accentSoft: Color,
    val danger: Color, val dangerSoft: Color,
    val up: Color, val down: Color, val divider: Color,
    val cls: Color, val clsInk: Color, val job: Color, val jobInk: Color, val ev: Color, val evInk: Color,
    val travel: Color, val travelLine: Color, val free: Color, val freeLine: Color, val freeInk: Color,
)

val LightPal = Pal(
    dark = false,
    bg = Color(0xFFFFFFFF), card = Color(0xFFF4F5F7), tile = Color(0xFFFFFFFF),
    text = Color(0xFF191F28), sub = Color(0xFF6B7684), faint = Color(0xFFB0B8C1),
    accent = Color(0xFF3182F6), accentSoft = Color(0xFFE8F3FF),
    danger = Color(0xFFF04452), dangerSoft = Color(0xFFFFEEEF),
    up = Color(0xFFF04452), down = Color(0xFF3182F6), divider = Color(0xFFE5E8EB),
    cls = Color(0xFFE3EEFF), clsInk = Color(0xFF1B64DA), job = Color(0xFFFFEDE0), jobInk = Color(0xFFC2410C),
    ev = Color(0xFFE9ECF0), evInk = Color(0xFF4E5968),
    travel = Color(0xFFF2F4F6), travelLine = Color(0xFFD1D6DB),
    free = Color(0xFFE5F7EE), freeLine = Color(0xFF4CC38A), freeInk = Color(0xFF0F7B4A),
)

val DarkPal = Pal(
    dark = true,
    bg = Color(0xFF101114), card = Color(0xFF1C1E22), tile = Color(0xFF26292E),
    text = Color(0xFFF2F4F6), sub = Color(0xFF9BA3AE), faint = Color(0xFF5D646E),
    accent = Color(0xFF4C9AFF), accentSoft = Color(0xFF1B2B44),
    danger = Color(0xFFFF5C69), dangerSoft = Color(0xFF3A1E22),
    up = Color(0xFFFF5C69), down = Color(0xFF4C9AFF), divider = Color(0xFF2C2F35),
    cls = Color(0xFF1E2E4A), clsInk = Color(0xFF8DBBFF), job = Color(0xFF3A2A1E), jobInk = Color(0xFFFFB37A),
    ev = Color(0xFF2A2D33), evInk = Color(0xFFC3C9D1),
    travel = Color(0xFF202328), travelLine = Color(0xFF3A3E45),
    free = Color(0xFF16332A), freeLine = Color(0xFF3FB27F), freeInk = Color(0xFF7EE0B0),
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
    val scheme = if (dark) darkColorScheme(
        primary = p.accent, onPrimary = Color.White, primaryContainer = p.accentSoft, onPrimaryContainer = p.accent,
        secondaryContainer = p.accentSoft, onSecondaryContainer = p.accent,
        background = p.bg, onBackground = p.text, surface = p.bg, onSurface = p.text,
        surfaceVariant = p.card, onSurfaceVariant = p.sub, outline = p.faint, outlineVariant = p.divider,
        error = p.danger, surfaceContainer = p.bg, surfaceContainerLow = p.card,
        surfaceContainerHigh = p.card, surfaceContainerHighest = p.tile, inverseSurface = p.text, inverseOnSurface = p.bg,
        inversePrimary = p.accent,
    ) else lightColorScheme(
        primary = p.accent, onPrimary = Color.White, primaryContainer = p.accentSoft, onPrimaryContainer = p.accent,
        secondaryContainer = p.accentSoft, onSecondaryContainer = p.accent,
        background = p.bg, onBackground = p.text, surface = p.bg, onSurface = p.text,
        surfaceVariant = p.card, onSurfaceVariant = p.sub, outline = p.faint, outlineVariant = p.divider,
        error = p.danger, surfaceContainer = p.bg, surfaceContainerLow = p.card,
        surfaceContainerHigh = Color.White, surfaceContainerHighest = Color(0xFFE5E8EB), inverseSurface = p.text,
        inverseOnSurface = p.bg, inversePrimary = Color(0xFF90C2FF),
    )
    CompositionLocalProvider(LocalPal provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
