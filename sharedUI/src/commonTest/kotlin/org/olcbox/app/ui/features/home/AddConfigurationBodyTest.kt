package org.olcbox.app.ui.features.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import org.olcbox.app.ui.features.home.components.AddConfigurationBody
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AddConfigurationBodyTest {
    @Test fun androidAddFlowOffersManualSubscriptionEntryAlongsideExistingImports() = runComposeUiTest {
        setContent {
            MaterialTheme {
                AddConfigurationBody(
                    canScanQr = true,
                    hasSubscriptions = false,
                    showCustomLocation = false,
                    showManualSubscriptionUrl = true,
                    onScanQrClick = {},
                    onPasteLinkClick = {},
                    onImportFileClick = {},
                    onUpdateSubscriptionsClick = {},
                    onAddCustomLocationClick = {}
                )
            }
        }
        onNodeWithText("Enter subscription URL").assertExists()
        onNodeWithText("Scan QR code").assertExists()
        onNodeWithText("Import from file").assertExists()
        onNodeWithText("HTTP, HTTPS, or olcrtc URI").assertDoesNotExist()
        onNodeWithText("Server list or olcrtc URI").assertDoesNotExist()
    }

    @Test fun submittedSubscriptionUrlIsRemovedFromTheEntryField() = runComposeUiTest {
        val submitted = mutableListOf<String>()
        val synthetic = "https://example.test/sub?token=synthetic"
        setContent {
            MaterialTheme {
                AddConfigurationBody(
                    canScanQr = false,
                    hasSubscriptions = false,
                    showCustomLocation = false,
                    showManualSubscriptionUrl = true,
                    onScanQrClick = {},
                    onPasteLinkClick = {},
                    onImportFileClick = {},
                    onManualSubscriptionUrl = { submitted += it },
                    onUpdateSubscriptionsClick = {},
                    onAddCustomLocationClick = {}
                )
            }
        }
        onNodeWithText("Enter subscription URL").performClick()
        onNodeWithTag("manual-subscription-url").performTextInput(synthetic)
        onNodeWithText("IMPORT SUBSCRIPTION").performClick()
        assertEquals(listOf(synthetic), submitted)
        onNodeWithText("Enter subscription URL").performClick()
        onNodeWithText(synthetic).assertDoesNotExist()
    }

    @Test fun manualEntryReplacesOtherRowsSoSubmitIsNotPushedBelowTheKeyboard() = runComposeUiTest {
        setContent {
            MaterialTheme {
                AddConfigurationBody(
                    canScanQr = true,
                    hasSubscriptions = true,
                    showCustomLocation = false,
                    showManualSubscriptionUrl = true,
                    onScanQrClick = {},
                    onPasteLinkClick = {},
                    onImportFileClick = {},
                    onUpdateSubscriptionsClick = {},
                    onAddCustomLocationClick = {}
                )
            }
        }
        onNodeWithText("Enter subscription URL").performClick()
        onNodeWithTag("manual-subscription-url").assertExists()
        onNodeWithText("IMPORT SUBSCRIPTION").assertExists()
        onNodeWithText("Scan QR code").assertDoesNotExist()
        onNodeWithText("Import from file").assertDoesNotExist()
        onNodeWithText("Update server lists").assertDoesNotExist()
    }
}
