package com.localsmsrelay.ui

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
 * 应用主题。
 *
 * Android 12（API 31）及以上跟随系统取色（Material You），
 * 更低版本回落到与旧界面一脉相承的蓝绿配色，保证观感连续。
 */
private val FallbackLight = lightColorScheme(
    primary = Color(0xFF0E4D64),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC2E7F5),
    onPrimaryContainer = Color(0xFF001F29),
    secondary = Color(0xFF4E6168),
    onSecondary = Color.White,
    background = Color(0xFFF5F7F8),
    onBackground = Color(0xFF171C1E),
    surface = Color(0xFFF5F7F8),
    onSurface = Color(0xFF171C1E),
    surfaceVariant = Color(0xFFDCE4E8),
    onSurfaceVariant = Color(0xFF40484B),
    outline = Color(0xFF70787C),
    error = Color(0xFFB3261E),
    onError = Color.White,
)

private val FallbackDark = darkColorScheme(
    primary = Color(0xFF86D0EA),
    onPrimary = Color(0xFF003545),
    primaryContainer = Color(0xFF004D61),
    onPrimaryContainer = Color(0xFFC2E7F5),
    secondary = Color(0xFFB5C9D1),
    onSecondary = Color(0xFF20333A),
    background = Color(0xFF0F1416),
    onBackground = Color(0xFFDFE3E5),
    surface = Color(0xFF0F1416),
    onSurface = Color(0xFFDFE3E5),
    surfaceVariant = Color(0xFF40484B),
    onSurfaceVariant = Color(0xFFC0C8CB),
    outline = Color(0xFF8A9296),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
)

@Composable
fun SmsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> FallbackDark
        else -> FallbackLight
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
