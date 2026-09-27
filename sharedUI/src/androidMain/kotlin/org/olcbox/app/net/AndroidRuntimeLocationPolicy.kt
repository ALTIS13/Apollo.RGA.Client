package org.olcbox.app.net

import android.content.Context
import android.content.pm.ApplicationInfo

/** Uses the installed package's build identity in both Activity and VPN service processes. */
object AndroidRuntimeLocationPolicy {
    fun forApp(context: Context): RuntimeLocationPolicy = runCatching {
        fromFlags(context.applicationInfo.flags)
    }.getOrDefault(RuntimeLocationPolicy.OrdinaryOnly)

    fun fromFlags(flags: Int): RuntimeLocationPolicy =
        if (flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) RuntimeLocationPolicy.ExperimentalDebug
        else RuntimeLocationPolicy.OrdinaryOnly
}
