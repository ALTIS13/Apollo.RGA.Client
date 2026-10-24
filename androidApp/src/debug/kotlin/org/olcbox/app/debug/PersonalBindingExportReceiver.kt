package org.olcbox.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.olcbox.app.net.PersonalDebugBindingExporter

/** ADB-shell-only trigger for the personal debug installation binding. No secret enters the intent. */
class PersonalBindingExportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_EXPORT || context.packageName != PERSONAL_PACKAGE) return
        PersonalDebugBindingExporter.writeForPersonalDebug(context)
    }

    companion object {
        const val ACTION_EXPORT = "tech.gatealtas.rga.personal.DEBUG_EXPORT_BINDING"
        private const val PERSONAL_PACKAGE = "tech.gatealtas.rga.personal"
    }
}
