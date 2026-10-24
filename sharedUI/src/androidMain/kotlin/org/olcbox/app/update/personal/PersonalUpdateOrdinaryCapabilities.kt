package org.olcbox.app.update.personal

import android.net.NetworkCapabilities
import android.net.NetworkSpecifier
import android.os.Build

/** Public routing/ownership facts, not a full NetworkCapabilities.equals clone.
 * RSSI, bandwidth estimates and transportInfo are deliberately not authority.
 * Hidden UID ranges, allowed/admin UIDs, requestor, SSID, privateDnsBroken,
 * forbidden capabilities and underlying-network facts cannot be captured through
 * the public SDK. Network identity and full LinkProperties
 * remain independent manager fences. Ordinary OTA is scoped to SDK 31..37 only.
 */
internal data class PersonalUpdateOrdinaryCapabilities(
    val capabilities: Set<Int>,
    val transports: Set<Int>,
    val ownerUid: Int,
    val specifier: NetworkSpecifier?,
    val subscriptionIds: Set<Int>,
    val enterpriseIds: Set<Int>,
)

internal fun personalUpdateOrdinaryCapabilities(value: NetworkCapabilities?): PersonalUpdateOrdinaryCapabilities? {
    if (value == null || Build.VERSION.SDK_INT !in 31..37) return null
    return PersonalUpdateOrdinaryCapabilities(
        capabilities = value.capabilities.toSet(),
        // getTransportTypes is hidden. Public hasTransport covers the framework's
        // 64-bit mask domain without a whitelist of currently named transports.
        transports = (0..63).filter(value::hasTransport).toSet(),
        ownerUid = value.ownerUid,
        specifier = value.networkSpecifier,
        subscriptionIds = if (Build.VERSION.SDK_INT >= 35) value.subscriptionIds.toSet() else emptySet(),
        enterpriseIds = if (Build.VERSION.SDK_INT >= 33) value.enterpriseIds.toSet() else emptySet(),
    )
}
