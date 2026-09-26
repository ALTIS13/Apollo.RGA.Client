package org.olcbox.app.ui.features.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ApolloCountryHostCardTest {
    @Test fun countryAppearsOnceButEveryChoiceNamesItForAccessibility() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ApolloCountryHostCard(
                    countryTitle = "🇳🇱 Netherlands",
                    choices = listOf(
                        ApolloHostChoice("web", "🇳🇱 Netherlands", "For sites and apps", "VLESS · XHTTP", true),
                        ApolloHostChoice("games", "🇳🇱 Netherlands", "For games and calls", "Hysteria 2", false)
                    ),
                    onSelect = {}
                )
            }
        }

        onAllNodesWithText("🇳🇱 Netherlands").assertCountEquals(1)
        onNodeWithTag("apollo-country-choice-web").assertIsSelected()
        onNodeWithTag("apollo-country-choice-games").assertContentDescriptionEquals(
            "🇳🇱 Netherlands, For games and calls, Hysteria 2"
        )
    }

    @Test fun tappingAChoiceReturnsItsIdentity() = runComposeUiTest {
        var selectedId: String? = null
        setContent {
            MaterialTheme {
                ApolloCountryHostCard(
                    countryTitle = "🇫🇮 Finland",
                    choices = listOf(
                        ApolloHostChoice("web", "🇫🇮 Finland", "For sites and apps", "VLESS", false),
                        ApolloHostChoice("games", "🇫🇮 Finland", "For games and calls", "Hysteria 2", true)
                    ),
                    onSelect = { selectedId = it }
                )
            }
        }

        onNodeWithTag("apollo-country-choice-web").performClick()
        assertEquals("web", selectedId)
    }

    @Test fun mixedIngressDoesNotClaimTheWholeCountryUsesARelay() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ApolloCountryHostCard(
                    countryTitle = "🇫🇮 Finland",
                    choices = listOf(
                        ApolloHostChoice("relayed", "🇫🇮 Finland", "Sites and apps", "VLESS",
                            selected = true, ingress = "Via a server in Russia"),
                        ApolloHostChoice("direct", "🇫🇮 Finland", "Games and calls", "Hysteria 2",
                            selected = false)
                    ),
                    onSelect = {}
                )
            }
        }

        onAllNodesWithText("Via a server in Russia", substring = true).assertCountEquals(1)
        onNodeWithTag("apollo-country-choice-relayed").assertContentDescriptionEquals(
            "🇫🇮 Finland, Sites and apps, VLESS, Via a server in Russia"
        )
        onNodeWithTag("apollo-country-choice-direct").assertContentDescriptionEquals(
            "🇫🇮 Finland, Games and calls, Hysteria 2"
        )
    }
}
