package org.olcbox.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

@Composable
actual fun AppTheme(
    useDynamicColor: Boolean,
    content: @Composable () -> Unit
) {
    // The approved Apollo Orbit direction uses a fixed navy field on Android.
    val isDarkState = remember { mutableStateOf(true) }
    val typography = getAppTypography()

    CompositionLocalProvider(
        LocalThemeIsDark provides isDarkState,
        LocalPkPalette provides PkPalette(
            accent = Color(0xFF33E6FF), accentSoft = Color(0x1A33E6FF),
            accent2 = Color(0xFF3187FF), accent2Soft = Color(0x1A3187FF),
            link = Color(0xFF8DB8FF), textDim = Color(0xFFD1E2F4),
            textMuted = Color(0xFF9DB7D2), hairline = Color(0xFF36516F)
        )
    ) {
        MaterialTheme(
            colorScheme = ApolloDarkColorScheme,
            typography = typography
        ) {
            ProvideTextStyle(MaterialTheme.typography.bodyMedium, content)
        }
    }
}
