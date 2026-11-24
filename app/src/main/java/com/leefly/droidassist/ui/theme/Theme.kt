package com.leefly.droidassist.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = AssistGreenDark,
    onPrimary = AssistOnGreenDark,
    primaryContainer = AssistGreenContainerDark,
    onPrimaryContainer = AssistOnGreenContainerDark,
    secondary = AssistSecondaryDark,
    onSecondary = AssistOnSecondaryDark,
    secondaryContainer = AssistSecondaryContainerDark,
    onSecondaryContainer = AssistOnSecondaryContainerDark,
    tertiary = AssistTertiaryDark,
    background = AssistBackgroundDark,
    onBackground = AssistOnBackgroundDark,
    surface = AssistBackgroundDark,
    onSurface = AssistOnBackgroundDark,
    surfaceVariant = AssistSurfaceVariantDark,
    outline = AssistOutlineDark,
)

private val LightColorScheme = lightColorScheme(
    primary = AssistGreen,
    onPrimary = AssistOnGreen,
    primaryContainer = AssistGreenContainer,
    onPrimaryContainer = AssistOnGreenContainer,
    secondary = AssistSecondary,
    onSecondary = AssistOnSecondary,
    secondaryContainer = AssistSecondaryContainer,
    onSecondaryContainer = AssistOnSecondaryContainer,
    tertiary = AssistTertiary,
    background = AssistBackground,
    onBackground = AssistOnBackground,
    surface = AssistBackground,
    onSurface = AssistOnBackground,
    surfaceVariant = AssistSurfaceVariant,
    outline = AssistOutline,
)

@Composable
fun DroidAssistTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content,
    )
}
