package org.olcbox.app.vpn.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.net.LocationKind
import org.olcbox.app.net.RuntimeLocationPolicy
import org.olcbox.app.vpn.AndroidConnectionMode

class PersonalRestrictedServiceSelectionTest {
    private val importedUrl = "https://pan.gate-altas.tech/SelectedBearer_01"
    private val unrelatedUrl = "https://pan.gate-altas.tech/OtherBearer_02"

    @Test fun onlyTheSelectedOrdinarySubscriptionCanPrefetchPersonalBootstrap() {
        val bundle = LocationBundleV4(
            activeLocationId = "selected",
            locations = listOf(
                LocationEntry.from("other", LocationConfig(kind = LocationKind.Vless), unrelatedUrl),
                LocationEntry.from("selected", LocationConfig(kind = LocationKind.Vless), importedUrl)
            )
        )

        assertEquals(importedUrl, selectPersonalSubscriptionUrl(
            "tech.gatealtas.rga.personal", RuntimeLocationPolicy.ExperimentalDebug,
            AndroidConnectionMode.Tun, bundle, "selected"
        ))
        assertNull(selectPersonalSubscriptionUrl(
            "tech.gatealtas.rga.personal", RuntimeLocationPolicy.ExperimentalDebug,
            AndroidConnectionMode.Tun, bundle, "other"
        ))
    }

    @Test fun ordinaryPackageProxyModeAndManualRoomCannotUsePersonalBootstrap() {
        val bundle = LocationBundleV4(
            activeLocationId = "selected",
            locations = listOf(LocationEntry.from("selected", LocationConfig(kind = LocationKind.Vless), importedUrl))
        )

        assertNull(selectPersonalSubscriptionUrl(
            "tech.gatealtas.rga", RuntimeLocationPolicy.ExperimentalDebug,
            AndroidConnectionMode.Tun, bundle, "selected"
        ))
        assertNull(selectPersonalSubscriptionUrl(
            "tech.gatealtas.rga.personal", RuntimeLocationPolicy.ExperimentalDebug,
            AndroidConnectionMode.Proxy, bundle, "selected"
        ))
        assertNull(selectPersonalSubscriptionUrl(
            "tech.gatealtas.rga.personal", RuntimeLocationPolicy.ExperimentalDebug,
            AndroidConnectionMode.Tun, bundle.copy(locations = listOf(
                LocationEntry.from("selected", LocationConfig(id = "room-123", key = "a".repeat(64)), importedUrl)
            )), "selected"
        ))
    }

    @Test fun aDifferentOrMalformedBearerCannotBeUsedAsFallback() {
        val bundle = LocationBundleV4(
            activeLocationId = "selected",
            locations = listOf(
                LocationEntry.from("selected", LocationConfig(kind = LocationKind.Hysteria2),
                    "https://pan.gate-altas.tech/SelectedBearer_01?token=leak"),
                LocationEntry.from("other", LocationConfig(kind = LocationKind.Vless), unrelatedUrl)
            )
        )

        assertNull(selectPersonalSubscriptionUrl(
            "tech.gatealtas.rga.personal", RuntimeLocationPolicy.ExperimentalDebug,
            AndroidConnectionMode.Tun, bundle, "selected"
        ))
    }
}
