package org.olcbox.app.vpn

import android.content.Context

/** Compile-selected public-only factory discovery; never JNI or admission. */
internal object AndroidGeneralRuntimeProvider {
    private const val FACTORY = "org.olcbox.app.vpn.AndroidGeneralOlcRtcRuntimeFactory"
    fun forApp(context: Context): AndroidGeneralRuntimeHandleFactory? = loadFactory(context.packageName) {
        Class.forName(FACTORY).getDeclaredConstructor().newInstance() as AndroidGeneralSelectionRuntimeHandleFactory
    }
    internal fun loadFactory(packageName: String, loader: () -> AndroidGeneralRuntimeHandleFactory): AndroidGeneralRuntimeHandleFactory? =
        if (packageName == "tech.gatealtas.rga") try { loader() } catch (_: Exception) { null } else null
}
