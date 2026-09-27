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
    // Keep Android on Apollo's graphite/violet field regardless of system dynamic color.
    val isDarkState = remember { mutableStateOf(true) }
    val typography = getAppTypography()

    CompositionLocalProvider(
        LocalThemeIsDark provides isDarkState,
        LocalPkPalette provides PkPalette(
            accent = Color(0xFF8256EE), accentSoft = Color(0x1A8B5CF6),
            accent2 = Color(0xFFB9A4FF), accent2Soft = Color(0x1AB9A4FF),
            success = Color(0xFF3DDC97), successSoft = Color(0x1A3DDC97),
            warning = Color(0xFFF5B13D), danger = Color(0xFFF4635F),
            textDim = Color(0xFFAAB6C8), textMuted = Color(0xFF93A0B4),
            gridLine = Color(0x0EFFFFFF),
            seatFree = Color(0xFF0C1017), seatOther = Color(0xFF69758A),
            link = Color(0xFFB9A4FF), hairline = Color(0xFF212936)
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
