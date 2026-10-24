package org.olcbox.app.vpn

import org.olcbox.app.net.GeneralBootstrapV1

/** Provider locator and authority channel are distinct native inputs. */
internal fun configureGeneralNativeRoom(
    metadata: GeneralBootstrapV1,
    setRoom: (String) -> Unit,
    setChannel: (String) -> Unit
) {
    val url = metadata.roomUrl ?: throw IllegalStateException(GENERAL_RUNTIME_DENIED)
    check(metadata.schemaVersion in 2..3 && metadata.hasValidProviderLocator) { GENERAL_RUNTIME_DENIED }
    setRoom(url)
    setChannel(metadata.roomId)
}
