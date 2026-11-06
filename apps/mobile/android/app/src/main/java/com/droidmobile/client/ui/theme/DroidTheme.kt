package com.droidmobile.client.ui.theme

import android.content.SharedPreferences
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** App-wide spatial scale. Layout and component shape communicate hierarchy. */
object DroidTokens {
    val gapXS = 4.dp
    val gapS = 8.dp
    val gapM = 12.dp
    val gapL = 16.dp
    val gapXL = 24.dp
    val gapXXL = 32.dp
    val pagePadding = 20.dp
    val contentMaxWidth = 840.dp
    val touchTarget = 48.dp
    val smallSpacing = gapS
    val sectionSpacing = gapL
    val contentPadding = pagePadding
    val heroPadding = gapXXL
    val pageMaxWidth = contentMaxWidth
}

/** The same tonal palette is used by *all* screens, including onboarding. */
private val fallbackDark = darkColorScheme(
    primary = Color(0xFFCDC3FF),
    onPrimary = Color(0xFF322861),
    primaryContainer = Color(0xFF4C417A),
    onPrimaryContainer = Color(0xFFE9DFFF),
    secondary = Color(0xFFD0C3E4),
    onSecondary = Color(0xFF362B46),
    secondaryContainer = Color(0xFF4C405C),
    onSecondaryContainer = Color(0xFFEDDCF8),
    tertiary = Color(0xFFF0BBD4),
    onTertiary = Color(0xFF51263C),
    background = Color(0xFF15141B),
    onBackground = Color(0xFFE9E5F1),
    surface = Color(0xFF15141B),
    onSurface = Color(0xFFE9E5F1),
    surfaceVariant = Color(0xFF484650),
    onSurfaceVariant = Color(0xFFCCC5D5),
    surfaceContainerLow = Color(0xFF1B1922),
    surfaceContainer = Color(0xFF222029),
    surfaceContainerHigh = Color(0xFF2D2A35),
    outline = Color(0xFF97909F)
)
private val fallbackLight = lightColorScheme(
    primary = Color(0xFF5E518F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8DEFF),
    onPrimaryContainer = Color(0xFF211342),
    secondary = Color(0xFF655A79),
    secondaryContainer = Color(0xFFEBDEF6),
    tertiary = Color(0xFF82526C),
    background = Color(0xFFFCF8FF),
    onBackground = Color(0xFF1D1B22),
    surface = Color(0xFFFCF8FF),
    onSurface = Color(0xFF1D1B22),
    surfaceContainerLow = Color(0xFFF8F2FB),
    surfaceContainer = Color(0xFFF2ECF5),
    surfaceContainerHigh = Color(0xFFECE6EF)
)

/** Shapes are a scale: pills for actions, grouped rounds for related content. */
private val expressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

/**
 * Keep the complete Material type scale. Screen headings are display/headline,
 * data is body, controls are labels; never simulate hierarchy with arbitrary
 * hard-coded text sizes in individual screen files.
 */
private val expressiveTypography = Typography(
    displayLarge = TextStyle(fontSize = 56.sp, lineHeight = 64.sp, fontWeight = FontWeight.SemiBold),
    displayMedium = TextStyle(fontSize = 44.sp, lineHeight = 52.sp, fontWeight = FontWeight.SemiBold),
    displaySmall = TextStyle(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold),
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DroidTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val prefs = remember(context) {
        context.getSharedPreferences("droid_preferences", 0)
    }
    var themeOption by remember(prefs) { mutableStateOf(prefs.getString("theme", "system") ?: "system") }
    var dynamic by remember(prefs) { mutableStateOf(prefs.getBoolean("dynamic", true)) }

    // Compose *observes* preference changes. The previous UI wrote theme
    // settings but could not update its palette until a process restart.
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "theme" -> themeOption = prefs.getString("theme", "system") ?: "system"
                "dynamic" -> dynamic = prefs.getBoolean("dynamic", true)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val dark = when (themeOption) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }
    val colors = when {
        dynamic && Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        dynamic && Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> fallbackDark
        else -> fallbackLight
    }
    MaterialTheme(
        colorScheme = colors,
        typography = expressiveTypography,
        shapes = expressiveShapes,
        motionScheme = MotionScheme.expressive(),
        content = content
    )
}
