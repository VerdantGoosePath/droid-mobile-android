package com.droidmobile.client.ui.theme

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One design system: dynamic tonal roles, expressive shapes, type hierarchy and
 * the library's spring-based expressive MotionScheme. Never assign random
 * animation durations, corner radii or colours to individual features.
 */
object DroidTokens {
    val smallSpacing = 8.dp
    val sectionSpacing = 16.dp
    val contentPadding = 20.dp
    val heroPadding = 28.dp
    val pageMaxWidth = 900.dp
}
private val fallbackDark = darkColorScheme(
    primary = Color(0xFFCDC3FF),
    onPrimary = Color(0xFF332A62),
    primaryContainer = Color(0xFF493F79),
    secondary = Color(0xFFD0C3E4),
    tertiary = Color(0xFFF0BBD4),
    background = Color(0xFF15141B),
    surface = Color(0xFF15141B),
    surfaceContainer = Color(0xFF222129),
    surfaceContainerHigh = Color(0xFF2E2C36)
)
private val fallbackLight = lightColorScheme(
    primary = Color(0xFF5E518F),
    primaryContainer = Color(0xFFE8DEFF),
    secondary = Color(0xFF655A79),
    tertiary = Color(0xFF82526C),
    background = Color(0xFFFCF8FF),
    surface = Color(0xFFFCF8FF)
)
private val expressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp)
)
private val expressiveType = Typography(
    displaySmall = TextStyle(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold),
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
)
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DroidTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val palette = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor && Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        darkTheme -> fallbackDark
        else -> fallbackLight
    }
    MaterialTheme(
        colorScheme = palette,
        typography = expressiveType,
        shapes = expressiveShapes,
        motionScheme = MotionScheme.expressive(),
        content = content
    )
}
