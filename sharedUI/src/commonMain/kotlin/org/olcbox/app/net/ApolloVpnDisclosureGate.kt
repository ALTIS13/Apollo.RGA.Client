package org.olcbox.app.net

import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.repository.APOLLO_VPN_DISCLOSURE_VERSION

/** Android service gate shared by app, Quick Settings, always-on and reconnect starts. */
internal object ApolloVpnDisclosureGate {
    const val NOTICE_REQUIRED = "Open Apollo.RGA and accept the VPN notice"

    fun denial(bundle: LocationBundleV4): String? =
        if (bundle.apolloVpnDisclosureVersion == APOLLO_VPN_DISCLOSURE_VERSION &&
            bundle.apolloVpnDisclosureAcceptedAt != null) null else NOTICE_REQUIRED
}
