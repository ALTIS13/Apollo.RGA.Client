package org.olcbox.app.ui.features.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.SubscriptionMetadata
import org.olcbox.app.net.LocationKind
import org.olcbox.app.ui.SubscriptionDisplayState
import org.olcbox.app.ui.SubscriptionPresentation
import org.olcbox.app.ui.features.locations.LocationItem
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ApolloHomeContentTest {
    private val finnish = LocationItem(
        storageId = "synthetic-fi",
        fullName = "🇫🇮 FI · Helsinki",
        config = LocationConfig(name = "🇫🇮 FI · Helsinki", kind = LocationKind.Vless)
    )

    private val active = SubscriptionPresentation(
        state = SubscriptionDisplayState.ACTIVE,
        accessState = SubscriptionMetadata.AccessState.ALLOWED,
        remainingBytes = null,
        expiresAtEpochMs = null,
        refreshError = null
    )

    @Test fun ordinaryHomeShowsDestinationAndOneWorkingConnectAction() = runComposeUiTest {
        var connects = 0
        var selected: String? = null
        setContent {
            MaterialTheme {
                ApolloHomeContent(
                    locations = listOf(finnish), selectedLocationId = finnish.storageId,
                    subscription = active, isConnected = false, isConnecting = false,
                    canStartVpn = true, isRefreshing = false, showSettings = false,
                    onAction = { connects++ }, onRefresh = {}, onAdd = {}, onSettings = {},
                    onLocationSelected = { selected = it }, snackbarHostState = SnackbarHostState()
                )
            }
        }
        onAllNodesWithText("🇫🇮 Finland").assertCountEquals(2)
        onNodeWithText("Subscription active").assertExists()
        onNodeWithText("Route not verified").assertExists()
        onNodeWithText("Connect").performClick()
        assertEquals(1, connects)
        onNodeWithTag("apollo-host-0").performClick()
        assertEquals(finnish.storageId, selected)
        onNodeWithText("Experimental mode").assertDoesNotExist()
    }

    @Test fun experimentalModeIsSeparateAndDoesNotPrintRoomKey() = runComposeUiTest {
        val room = LocationItem(
            storageId = "synthetic-room",
            fullName = "token SECRET_VALUE",
            config = LocationConfig(name = "token SECRET_VALUE", id = "room-id", key = "room-secret")
        )
        setContent {
            MaterialTheme {
                ApolloHomeContent(
                    locations = listOf(finnish, room), selectedLocationId = finnish.storageId,
                    subscription = active, isConnected = false, isConnecting = false,
                    canStartVpn = true, isRefreshing = false, showSettings = false,
                    onAction = {}, onRefresh = {}, onAdd = {}, onSettings = {},
                    onLocationSelected = {}, snackbarHostState = SnackbarHostState()
                )
            }
        }
        onNodeWithText("Experimental mode").assertExists()
        onNodeWithText("token SECRET_VALUE").assertDoesNotExist()
        onNodeWithText("room-secret").assertDoesNotExist()
    }

    @Test fun knownExpiryAndQuotaAreShownWithoutInventingUnlimitedQuota() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ApolloHomeContent(
                    locations = listOf(finnish), selectedLocationId = finnish.storageId,
                    subscription = active.copy(remainingBytes = 1_073_741_824, expiresAtEpochMs = 2_000_000_000_000),
                    isConnected = false, isConnecting = false, canStartVpn = true,
                    isRefreshing = false, showSettings = false,
                    onAction = {}, onRefresh = {}, onAdd = {}, onSettings = {},
                    onLocationSelected = {}, snackbarHostState = SnackbarHostState()
                )
            }
        }
        onNodeWithText("Remaining: 1.0 GB").assertExists()
        onAllNodesWithText("Expires:", substring = true).assertCountEquals(1)
        onNodeWithText("Unlimited").assertDoesNotExist()
    }

    @Test fun expiredAccessDoesNotOfferConnect() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ApolloHomeContent(
                    locations = listOf(finnish), selectedLocationId = finnish.storageId,
                    subscription = active.copy(state = SubscriptionDisplayState.EXPIRED),
                    isConnected = false, isConnecting = false, canStartVpn = true,
                    isRefreshing = false, showSettings = false,
                    onAction = {}, onRefresh = {}, onAdd = {}, onSettings = {},
                    onLocationSelected = {}, snackbarHostState = SnackbarHostState()
                )
            }
        }
        onNodeWithText("Subscription expired").assertExists()
        onNodeWithText("Connect").assertIsNotEnabled()
    }
}
