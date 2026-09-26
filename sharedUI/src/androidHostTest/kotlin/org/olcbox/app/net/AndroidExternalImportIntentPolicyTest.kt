package org.olcbox.app.net

import org.junit.Test
import kotlin.test.assertNull

class AndroidExternalImportIntentPolicyTest {
    private val view = "android.intent.action.VIEW"
    private val synthetic = "apollorga://add?url=https%3A%2F%2Fsubscription.example.test%2Fqa"

    @Test fun rejectsEvenExplicitAppOwnedViewIntent() {
        assertNull(AndroidExternalImportIntentPolicy.allowedData(view, synthetic))
        assertNull(AndroidExternalImportIntentPolicy.allowedData(
            view, "https://foreign.example.test/add#synthetic"))
    }

    @Test fun rejectsBothExactOwnedSchemeHostsOnView() {
        assertNull(AndroidExternalImportIntentPolicy.allowedData(view, synthetic))
        val importHost = "apollorga://import?url=https%3A%2F%2Fsubscription.example.test%2Fqa"
        assertNull(AndroidExternalImportIntentPolicy.allowedData(view, importHost))
    }

    @Test fun rejectsWrongActionsAndUntrustedOrigins() {
        val invalid = listOf(
            "https://foreign.example.test/add#synthetic",
            "https://owned.example.test/import#synthetic",
            "ghostlane://add?url=synthetic",
            "proofkit://add?url=synthetic",
            "apollorga://add.evil.test?url=synthetic",
            "apollorga://eviladd?url=synthetic",
            "apollorga://add.?url=synthetic",
            "apollorga://user@add?url=synthetic",
            "apollorga://add:443?url=synthetic",
            "APOLLORGA://add?url=synthetic",
            "apollorga://Add?url=synthetic",
            "apollorga:add?url=synthetic",
            "apollorga://add?url=" + "a".repeat(20_000),
        )
        invalid.forEach { assertNull(AndroidExternalImportIntentPolicy.allowedData(view, it)) }
        assertNull(AndroidExternalImportIntentPolicy.allowedData(
            "android.intent.action.MAIN", synthetic))
        assertNull(AndroidExternalImportIntentPolicy.allowedData(
            "android.intent.action.SEND", synthetic))
        assertNull(AndroidExternalImportIntentPolicy.allowedData(null, synthetic))
        assertNull(AndroidExternalImportIntentPolicy.allowedData(view, null))
    }
}
