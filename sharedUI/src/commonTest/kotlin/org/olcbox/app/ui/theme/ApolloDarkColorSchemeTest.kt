package org.olcbox.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertTrue

class ApolloDarkColorSchemeTest {
    @Test
    fun primaryActionIsVioletAndKeepsItsLabelReadable() {
        val scheme = ApolloDarkColorScheme

        assertTrue(
            scheme.primary.blue > scheme.primary.red && scheme.primary.red > scheme.primary.green,
            "the Android primary action must stay violet, not cyan or green"
        )
        assertTrue(contrast(scheme.primary, scheme.onPrimary) >= 4.5f)
    }

    @Test
    fun graphiteCanvasKeepsBodyAndSecondaryCopyReadable() {
        val scheme = ApolloDarkColorScheme

        assertTrue(scheme.background.blue - scheme.background.red < 0.06f)
        assertTrue(contrast(scheme.background, scheme.onBackground) >= 7f)
        assertTrue(contrast(scheme.surfaceContainer, scheme.onSurfaceVariant) >= 4.5f)
    }

    private fun contrast(a: Color, b: Color): Float {
        val lighter = maxOf(a.luminance(), b.luminance())
        val darker = minOf(a.luminance(), b.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }
}
