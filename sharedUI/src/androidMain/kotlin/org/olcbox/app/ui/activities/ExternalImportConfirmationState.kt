package org.olcbox.app.ui.activities

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Keeps an external subscription link in memory until the person accepts or dismisses it. */
class ExternalImportConfirmationState {
    enum class OfferResult { ACCEPTED, ALREADY_PENDING, CONFLICT }

    var pendingLink by mutableStateOf<String?>(null)
        private set
    var hasConflict by mutableStateOf(false)
        private set

    /** A recreated Activity must never turn its stored VIEW intent back into a new consent. */
    fun receiveInitialIntent(link: String?, restoredConflict: Boolean?) {
        if (restoredConflict == null) {
            receiveExternalIntent(link)
        } else {
            pendingLink = null
            hasConflict = restoredConflict
        }
    }

    /** Called synchronously for every Activity intent, before UI rendering can conflate events. */
    fun receiveExternalIntent(link: String?) {
        if (link == null) dismiss() else offer(link)
    }

    fun offer(link: String): OfferResult {
        if (hasConflict) return OfferResult.CONFLICT
        return when (pendingLink) {
            null -> {
                pendingLink = link
                OfferResult.ACCEPTED
            }
            link -> OfferResult.ALREADY_PENDING
            else -> {
                // Block further offers until the person closes the conflict.
                pendingLink = null
                hasConflict = true
                OfferResult.CONFLICT
            }
        }
    }

    fun dismiss() {
        pendingLink = null
        hasConflict = false
    }

    fun confirm(onImport: (String) -> Unit) {
        if (hasConflict) return
        val link = pendingLink ?: return
        pendingLink = null
        onImport(link)
    }
}
