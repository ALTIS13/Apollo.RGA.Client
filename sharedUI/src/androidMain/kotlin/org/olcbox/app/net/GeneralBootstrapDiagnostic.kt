package org.olcbox.app.net

import android.util.Log

/** Fixed vocabulary only: never accepts a URL, key, body, identity or error message. */
internal enum class GeneralBootstrapDiagnostic(val text: String) {
    Link("phase=LINK"), Key("phase=KEY"), Begin("phase=BEGIN"),
    Challenge("phase=CHALLENGE"), Complete("phase=COMPLETE"), Metadata("phase=METADATA"),
    Denied("phase=DENIED"), Cancelled("phase=CANCELLED"),
    Identity("cache=IDENTITY"), CacheDenied("cache=DENIED"),
    Eligible("selection=ELIGIBLE"), Ineligible("selection=INELIGIBLE"),
    Schedule("schedule=ENTERED"), LinkAbsent("schedule=LINK_ABSENT"),
    Busy("schedule=BUSY"), SnapshotAbsent("schedule=SNAPSHOT_ABSENT"),
    Prefetch("prefetch=ENTERED"), Superseded("prefetch=SUPERSEDED"),
    Ready("prefetch=READY"), Unavailable("prefetch=UNAVAILABLE"),
    HttpAccepted("http=ACCEPTED"), HttpDenied("http=DENIED"), NoResponse("http=NO_RESPONSE"),
    DnsFailure("transport=DNS"), TlsFailure("transport=TLS"),
    IoFailure("transport=IO"), OtherFailure("transport=OTHER")
}

internal fun observeGeneralBootstrap(event: GeneralBootstrapDiagnostic) {
    try { Log.d("RgaGeneralBootstrap", event.text) }
    catch (_: Exception) { /* Diagnostic availability is never connection or access authority. */ }
}
