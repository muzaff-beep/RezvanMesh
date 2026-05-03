package com.rezvani.mesh.ui.theme

import androidx.compose.ui.graphics.Color

// ---- Dark Theme Colors ----
val DarkBackground      = Color(0xFF060606)
val DarkSurface         = Color(0xFF2A3036)
val DarkSurfaceVariant  = Color(0xFF1A1D20)
val DarkBorder          = Color(0xFF3A3F44)

val PrimaryGreen        = Color(0xFF1E6B4E)
val PrimaryGreenHover   = Color(0xFF25805A)
val PrimaryGreenDark    = Color(0xFF1A5A3E)

val DangerRed           = Color(0xFFD32F2F)
val SuccessGreen        = Color(0xFF4CAF50)
val WarningOrange       = Color(0xFFFF9800)

val TextWhite           = Color(0xFFFFFFFF)
val TextPrimaryDark     = Color(0xFFCDCFD0)
val TextMutedDark       = Color(0xFF8A8F94)
val TextDimDark         = Color(0xFF6A7075)
val ErrorContainerDark  = DangerRed.copy(alpha = 0.15f)

// ---- Light Theme Colors ----
val LightBackground     = Color(0xFFF5F5F5)
val LightSurface        = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFE0E0E0)
val LightBorder         = Color(0xFFC0C0C0)

val TextPrimaryLight    = Color(0xFF212121)
val TextMutedLight      = Color(0xFF757575)
val TextDimLight        = Color(0xFF9E9E9E)
val ErrorContainerLight = DangerRed.copy(alpha = 0.08f)
