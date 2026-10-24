package org.olcbox.app.ui.features.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.olcbox.app.ui.features.home.components.AddConfigurationBody
import org.olcbox.app.ui.features.home.components.PkSheetActionRow
import org.olcbox.app.ui.icons.PkIcons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AddConfigurationBodyTest {
    @Test fun otherImportMethodsRemainReachableInShortLandscapeViewport() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(Modifier.width(360.dp).height(260.dp)) {
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
        }
        onNodeWithText("Other ways").performScrollTo().performClick()
        onNodeWithText("Import from file").performScrollTo().assertExists()
    }

    @Test fun importActionTitleWrapsInsteadOfEllipsizingAtLargeFontScale() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                MaterialTheme {
                    Box(Modifier.width(360.dp)) {
                        PkSheetActionRow(
                            title = "Paste copied subscription link",
                            subtitle = "From your clipboard",
                            icon = PkIcons.Input,
                            onClick = {}
                        )
                    }
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        onNodeWithText("Paste copied subscription link", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty() && layouts.none { it.hasVisualOverflow },
            "import action title must remain fully readable at 2× font scale")
    }

    @Test fun androidAddFlowKeepsFileImportBehindOtherWays() = runComposeUiTest {
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
        onNodeWithText("Import from file").assertDoesNotExist()
        onNodeWithText("Other ways").performClick()
        onNodeWithText("Import from file").assertExists()
        onNodeWithText("HTTP, HTTPS, or olcrtc URI").assertDoesNotExist()
        onNodeWithText("Server list or olcrtc URI").assertDoesNotExist()
    }

    @Test fun androidAddFlowOffersCopiedLinkBeforeQrWithoutProviderJargon() = runComposeUiTest {
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
        val paste = onNodeWithText("Paste", substring = true).fetchSemanticsNode().boundsInRoot.top
        val scan = onNodeWithText("Scan QR code").fetchSemanticsNode().boundsInRoot.top
        assertTrue(paste < scan)
        onNodeWithText("From a provider's list").assertDoesNotExist()
    }

    @Test fun manualImportErrorKeepsTheEnteredUrlEditable() = runComposeUiTest {
        val submitted = mutableListOf<String>()
        val synthetic = "https://example.test/sub?token=synthetic"
        var error by mutableStateOf<String?>(null)
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
                    onManualSubscriptionUrl = {
                        submitted += it
                        error = "Unable to import this subscription"
                    },
                    manualImportError = error,
                    onUpdateSubscriptionsClick = {},
                    onAddCustomLocationClick = {}
                )
            }
        }
        onNodeWithText("Enter subscription URL").performClick()
        onNodeWithTag("manual-subscription-url").performTextInput(synthetic)
        onNodeWithText("IMPORT SUBSCRIPTION").performClick()
        assertEquals(listOf(synthetic), submitted)
        onNodeWithTag("manual-subscription-url").assertTextContains(synthetic)
        onNodeWithText("Unable to import this subscription").assertExists()
    }

    @Test fun manualEntryFocusesItsUrlField() = runComposeUiTest {
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
        onNodeWithText("Enter subscription URL").performClick()
        onNodeWithTag("manual-subscription-url").assertIsFocused()
    }

    @Test fun manualImportShowsProgressAndPreventsDuplicateSubmission() = runComposeUiTest {
        setContent {
            MaterialTheme {
                AddConfigurationBody(
                    canScanQr = false,
                    hasSubscriptions = false,
                    showCustomLocation = false,
                    showManualSubscriptionUrl = true,
                    manualImportInProgress = true,
                    onScanQrClick = {},
                    onPasteLinkClick = {},
                    onImportFileClick = {},
                    onUpdateSubscriptionsClick = {},
                    onAddCustomLocationClick = {}
                )
            }
        }
        onNodeWithText("Enter subscription URL").performClick()
        onNodeWithText("CHECKING...").assertIsNotEnabled()
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
