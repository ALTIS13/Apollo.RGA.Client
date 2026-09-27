package org.olcbox.app.net

import org.olcbox.app.data.model.LocationBundleV4

/** Installed-client capability, never a setting read from a subscription or an intent. */
enum class RuntimeLocationPolicy {
    OrdinaryOnly,
    ExperimentalDebug;

    fun permits(kind: LocationKind): Boolean =
        kind != LocationKind.Olcrtc || this == ExperimentalDebug

    fun visible(bundle: LocationBundleV4): LocationBundleV4 {
        val entries = bundle.locations.filter { permits(it.location.kind) }
        return bundle.copy(
            activeLocationId = bundle.activeLocationId?.takeIf { id -> entries.any { it.storageId == id } },
            locations = entries
        )
    }

    fun denial(kind: LocationKind): String? =
        if (permits(kind)) null else "Experimental mode unavailable"
}
