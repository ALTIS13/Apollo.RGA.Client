package org.olcbox.app.util

import kotlin.test.Test
import kotlin.test.assertEquals

class DestinationFlagTest {
    @Test fun compositeCountryFlagIsOneLeadingSymbol() {
        assertEquals("🇳🇱" to "Netherlands via RU", parseEmojiAndName("🇳🇱 Netherlands via RU"))
    }

    @Test fun adjacentNonFlagSymbolsDoNotBecomeACompositeFlag() {
        assertEquals("🏠" to "🏠 Home", parseEmojiAndName("🏠🏠 Home"))
    }
}
