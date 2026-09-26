package org.olcbox.app.net

/** External intents stay inert in every build until an owned App Link and confirmation UI ship. */
object AndroidExternalImportIntentPolicy {
    /** No external source can import until owned App Link verification and confirmation both exist. */
    @Suppress("UNUSED_PARAMETER")
    fun allowedData(action: String?, data: String?): String? = null
}
