package org.olcbox.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.olcbox.app.data.model.RoutingMode
import org.olcbox.app.data.model.RoutingSettings
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class RoutingSettingsScreenTest {
    @Test fun basicRoutingOffersModeChoicesInlineWithoutManualRules() = runComposeUiTest {
        var changed: RoutingSettings? = null
        setContent {
            MaterialTheme {
                RoutingSettingsScreen(
                    settings = RoutingSettings(mode = RoutingMode.Global),
                    enabled = true,
                    showCustomRules = false,
                    onChanged = { changed = it },
                    onBack = {}
                )
            }
        }

        onNodeWithText("All traffic through the tunnel").assertIsSelected()
        onNodeWithText("Bypass Russia").performClick()
        assertEquals(RoutingMode.BypassRussia, changed?.mode)
        onNodeWithText("Always direct").assertDoesNotExist()
    }
}
