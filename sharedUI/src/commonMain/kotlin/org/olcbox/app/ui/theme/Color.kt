package org.olcbox.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

// Legacy palette retained for desktop/iOS until their surfaces move to Apollo Orbit.
internal val OlcboxDarkColorScheme = darkColorScheme(
    primary = Color(0xFF6675FF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF1A1E3D),
    onPrimaryContainer = Color(0xFF8190FF),
    inversePrimary = Color(0xFF6675FF),
    secondary = Color(0xFF8B93A8),
    onSecondary = Color(0xFF07080D),
    secondaryContainer = Color(0xFF161822),
    onSecondaryContainer = Color(0xFFE8ECF2),
    tertiary = Color(0xFFB5F23D),
    onTertiary = Color(0xFF0C1300),
    tertiaryContainer = Color(0xFF232B10),
    onTertiaryContainer = Color(0xFFB5F23D),
    background = Color(0xFF07080D),
    onBackground = Color(0xFFE8ECF2),
    surface = Color(0xFF07080D),
    onSurface = Color(0xFFE8ECF2),
    surfaceVariant = Color(0xFF161822),
    onSurfaceVariant = Color(0xFF8B93A8),
    surfaceContainerLowest = Color(0xFF05060A),
    surfaceContainerLow = Color(0xFF0A0C14),
    surfaceContainer = Color(0xFF0F1117),
    surfaceContainerHigh = Color(0xFF161822),
    surfaceContainerHighest = Color(0xFF1D2030),
    inverseSurface = Color(0xFFE8ECF2),
    inverseOnSurface = Color(0xFF07080D),
    outline = Color(0xFF2A2D42),
    outlineVariant = Color(0xFF1E2030),
    error = Color(0xFFF43F5E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF3D141C),
    onErrorContainer = Color(0xFFF43F5E),
    scrim = Color(0xFF000000)
)

/** Android's approved Apollo Orbit field, mapped to Material 3 roles. */
internal val ApolloDarkColorScheme = darkColorScheme(
    primary = Color(0xFF33E6FF),
    onPrimary = Color(0xFF07182F),
    primaryContainer = Color(0xFF14395A),
    onPrimaryContainer = Color(0xFFD8F9FF),
    secondary = Color(0xFF8DB8FF),
    onSecondary = Color(0xFF07182F),
    secondaryContainer = Color(0xFF203B66),
    onSecondaryContainer = Color(0xFFE5EDFF),
    tertiary = Color(0xFFC6AEFF),
    onTertiary = Color(0xFF1A1236),
    tertiaryContainer = Color(0xFF38285E),
    onTertiaryContainer = Color(0xFFF0E8FF),
    background = Color(0xFF07182F),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF07182F),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF18324F),
    onSurfaceVariant = Color(0xFFD1E2F4),
    surfaceContainerLowest = Color(0xFF061426),
    surfaceContainerLow = Color(0xFF0A2039),
    surfaceContainer = Color(0xFF102945),
    surfaceContainerHigh = Color(0xFF183653),
    surfaceContainerHighest = Color(0xFF234160),
    outline = Color(0xFF6F8BA8),
    outlineVariant = Color(0xFF36516F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)
