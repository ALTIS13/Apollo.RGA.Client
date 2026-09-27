package org.olcbox.app.ui.features.home

import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.net.LocationKind
import org.olcbox.app.ui.features.locations.LocationItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ApolloReconnectionLabelTest {
    @Test fun experimentalNameIsGenericOnAndroidAndNeverReflectsUntrustedRoomLabel() {
        val item = LocationItem(
            storageId = "synthetic-room",
            fullName = "token SECRET_VALUE",
            config = LocationConfig(name = "token SECRET_VALUE", id = "room", key = "secret")
        )
        val label = reconnectingLocationLabel(item, isAndroid = true,
            experimentalLabel = "Experimental mode", unknownCountryLabel = "Country not specified")
        assertEquals("Experimental mode", label)
        assertFalse(label.orEmpty().contains("SECRET_VALUE"))
    }

    @Test fun backgroundNoticeNeverReflectsUntrustedMessageOnAndroid() {
        assertEquals("Connection information changed.", visibleBackgroundNotice(
            isAndroid = true, rawNotice = "https://example.com/private?token=SECRET_VALUE",
            androidLabel = "Connection information changed."
        ))
    }

    @Test fun unknownOrdinaryDestinationIsLocalizedInAndroidSnackbar() {
        val item = LocationItem(
            storageId = "synthetic-ordinary", fullName = "token SECRET_VALUE",
            config = LocationConfig(name = "token SECRET_VALUE", kind = LocationKind.Vless)
        )
        assertEquals("Country not specified", reconnectingLocationLabel(item, isAndroid = true,
            experimentalLabel = "Experimental mode", unknownCountryLabel = "Country not specified"))
    }
}
