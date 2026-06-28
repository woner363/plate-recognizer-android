package com.example.platerecognizer.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
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
 * App 主题。默认使用稳定的品牌色，确保相机遮罩、状态色和记录卡片保持一致；
 * 调用方仍可显式打开 Android 12+ 的 Material You 动态取色。
 */
@Composable
fun PlateRecognizerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> AppDarkColors
        else -> AppLightColors
    }
    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

private val AppLightColors = lightColorScheme(
    primary = Color(0xFF315CF6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE2E8FF),
    onPrimaryContainer = Color(0xFF102566),
    secondary = Color(0xFF5B6478),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8EAF0),
    onSecondaryContainer = Color(0xFF252B3A),
    tertiary = Color(0xFF087F69),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC6F3E6),
    onTertiaryContainer = Color(0xFF00382D),
    background = Color(0xFFF5F6FA),
    onBackground = Color(0xFF171A21),
    surface = Color(0xFFFBFCFF),
    onSurface = Color(0xFF171A21),
    surfaceVariant = Color(0xFFE9EBF2),
    onSurfaceVariant = Color(0xFF5D6270),
    outline = Color(0xFFB9BECA),
    outlineVariant = Color(0xFFDDE0E8),
    error = Color(0xFFBA1A1A),
)

private val AppDarkColors = darkColorScheme(
    primary = Color(0xFFAFC2FF),
    onPrimary = Color(0xFF002E75),
    primaryContainer = Color(0xFF173F9E),
    onPrimaryContainer = Color(0xFFDCE4FF),
    secondary = Color(0xFFC1C7D8),
    onSecondary = Color(0xFF2B3040),
    secondaryContainer = Color(0xFF3F4658),
    onSecondaryContainer = Color(0xFFDDE1F2),
    tertiary = Color(0xFF70DBC2),
    onTertiary = Color(0xFF00382D),
    tertiaryContainer = Color(0xFF005143),
    onTertiaryContainer = Color(0xFF92F8DD),
    background = Color(0xFF101218),
    onBackground = Color(0xFFE3E5ED),
    surface = Color(0xFF151820),
    onSurface = Color(0xFFE3E5ED),
    surfaceVariant = Color(0xFF282C36),
    onSurfaceVariant = Color(0xFFC3C6D0),
    outline = Color(0xFF8D919E),
    outlineVariant = Color(0xFF414550),
    error = Color(0xFFFFB4AB),
)

private val AppShapes = Shapes(
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.4).sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
)
