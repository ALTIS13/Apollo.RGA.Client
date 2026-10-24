package org.olcbox.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.olcbox.app.update.personal.PERSONAL_UPDATE_DIAGNOSTIC_TAG
import org.olcbox.app.update.personal.PersonalUpdateDiagnosticRecords

/** DUMP-gated debug one-shot. Reads only immutable finite records; never starts a controller/check. */
class PersonalUpdateDiagnosticReadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val snapshot = PersonalUpdateDiagnosticRecords.readOrdered(context.packageName, intent.action,
            isOrderedBroadcast, Log.isLoggable(PERSONAL_UPDATE_DIAGNOSTIC_TAG, Log.DEBUG)) ?: return
        resultData = snapshot // No logger/sink is awaited on the receiver/main thread.
    }
}
