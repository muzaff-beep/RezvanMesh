package com.rezvani.mesh.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.rezvani.mesh.utils.LocaleHelper
import java.util.*

// Custom composition local for RTL support
val LocalLayoutDirectionOverride = staticCompositionLocalOf { LayoutDirection.Ltr }

@Composable
fun RezvanMeshTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val savedLanguage = LocaleHelper.getSavedLanguage(context)
    val isRtl = savedLanguage == "fa"
    val layoutDirection = if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr

    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = PrimaryDark,
            onPrimary = OnPrimaryDark,
            primaryContainer = PrimaryDark.copy(alpha = 0.3f),
            onPrimaryContainer = OnPrimaryDark,
            secondary = SecondaryDark,
            onSecondary = OnSecondaryDark,
            secondaryContainer = SecondaryDark.copy(alpha = 0.3f),
            onSecondaryContainer = OnSecondaryDark,
            tertiary = TertiaryDark,
            onTertiary = OnTertiaryDark,
            tertiaryContainer = TertiaryDark.copy(alpha = 0.3f),
            onTertiaryContainer = OnTertiaryDark,
            error = ErrorDark,
            onError = OnErrorDark,
            errorContainer = ErrorDark.copy(alpha = 0.3f),
            onErrorContainer = OnErrorDark,
            background = BackgroundDark,
            onBackground = OnBackgroundDark,
            surface = SurfaceDark,
            onSurface = OnSurfaceDark,
            surfaceVariant = SurfaceVariantDark,
            onSurfaceVariant = OnSurfaceVariantDark,
            outline = OutlineDark,
            inverseSurface = SurfaceLight,
            inverseOnSurface = OnSurfaceLight
        )
    } else {
        lightColorScheme(
            primary = PrimaryLight,
            onPrimary = OnPrimaryLight,
            primaryContainer = PrimaryLight.copy(alpha = 0.15f),
            onPrimaryContainer = PrimaryLight,
            secondary = SecondaryLight,
            onSecondary = OnSecondaryLight,
            secondaryContainer = SecondaryLight.copy(alpha = 0.15f),
            onSecondaryContainer = SecondaryLight,
            tertiary = TertiaryLight,
            onTertiary = OnTertiaryLight,
            tertiaryContainer = TertiaryLight.copy(alpha = 0.15f),
            onTertiaryContainer = TertiaryLight,
            error = ErrorLight,
            onError = OnErrorLight,
            errorContainer = ErrorLight.copy(alpha = 0.15f),
            onErrorContainer = ErrorLight,
            background = BackgroundLight,
            onBackground = OnBackgroundLight,
            surface = SurfaceLight,
            onSurface = OnSurfaceLight,
            surfaceVariant = SurfaceVariantLight,
            onSurfaceVariant = OnSurfaceVariantLight,
            outline = OutlineLight,
            inverseSurface = SurfaceDark,
            inverseOnSurface = OnSurfaceDark
        )
    }

    CompositionLocalProvider(
        LocalLayoutDirectionOverride provides layoutDirection
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = Shapes,
            content = content
        )
    }
}

val Shapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
)
