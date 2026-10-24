package org.olcbox.app.ui.features.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationMetadata
import org.olcbox.app.data.model.SubscriptionMetadata
import org.olcbox.app.net.LocationKind
import org.olcbox.app.ui.SubscriptionPresenter
import org.olcbox.app.ui.features.locations.LocationItem
import kotlin.test.Test
import kotlin.test.assertEquals

/** These fail if Android flattens subscriptions into the same country/protocol list. */
@OptIn(ExperimentalTestApi::class)
class ApolloSubscriptionSelectionTest {
    private fun item(id: String, token: String, title: String, host: String = "pan.gate-altas.tech") = LocationItem(
        storageId = id, fullName = "Netherlands 🇳🇱 · VLESS",
        config = LocationConfig(name = "Netherlands 🇳🇱 · VLESS", kind = LocationKind.Vless),
        subscriptionUrl = "https://$host/$token",
        metadata = LocationMetadata(subscription = SubscriptionMetadata(name = title))
    )

    @Test fun onlySelectedSubscriptionsHostsAreRendered() = runComposeUiTest {
        val first = item("first", "fixture_token_1", "Apollo — Alex")
        val second = item("second", "fixture_token_2", "QA — Alex")
        setContent {
            MaterialTheme {
                ApolloHomeContent(listOf(first, second), first.storageId,
                    SubscriptionPresenter.present(first.metadata?.subscription, 1),
                    false, false, canStartVpn = true, isRefreshing = false, showSettings = false,
                    onAction = {}, onRefresh = {}, onAdd = {}, onSettings = {},
                    onLocationSelected = {}, snackbarHostState = SnackbarHostState())
            }
        }
        onAllNodesWithText("VLESS").assertCountEquals(1)
        onNodeWithTag("apollo-subscription-selector").performScrollTo().assertExists()
        onNodeWithText("Apollo — Alex").assertExists()
        onNodeWithText("QA — Alex").assertDoesNotExist()
    }

    @Test fun sameProviderTitlesStillSwitchDistinctSubscriptionIdentities() = runComposeUiTest {
        val first = item("first", "fixture_token_1", "My service")
        val second = item("second", "fixture_token_2", "My service")
        val selected = mutableStateOf(first.storageId)
        setContent {
            MaterialTheme {
                ApolloHomeContent(listOf(first, second), selected.value,
                    SubscriptionPresenter.present(first.metadata?.subscription, 1),
                    false, false, canStartVpn = true, isRefreshing = false, showSettings = false,
                    onAction = {}, onRefresh = {}, onAdd = {}, onSettings = {},
                    onLocationSelected = { selected.value = it }, snackbarHostState = SnackbarHostState())
            }
        }
        onNodeWithTag("apollo-subscription-selector").performScrollTo().performClick()
        onNodeWithTag("apollo-subscription-option-1").performClick()
        assertEquals("second", selected.value)
        onAllNodesWithText("VLESS").assertCountEquals(1)
        onNodeWithText("fixture_token", substring = true).assertDoesNotExist()
    }

    @Test fun activeTunnelCannotSilentlySwitchItsSubscription() = runComposeUiTest {
        val first = item("first", "fixture_token_1", "Apollo")
        val second = item("second", "fixture_token_2", "External", "example.invalid")
        setContent {
            MaterialTheme {
                ApolloHomeContent(listOf(first, second), first.storageId,
                    SubscriptionPresenter.present(first.metadata?.subscription, 1),
                    true, false, canStartVpn = true, isRefreshing = false, showSettings = false,
                    onAction = {}, onRefresh = {}, onAdd = {}, onSettings = {},
                    onLocationSelected = {}, snackbarHostState = SnackbarHostState())
            }
        }
        onNodeWithTag("apollo-subscription-selector").performScrollTo().assertIsNotEnabled()
        onNodeWithText("Disconnect to change subscription").assertExists()
    }

    @Test fun claimedApolloNameDoesNotHideExternalProviderDisclosure() = runComposeUiTest {
        val external = item("external", "fixture_token_external", "Apollo.RGA ✓", "pan.gate-altas.tech.attacker.invalid")
        setContent {
            MaterialTheme {
                ApolloHomeContent(listOf(external), external.storageId,
                    SubscriptionPresenter.present(external.metadata?.subscription, 1),
                    false, false, canStartVpn = true, isRefreshing = false, showSettings = false,
                    onAction = {}, onRefresh = {}, onAdd = {}, onSettings = {},
                    onLocationSelected = {}, snackbarHostState = SnackbarHostState())
            }
        }
        onNodeWithTag("apollo-subscription-provider-info").performScrollTo().performClick()
        onNodeWithText("External subscription").assertExists()
        onNodeWithText("Apollo.RGA OlcRTC is not included.", substring = true).assertExists()
    }

    @Test fun providerTitleCannotRepeatTheShortSubscriptionBearer() = runComposeUiTest {
        val profile = item("first", "fixture_token_1", "Account fixture_token_1")
        setContent {
            MaterialTheme {
                ApolloHomeContent(listOf(profile), profile.storageId,
                    SubscriptionPresenter.present(profile.metadata?.subscription, 1),
                    false, false, canStartVpn = true, isRefreshing = false, showSettings = false,
                    onAction = {}, onRefresh = {}, onAdd = {}, onSettings = {},
                    onLocationSelected = {}, snackbarHostState = SnackbarHostState())
            }
        }
        onNodeWithText("fixture_token_1", substring = true).assertDoesNotExist()
        onNodeWithText("Subscription").assertExists()
    }

    @Test fun unnamedAndExplicitFallbackTitlesRemainDistinct() = runComposeUiTest {
        val unnamed = item("first", "fixture_token_1", "").copy(
            metadata = LocationMetadata(subscription = SubscriptionMetadata()))
        val named = item("second", "fixture_token_2", "Subscription")
        setContent {
            MaterialTheme {
                ApolloHomeContent(listOf(unnamed, named), unnamed.storageId,
                    SubscriptionPresenter.present(unnamed.metadata?.subscription, 1),
                    false, false, canStartVpn = true, isRefreshing = false, showSettings = false,
                    onAction = {}, onRefresh = {}, onAdd = {}, onSettings = {},
                    onLocationSelected = {}, snackbarHostState = SnackbarHostState())
            }
        }
        onNodeWithText("Subscription · Profile 1").assertExists()
        onNodeWithTag("apollo-subscription-selector").performScrollTo().performClick()
        onNodeWithText("Subscription · Profile 2").assertExists()
    }
}
