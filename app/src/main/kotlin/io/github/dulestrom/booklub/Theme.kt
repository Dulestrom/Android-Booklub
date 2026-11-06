package io.github.dulestrom.booklub

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Paper & Ink, the desktop's token set (desktop QA-17), so both apps share one palette. */
@Immutable
data class Ink(
    val page: Color,
    val card: Color,
    val raised: Color,
    val track: Color,
    val lineSubtle: Color,
    val line: Color,
    val lineStrong: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val ink4: Color,
    val accent: Color,
    val accentInk: Color,
    val onAccent: Color,
    val commit: Color,
    val onCommit: Color,
    val danger: Color,
    val onDanger: Color,
    val dangerInk: Color,
    val success: Color,
    val successInk: Color,
    val warning: Color,
    val warningInk: Color,
    val chart: List<Color>,
    /** Over the Reader's images, so these never change with the mode. */
    val scrim: Color = Color(0xB3120E0A),
    val scrimSolid: Color = Color(0xFF120E0A),
    val tileInk: Color = Color(0xFFF4EEE4),
)

private val LightInk = Ink(
    page = Color(0xFFF7F3EC), card = Color(0xFFFFFDF8), raised = Color(0xFFEFE9DE), track = Color(0xFFE0D8CA),
    lineSubtle = Color(0xFFE9E2D6), line = Color(0xFFD3C9B8), lineStrong = Color(0xFF8A7F6E),
    ink = Color(0xFF211B15), ink2 = Color(0xFF4A4036), ink3 = Color(0xFF62584B), ink4 = Color(0xFF9A8F7E),
    accent = Color(0xFF2E4A8C), accentInk = Color(0xFF2A4585), onAccent = Color(0xFFFFFFFF),
    commit = Color(0xFF2A241F), onCommit = Color(0xFFFFFDF8),
    danger = Color(0xFFA3261E), onDanger = Color(0xFFFFFFFF), dangerInk = Color(0xFF9C241C),
    success = Color(0xFF2F7A4E), successInk = Color(0xFF276842), warning = Color(0xFFA9640A), warningInk = Color(0xFF8A5208),
    chart = listOf(Color(0xFF2E4A8C), Color(0xFF6F86BE), Color(0xFFA9640A), Color(0xFFD0A564), Color(0xFF9A8F7E)),
)

private val DarkInk = Ink(
    page = Color(0xFF14110E), card = Color(0xFF1B1814), raised = Color(0xFF24201B), track = Color(0xFF332D26),
    lineSubtle = Color(0xFF26211C), line = Color(0xFF3A332B), lineStrong = Color(0xFF7D7262),
    ink = Color(0xFFEFE8DC), ink2 = Color(0xFFC2B8A8), ink3 = Color(0xFFA0968A), ink4 = Color(0xFF6B6153),
    accent = Color(0xFF7F9CE0), accentInk = Color(0xFF9BB2EA), onAccent = Color(0xFF14110E),
    commit = Color(0xFFEFE8DC), onCommit = Color(0xFF14110E),
    danger = Color(0xFF8E2A22), onDanger = Color(0xFFF7F3EC), dangerInk = Color(0xFFF08A7E),
    success = Color(0xFF3E9A66), successInk = Color(0xFF7FD3A2), warning = Color(0xFFC98A2B), warningInk = Color(0xFFF0C070),
    chart = listOf(Color(0xFF7F9CE0), Color(0xFF4A64A3), Color(0xFFC98A2B), Color(0xFF8A6A3A), Color(0xFF6B6153)),
)

val LocalInk = staticCompositionLocalOf { LightInk }

val Manrope = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
)

/** The desktop's type ramp (QA-34), in sp. */
object Type {
    val largeTitle = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = (-0.4).sp)
    val title1 = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = (-0.3).sp)
    val title2 = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = (-0.2).sp)
    val heading = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
    val bodyStrong = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    val body = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Normal, fontSize = 15.sp)
    val caption = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Medium, fontSize = 13.sp)
    val eyebrow = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.4.sp)
    val chip = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.2.sp)
    val stat = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = (-0.4).sp)
    val tileTitle = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    val tileAuthor = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Medium, fontSize = 12.sp)
    val initials = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 40.sp, letterSpacing = (-0.5).sp)
}

/** Android SRS REQ-40: System follows the phone's dark theme setting (REQ-40a). */
enum class Appearance(val label: String) { System("System"), Light("Light"), Dark("Dark") }

@Composable
fun BooklubTheme(appearance: Appearance, content: @Composable () -> Unit) {
    val dark = when (appearance) {
        Appearance.System -> isSystemInDarkTheme()
        Appearance.Light -> false
        Appearance.Dark -> true
    }
    val ink = if (dark) DarkInk else LightInk
    val scheme = if (dark) {
        darkColorScheme(primary = ink.accent, onPrimary = ink.onAccent, background = ink.page, surface = ink.card, onSurface = ink.ink, onBackground = ink.ink, surfaceVariant = ink.raised, outline = ink.line, error = ink.dangerInk)
    } else {
        lightColorScheme(primary = ink.accent, onPrimary = ink.onAccent, background = ink.page, surface = ink.card, onSurface = ink.ink, onBackground = ink.ink, surfaceVariant = ink.raised, outline = ink.line, error = ink.dangerInk)
    }
    val typography = Typography(
        bodyLarge = Type.body, bodyMedium = Type.body, bodySmall = Type.caption,
        labelLarge = Type.bodyStrong, labelMedium = Type.caption, labelSmall = Type.chip,
        titleLarge = Type.title2, titleMedium = Type.heading, titleSmall = Type.bodyStrong,
        headlineSmall = Type.title2, headlineMedium = Type.title1, headlineLarge = Type.largeTitle,
    )
    CompositionLocalProvider(LocalInk provides ink) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
