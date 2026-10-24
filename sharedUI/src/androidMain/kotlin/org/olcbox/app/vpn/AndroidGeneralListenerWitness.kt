package org.olcbox.app.vpn

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/** Address-level witness only; never substitutes for native Stop/startup joins. */
internal fun generalListenerAddressReleased(capturedPort: Long): Boolean {
    if (capturedPort !in 1..65535) return false
    return try {
        ServerSocket().use { witness ->
            // Match Go's Linux listener option: TIME_WAIT is not a live listener.
            // Never set SO_REUSEPORT; bind/listen must still reject a live owner.
            witness.reuseAddress = true
            witness.bind(
                InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), capturedPort.toInt()),
                1
            )
        }
        true
    } catch (_: Exception) { false }
}
