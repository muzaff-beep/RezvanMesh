package com.rezvani.mesh.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryGreen,
    onPrimary = TextWhite,
    primaryContainer = DarkSurface,
    onPrimaryContainer = TextPrimaryDark,
    secondary = PrimaryGreenHover,
    onSecondary = TextWhite,
    secondaryContainer = DarkSurface,
    onSecondaryContainer = TextPrimaryDark,
    tertiary = WarningOrange,
    onTertiary = TextWhite,
    background = DarkBackground,
    onBackground = TextPrimaryDark,
    surface = DarkSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextMutedDark,
    error = DangerRed,
    onError = TextWhite,
    errorContainer = ErrorContainerDark,
    onErrorContainer = TextWhite,
    outline = DarkBorder,
    inverseSurface = DarkSurface,
    inverseOnSurface = TextPrimaryDark
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryGreen,
    onPrimary = TextWhite,
    primaryContainer = PrimaryGreenHover,
    onPrimaryContainer = TextWhite,
    secondary = PrimaryGreenHover,
    onSecondary = TextWhite,
    secondaryContainer = LightSurface,
    onSecondaryContainer = TextPrimaryLight,
    tertiary = WarningOrange,
    onTertiary = TextWhite,
    background = LightBackground,
    onBackground = TextPrimaryLight,
    surface = LightSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = TextMutedLight,
    error = DangerRed,
    onError = TextWhite,
    errorContainer = ErrorContainerLight,
    onErrorContainer = DangerRed,
    outline = LightBorder,
    inverseSurface = LightSurface,
    inverseOnSurface = TextPrimaryLight
)

@Composable
fun RezvanMeshTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
