package com.deepseekv2.agent.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = DeepBlue,
    onPrimary = Color.White,
    primaryContainer = LightUserBubble,
    onPrimaryContainer = Color(0xFF0F1E45),
    secondary = Color(0xFF565E71),
    background = LightBackground,
    onBackground = Color(0xFF1A1C22),
    surface = LightBackground,
    onSurface = Color(0xFF1A1C22),
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = Color(0xFF44474F),
    outline = LightOutline,
    error = Color(0xFFBA1A1A)
)

private val DarkColors = darkColorScheme(
    primary = DeepBlueLight,
    onPrimary = Color(0xFF0A1440),
    primaryContainer = DarkUserBubble,
    onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFFBFC6DC),
    background = DarkBackground,
    onBackground = Color(0xFFE2E2E9),
    surface = DarkSurface,
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFC4C6D0),
    outline = DarkOutline,
    error = Color(0xFFFFB4AB)
)

val AppTypography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 25.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp
    )
)

@Composable
fun DeepSeekAgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content
    )
}
