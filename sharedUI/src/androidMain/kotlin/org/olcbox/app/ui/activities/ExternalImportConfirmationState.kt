package org.olcbox.app.ui.activities

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Keeps an external subscription link in memory until the person accepts or dismisses it. */
internal class ExternalImportConfirmationState {
    enum class OfferResult { ACCEPTED, ALREADY_PENDING, CONFLICT }

    var pendingLink by mutableStateOf<String?>(null)
        private set
    var hasConflict by mutableStateOf(false)
        private set

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
