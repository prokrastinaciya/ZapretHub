package com.amurcanov.tgwsproxy.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.amurcanov.tgwsproxy.R

private val UnboundedFamily = FontFamily(
    Font(R.font.unbounded_semibold, FontWeight.SemiBold),
    Font(R.font.unbounded_bold, FontWeight.Bold),
)
private val OnestFamily = FontFamily(
    Font(R.font.onest_regular, FontWeight.Normal),
    Font(R.font.onest_medium, FontWeight.Medium),
    Font(R.font.onest_semibold, FontWeight.SemiBold),
)
val JetBrainsMonoFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

@Immutable
data class BrandColors(
    val isDark: Boolean,
    val bg: Color,
    val surface: Color,
    val raised: Color,
    val line: Color,
    val ink: Color,
    val muted: Color,
    /** Accent as text, line and icon. Differs per theme: the bright accent is unreadable on paper. */
    val accent: Color,
    /** Accent as a fill. Always the bright accent; text on it is always [onFill]. */
    val fill: Color,
    val onFill: Color,
    /** Only for errors and risk, and always next to a glyph such as [!]. */
    val alert: Color,
)

private val HubDark = BrandColors(
    isDark = true,
    bg = Color(0xFF07060F),
    surface = Color(0xFF151322),
    raised = Color(0xFF211C32),
    line = Color(0xFF3C3456),
    ink = Color(0xFFECEBF7),
    muted = Color(0xFFA3A0BF),
    accent = Color(0xFFB49FFF),
    fill = Color(0xFFB49FFF),
    onFill = Color(0xFF171024),
    alert = Color(0xFFFFC35C),
)

private val HubLight = BrandColors(
    isDark = false,
    bg = Color(0xFFF6F4FC),
    surface = Color(0xFFFFFFFF),
    raised = Color(0xFFEBE5F7),
    line = Color(0xFFC9BDE1),
    ink = Color(0xFF201536),
    muted = Color(0xFF564A70),
    accent = Color(0xFF6545B3),
    fill = Color(0xFFB49FFF),
    onFill = Color(0xFF171024),
    alert = Color(0xFF963C00),
)

private val LocalBrandColors = staticCompositionLocalOf { HubDark }

val Brand: BrandColors
    @Composable
    @ReadOnlyComposable
    get() = LocalBrandColors.current

/** System labels: > СЕТЬ, [OK] ПОДКЛЮЧЕНО, table heads. */
val MonoLabelStyle = TextStyle(
    fontFamily = JetBrainsMonoFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 12.sp,
    lineHeight = 18.sp,
    letterSpacing = 0.06.em,
)

/** Numbers, addresses and keys. */
val MonoValueStyle = TextStyle(
    fontFamily = JetBrainsMonoFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 14.sp,
    lineHeight = 20.sp,
)

private val BrandTypography = Typography(
    displayLarge = TextStyle(fontFamily = UnboundedFamily, fontWeight = FontWeight.Bold, fontSize = 48.sp, lineHeight = 56.sp),
    displayMedium = TextStyle(fontFamily = UnboundedFamily, fontWeight = FontWeight.Bold, fontSize = 38.sp, lineHeight = 46.sp),
    displaySmall = TextStyle(fontFamily = UnboundedFamily, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 40.sp),
    headlineLarge = TextStyle(fontFamily = UnboundedFamily, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontFamily = UnboundedFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = UnboundedFamily, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = UnboundedFamily, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = OnestFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = OnestFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = OnestFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = OnestFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = OnestFamily, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = MonoLabelStyle.copy(fontSize = 13.sp),
    labelMedium = MonoLabelStyle,
    labelSmall = MonoLabelStyle.copy(fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.02.em),
)

private val BrandShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

private fun BrandColors.toColorScheme() = if (isDark) {
    darkColorScheme(
        primary = accent, onPrimary = onFill,
        primaryContainer = raised, onPrimaryContainer = ink,
        secondary = accent, onSecondary = onFill,
        secondaryContainer = raised, onSecondaryContainer = ink,
        tertiary = accent, onTertiary = onFill,
        tertiaryContainer = raised, onTertiaryContainer = ink,
        background = bg, onBackground = ink,
        surface = surface, onSurface = ink,
        surfaceVariant = raised, onSurfaceVariant = muted,
        surfaceTint = Color.Transparent,
        inverseSurface = ink, inverseOnSurface = bg, inversePrimary = bg,
        outline = line, outlineVariant = line,
        error = alert, onError = onFill,
        errorContainer = raised, onErrorContainer = alert,
    )
} else {
    lightColorScheme(
        primary = accent, onPrimary = Color.White,
        primaryContainer = raised, onPrimaryContainer = ink,
        secondary = accent, onSecondary = Color.White,
        secondaryContainer = raised, onSecondaryContainer = ink,
        tertiary = accent, onTertiary = Color.White,
        tertiaryContainer = raised, onTertiaryContainer = ink,
        background = bg, onBackground = ink,
        surface = surface, onSurface = ink,
        surfaceVariant = raised, onSurfaceVariant = muted,
        surfaceTint = Color.Transparent,
        inverseSurface = ink, inverseOnSurface = bg, inversePrimary = fill,
        outline = line, outlineVariant = line,
        error = alert, onError = Color.White,
        errorContainer = raised, onErrorContainer = alert,
    )
}

object ThemeModes {
    const val DARK = "dark"
    const val LIGHT = "light"
    const val SYSTEM = "system"
}

@Composable
fun TgWsProxyTheme(
    themeMode: String = ThemeModes.DARK,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeModes.DARK -> true
        ThemeModes.LIGHT -> false
        else -> isSystemInDarkTheme()
    }
    val brand = if (darkTheme) HubDark else HubLight
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Android 7 cannot draw dark navigation icons, so a light bar would hide its buttons.
            val canTintNavigationIcons = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            val navigationBarColor = if (darkTheme || canTintNavigationIcons) brand.bg else HubDark.bg
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.Transparent.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = navigationBarColor.toArgb()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
                window.isStatusBarContrastEnforced = false
            }
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalBrandColors provides brand) {
        MaterialTheme(
            colorScheme = brand.toColorScheme(),
            typography = BrandTypography,
            shapes = BrandShapes,
            content = content
        )
    }
}
