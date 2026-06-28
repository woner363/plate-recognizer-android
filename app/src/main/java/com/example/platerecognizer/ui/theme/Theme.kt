package com.example.platerecognizer.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * App 主题。Android 12+ (S) 启用 Material You 动态取色（Wallpaper-based），
 * 老设备回退到本地定义的蓝色基调。
 */
@Composable
fun PlateRecognizerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> darkColorScheme(
            primary = Color(0xFF90CAF9),
            onPrimary = Color(0xFF003258),
            secondary = Color(0xFFB0BEC5),
        )
        else -> lightColorScheme(
            primary = Color(0xFF1976D2),
            onPrimary = Color(0xFFFFFFFF),
            secondary = Color(0xFF455A64),
        )
    }
    MaterialTheme(colorScheme = colors, content = content)
}
