package org.olcbox.app.ui.features.onboarding

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ApolloOnboardingScreenTest {
    @Test fun importActionKeepsExistingLiteralLinkFlow() = runComposeUiTest {
        var finished = 0
        var add = 0
        setContent {
            MaterialTheme {
                ApolloOnboardingScreen(onFinished = { finished++ }, onAddServerList = { add++ })
            }
        }
        onNodeWithText("Scan its QR code or paste the exact HTTPS link. No new account is needed.").assertExists()
        onNodeWithText("WebRTC").assertDoesNotExist()
        onNodeWithText("Add my subscription").performClick()
        assertEquals(1, finished)
        assertEquals(1, add)
    }
}
