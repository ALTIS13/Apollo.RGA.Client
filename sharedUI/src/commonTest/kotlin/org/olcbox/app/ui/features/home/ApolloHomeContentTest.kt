package org.olcbox.app.ui.features.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationMetadata
import org.olcbox.app.data.model.SubscriptionMetadata
import org.olcbox.app.net.LocationKind
import org.olcbox.app.ui.SubscriptionDisplayState
import org.olcbox.app.ui.SubscriptionPresentation
import org.olcbox.app.ui.features.locations.LocationItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ApolloHomeContentTest {
    private val dutchWeb = LocationItem(
        storageId = "synthetic-nl-web", fullName = "Netherlands 🇳🇱 · 🚀 RU Relay · VLESS",
        metadata = LocationMetadata(comment = "🎮 raw provider purpose"),
        config = LocationConfig(
            name = "Netherlands 🇳🇱 · 🚀 RU Relay · VLESS", kind = LocationKind.Vless,
            xrayConfig = Json.parseToJsonElement(
                """{"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"direct.example.net"}]},"streamSettings":{"network":"xhttp","security":"reality"}}]}"""
            ).jsonObject
        )
    )
    private val dutchWebTwo = dutchWeb.copy(storageId = "synthetic-nl-web-2")
    private val finnishGaming = LocationItem(
        storageId = "synthetic-fi-games", fullName = "Finland 🇫🇮 · 🎮 RU Relay · Hysteria 2",
        config = LocationConfig(name = "Finland 🇫🇮 · 🎮 RU Relay · Hysteria 2", kind = LocationKind.Hysteria2)
    )
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

    @Test fun heroThenSubscriptionThenCountryGroupsWithHumanModesAndExplicitSelection() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ApolloHomeContent(
                    locations = listOf(dutchWeb, finnishGaming, dutchWebTwo),
                    selectedLocationId = dutchWeb.storageId,
                    subscription = active.copy(remainingBytes = 1_073_741_824),
                    isConnected = false, isConnecting = false, canStartVpn = true,
                    isRefreshing = false, showSettings = false,
                    onAction = {}, onRefresh = {}, onAdd = {}, onSettings = {},
                    onLocationSelected = {}, snackbarHostState = SnackbarHostState()
                )
            }
        }
        val hero = onNodeWithTag("apollo-hero").fetchSemanticsNode().boundsInRoot.top
        val subscription = onNodeWithTag("apollo-subscription-card").fetchSemanticsNode().boundsInRoot.top
        val dutchGroup = onNodeWithTag("apollo-group-Netherlands").fetchSemanticsNode().boundsInRoot.top
        val finnishGroup = onNodeWithTag("apollo-group-Finland").fetchSemanticsNode().boundsInRoot.top
        assertTrue(hero < subscription && subscription < dutchGroup && dutchGroup < finnishGroup)
        onAllNodesWithText("Sites and apps").assertCountEquals(3)
        onNodeWithTag("apollo-selected-mode").assertTextEquals("Sites and apps")
        onNodeWithText("Games and calls").assertExists()
        onAllNodesWithText("🇳🇱 Netherlands").assertCountEquals(2)
        onNodeWithText("One selected connection is used for the whole device.").assertExists()
        onAllNodesWithText("VLESS · XHTTP · Reality").assertCountEquals(2)
        onNodeWithText("Hysteria2").assertExists()
        onNodeWithTag("apollo-country-choice-synthetic-nl-web").assertIsSelected()
        onNodeWithText("🚀", substring = true).assertDoesNotExist()
        onNodeWithText("🎮", substring = true).assertDoesNotExist()
        onAllNodesWithText("Connect").assertCountEquals(1)
        onNodeWithText("Remaining: 1.0 GB").assertExists()
    }

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
        onNodeWithTag("apollo-country-choice-synthetic-fi").performClick()
        assertEquals(finnish.storageId, selected)
        onNodeWithText("Experimental mode").assertDoesNotExist()
    }

    @Test fun selectingALowerCountryKeepsTheConnectActionAvailable() = runComposeUiTest {
        var actions = 0
        val selectedId = mutableStateOf(dutchWeb.storageId)
        val german = finnish.copy(storageId = "synthetic-de", fullName = "🇩🇪 DE · Berlin")
        val singaporean = finnish.copy(storageId = "synthetic-sg", fullName = "🇸🇬 SG · Singapore")
        val american = finnish.copy(storageId = "synthetic-us", fullName = "🇺🇸 US · New York")
        val italian = finnish.copy(storageId = "synthetic-it", fullName = "🇮🇹 IT · Milan")
        val british = finnish.copy(storageId = "synthetic-gb", fullName = "🇬🇧 GB · London")
        val lower = finnish.copy(storageId = "synthetic-jp", fullName = "🇯🇵 JP · Tokyo")
        setContent {
            MaterialTheme {
                ApolloHomeContent(
                    locations = listOf(dutchWeb, finnishGaming, finnish, german, singaporean,
                        american, italian, british, lower),
                    selectedLocationId = selectedId.value,
                    subscription = active,
                    isConnected = false, isConnecting = false, canStartVpn = true,
                    isRefreshing = false, showSettings = false,
                    onAction = { actions++ }, onRefresh = {}, onAdd = {}, onSettings = {},
                    onLocationSelected = { selectedId.value = it }, snackbarHostState = SnackbarHostState()
                )
            }
        }
        onNodeWithTag("apollo-sticky-action").assertDoesNotExist()
        onNodeWithTag("apollo-country-choice-synthetic-jp").performScrollTo()
        onNodeWithTag("apollo-country-choice-synthetic-jp").performClick()
        assertEquals(lower.storageId, selectedId.value)
        onNodeWithTag("apollo-sticky-action").performClick()
        assertEquals(1, actions)
    }

    @Test fun configuredRelayExplainsEachStageWithoutCallingItMeasuredTraffic() = runComposeUiTest {
        val relayed = dutchWeb.copy(config = dutchWeb.config!!.copy(
            xrayConfig = Json.parseToJsonElement(
                """{"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"rw-nl-relay.gate-altas.tech"}]},"streamSettings":{"network":"xhttp","security":"reality"}}]}"""
            ).jsonObject
        ))
        setContent {
            MaterialTheme {
                ApolloHomeContent(
                    locations = listOf(relayed), selectedLocationId = relayed.storageId,
                    subscription = active, isConnected = false, isConnecting = false,
                    canStartVpn = true, isRefreshing = false, showSettings = false,
                    onAction = {}, onRefresh = {}, onAdd = {}, onSettings = {},
                    onLocationSelected = {}, snackbarHostState = SnackbarHostState()
                )
            }
        }
        onNodeWithTag("apollo-route").assertExists()
        onNodeWithText("Device").assertExists()
        onNodeWithText("Russia").assertExists()
        onNodeWithText("Netherlands").assertExists()
        onNodeWithText("Configured path: via Russia → Netherlands").assertExists()
    }

    @Test fun sharedCountryIngressIsExplainedOnceInsteadOfRepeatedInEveryHost() = runComposeUiTest {
        val relayed = dutchWeb.copy(config = dutchWeb.config!!.copy(
            xrayConfig = Json.parseToJsonElement(
                """{"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"rw-nl-relay.gate-altas.tech"}]},"streamSettings":{"network":"xhttp","security":"reality"}}]}"""
            ).jsonObject
        ))
        setContent {
            MaterialTheme {
                ApolloHomeContent(
                    locations = listOf(relayed, relayed.copy(storageId = "synthetic-nl-web-2")),
                    selectedLocationId = relayed.storageId,
                    subscription = active, isConnected = false, isConnecting = false,
                    canStartVpn = true, isRefreshing = false, showSettings = false,
                    onAction = {}, onRefresh = {}, onAdd = {}, onSettings = {},
                    onLocationSelected = {}, snackbarHostState = SnackbarHostState()
                )
            }
        }
        onAllNodesWithText("Via a server in Russia", substring = true).assertCountEquals(1)
        onAllNodesWithText("VLESS · XHTTP · Reality").assertCountEquals(2)
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

    @Test fun selectedExperimentalNodeExposesRadioSelection() = runComposeUiTest {
        val room = LocationItem(
            storageId = "synthetic-room", fullName = "private label",
            config = LocationConfig(name = "private label", id = "room", key = "secret")
        )
        setContent {
            MaterialTheme {
                ApolloHomeContent(
                    locations = listOf(room), selectedLocationId = room.storageId,
                    subscription = active, isConnected = false, isConnecting = false,
                    canStartVpn = true, isRefreshing = false, showSettings = false,
                    onAction = {}, onRefresh = {}, onAdd = {}, onSettings = {},
                    onLocationSelected = {}, snackbarHostState = SnackbarHostState()
                )
            }
        }
        onNodeWithText("Experimental node 1").assertIsSelected()
    }

    @Test fun unsafeUnlocalizedHostFallbackUsesTheCurrentUiLanguage() = runComposeUiTest {
        val unsafe = LocationItem(
            storageId = "synthetic-unknown", fullName = "token SECRET_VALUE",
            config = LocationConfig(name = "token SECRET_VALUE", kind = LocationKind.Vless)
        )
        setContent {
            MaterialTheme {
                ApolloHomeContent(
                    locations = listOf(unsafe), selectedLocationId = unsafe.storageId,
                    subscription = active, isConnected = false, isConnecting = false,
                    canStartVpn = true, isRefreshing = false, showSettings = false,
                    onAction = {}, onRefresh = {}, onAdd = {}, onSettings = {},
                    onLocationSelected = {}, snackbarHostState = SnackbarHostState()
                )
            }
        }
        onAllNodesWithText("Country not specified").assertCountEquals(2)
        onNodeWithText("Unnamed host").assertDoesNotExist()
        onNodeWithText("Страна не указана").assertDoesNotExist()
        onNodeWithText("Хост без названия").assertDoesNotExist()
        onNodeWithText("SECRET_VALUE").assertDoesNotExist()
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
        onNodeWithText("Remaining: 1.0 GB", substring = true).assertExists()
        onAllNodesWithText("Expires:", substring = true).assertCountEquals(1)
        onNodeWithText("Unlimited").assertDoesNotExist()
    }

    @Test fun unknownQuotaDoesNotConsumeASecondSubscriptionLine() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ApolloHomeContent(
                    locations = listOf(finnish), selectedLocationId = finnish.storageId,
                    subscription = active, isConnected = false, isConnecting = false,
                    canStartVpn = true, isRefreshing = false, showSettings = false,
                    onAction = {}, onRefresh = {}, onAdd = {}, onSettings = {},
                    onLocationSelected = {}, snackbarHostState = SnackbarHostState()
                )
            }
        }
        onNodeWithText("Remaining traffic data is unavailable").assertDoesNotExist()
        onNodeWithText("Subscription active").assertExists()
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
        onAllNodesWithText("Subscription expired").assertCountEquals(2)
        onNodeWithText("Connect").assertIsNotEnabled()
    }

    @Test fun exhaustedQuotaKeepsTheSingleHeroActionDisabled() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ApolloHomeContent(
                    locations = listOf(finnish), selectedLocationId = finnish.storageId,
                    subscription = active.copy(state = SubscriptionDisplayState.LIMITED, remainingBytes = 0),
                    isConnected = false, isConnecting = false, canStartVpn = true,
                    isRefreshing = false, showSettings = false,
                    onAction = {}, onRefresh = {}, onAdd = {}, onSettings = {},
                    onLocationSelected = {}, snackbarHostState = SnackbarHostState()
                )
            }
        }
        onAllNodesWithText("Traffic limit reached").assertCountEquals(2)
        onNodeWithTag("apollo-action").assertIsNotEnabled()
        onAllNodesWithText("Connect").assertCountEquals(1)
        onNodeWithText("Remaining: 0 B").assertExists()
    }

    @Test fun explicitDeviceDenialExplainsWhyConnectIsUnavailable() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ApolloHomeContent(
                    locations = listOf(finnish), selectedLocationId = finnish.storageId,
                    subscription = active.copy(state = SubscriptionDisplayState.DEVICE_DENIED),
                    isConnected = false, isConnecting = false, canStartVpn = true,
                    isRefreshing = false, showSettings = false,
                    onAction = {}, onRefresh = {}, onAdd = {}, onSettings = {},
                    onLocationSelected = {}, snackbarHostState = SnackbarHostState()
                )
            }
        }
        onAllNodesWithText("Device access denied").assertCountEquals(2)
        onNodeWithText("Check device access or contact support. Refresh after it changes.").assertExists()
        onNodeWithTag("apollo-action").assertIsNotEnabled()
    }

    @Test fun connectedHeroOffersOnlyDisconnectWithoutASecondFooterAction() = runComposeUiTest {
        var actions = 0
        setContent {
            MaterialTheme {
                ApolloHomeContent(
                    locations = listOf(finnish), selectedLocationId = finnish.storageId,
                    subscription = active, isConnected = true, isConnecting = false,
                    canStartVpn = false, isRefreshing = false, showSettings = false,
                    onAction = { actions++ }, onRefresh = {}, onAdd = {}, onSettings = {},
                    onLocationSelected = {}, snackbarHostState = SnackbarHostState()
                )
            }
        }
        onNodeWithText("Connected").assertExists()
        onAllNodesWithText("Disconnect").assertCountEquals(1)
        onNodeWithText("Disconnect").performClick()
        assertEquals(1, actions)
    }

    @Test fun failedStartHasSafeRecoveryWithoutPrintingRawException() = runComposeUiTest {
        var dismissed = 0
        setContent {
            MaterialTheme {
                ApolloHomeContent(
                    locations = listOf(finnish), selectedLocationId = finnish.storageId,
                    subscription = active, isConnected = false, isConnecting = false,
                    canStartVpn = true, isRefreshing = false, showSettings = false,
                    onAction = {}, onRefresh = {}, onAdd = {}, onSettings = {},
                    onLocationSelected = {}, snackbarHostState = SnackbarHostState(),
                    notice = ApolloConnectionNotice.FAILED,
                    onDismissNotice = { dismissed++ }
                )
            }
        }
        onNodeWithText("Connection failed. Check this destination or try again.").assertExists()
        onNodeWithText("Dismiss").performClick()
        assertEquals(1, dismissed)
        onNodeWithText("Connect").assertExists()
    }
}
