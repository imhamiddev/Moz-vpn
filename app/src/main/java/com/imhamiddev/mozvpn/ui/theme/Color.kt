package com.imhamiddev.mozvpn.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val MozSeed = Color(0xFF6C4DF6)

// Moz Aurora - Light: soft lavender-white surfaces, violet accent, mint for the protected state.
val md_theme_light_primary = Color(0xFF6C4DF6)
val md_theme_light_onPrimary = Color(0xFFFFFFFF)
val md_theme_light_primaryContainer = Color(0xFFE7E0FF)
val md_theme_light_onPrimaryContainer = Color(0xFF1C0A66)
val md_theme_light_inversePrimary = Color(0xFFB9A8FF)

val md_theme_light_secondary = Color(0xFF5D5877)
val md_theme_light_onSecondary = Color(0xFFFFFFFF)
val md_theme_light_secondaryContainer = Color(0xFFE4E0F2)
val md_theme_light_onSecondaryContainer = Color(0xFF191631)

val md_theme_light_tertiary = Color(0xFF00907B)
val md_theme_light_onTertiary = Color(0xFFFFFFFF)
val md_theme_light_tertiaryContainer = Color(0xFFBDF5E9)
val md_theme_light_onTertiaryContainer = Color(0xFF00201B)

val md_theme_light_error = Color(0xFFBA1A1A)
val md_theme_light_onError = Color(0xFFFFFFFF)
val md_theme_light_errorContainer = Color(0xFFFFDAD6)
val md_theme_light_onErrorContainer = Color(0xFF410002)

val md_theme_light_background = Color(0xFFFAF9FF)
val md_theme_light_onBackground = Color(0xFF14121F)
val md_theme_light_surface = Color(0xFFFAF9FF)
val md_theme_light_onSurface = Color(0xFF14121F)
val md_theme_light_surfaceVariant = Color(0xFFE8E4F4)
val md_theme_light_onSurfaceVariant = Color(0xFF4B4760)
val md_theme_light_surfaceTint = md_theme_light_primary

val md_theme_light_surfaceContainerLowest = Color(0xFFFFFFFF)
val md_theme_light_surfaceContainerLow = Color(0xFFF4F1FD)
val md_theme_light_surfaceContainer = Color(0xFFEEEAF9)
val md_theme_light_surfaceContainerHigh = Color(0xFFE8E4F5)
val md_theme_light_surfaceContainerHighest = Color(0xFFE1DDF0)
val md_theme_light_surfaceBright = Color(0xFFFFFFFF)
val md_theme_light_surfaceDim = Color(0xFFD9D5E6)

val md_theme_light_outline = Color(0xFF857F9C)
val md_theme_light_outlineVariant = Color(0xFFD2CDE4)
val md_theme_light_inverseSurface = Color(0xFF2A2738)
val md_theme_light_inverseOnSurface = Color(0xFFF4F1FD)
val md_theme_light_scrim = Color(0xFF000000)

// Moz Aurora - Dark: deep ink surfaces, luminous violet accent, mint for the protected state.
val md_theme_dark_primary = Color(0xFF5B9BFF)
val md_theme_dark_onPrimary = Color(0xFF00205C)
val md_theme_dark_primaryContainer = Color(0xFF0F3AA8)
val md_theme_dark_onPrimaryContainer = Color(0xFFDCE6FF)
val md_theme_dark_inversePrimary = Color(0xFF1F5FFF)

val md_theme_dark_secondary = Color(0xFFB4C0DA)
val md_theme_dark_onSecondary = Color(0xFF1E2A44)
val md_theme_dark_secondaryContainer = Color(0xFF2A3654)
val md_theme_dark_onSecondaryContainer = Color(0xFFDCE4F7)

val md_theme_dark_tertiary = Color(0xFF5CCBF0)
val md_theme_dark_onTertiary = Color(0xFF003545)
val md_theme_dark_tertiaryContainer = Color(0xFF004D63)
val md_theme_dark_onTertiaryContainer = Color(0xFFBDEBFF)

val md_theme_dark_error = Color(0xFFFFB4AB)
val md_theme_dark_onError = Color(0xFF690005)
val md_theme_dark_errorContainer = Color(0xFF93000A)
val md_theme_dark_onErrorContainer = Color(0xFFFFDAD6)

val md_theme_dark_background = Color(0xFF000000)
val md_theme_dark_onBackground = Color(0xFFE8EDF7)
val md_theme_dark_surface = Color(0xFF000000)
val md_theme_dark_onSurface = Color(0xFFE8EDF7)
val md_theme_dark_surfaceVariant = Color(0xFF1E2636)
val md_theme_dark_onSurfaceVariant = Color(0xFFA9B4C8)
val md_theme_dark_surfaceTint = md_theme_dark_primary

val md_theme_dark_surfaceContainerLowest = Color(0xFF000000)
val md_theme_dark_surfaceContainerLow = Color(0xFF0A0E16)
val md_theme_dark_surfaceContainer = Color(0xFF0F1520)
val md_theme_dark_surfaceContainerHigh = Color(0xFF151C2A)
val md_theme_dark_surfaceContainerHighest = Color(0xFF1C2535)
val md_theme_dark_surfaceBright = Color(0xFF222C3E)
val md_theme_dark_surfaceDim = Color(0xFF05070C)

val md_theme_dark_outline = Color(0xFF6B7690)
val md_theme_dark_outlineVariant = Color(0xFF263044)
val md_theme_dark_inverseSurface = Color(0xFFE8EDF7)
val md_theme_dark_inverseOnSurface = Color(0xFF1B2230)
val md_theme_dark_scrim = Color(0xFF000000)

data class MozStatusColors(
    val connected: Color,
    val connecting: Color,
    val disconnected: Color,
)

val LightStatusColors = MozStatusColors(
    connected = Color(0xFF1F5FFF),
    connecting = Color(0xFFB26A00),
    disconnected = md_theme_light_onSurfaceVariant,
)

val DarkStatusColors = MozStatusColors(
    connected = Color(0xFF5B9BFF),
    connecting = Color(0xFFFFC46B),
    disconnected = md_theme_dark_onSurfaceVariant,
)

val LocalMozStatusColors = staticCompositionLocalOf { LightStatusColors }
