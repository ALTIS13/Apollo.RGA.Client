package org.olcbox.app.net

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidExternalImportIntentPolicyTest {
    private val view = "android.intent.action.VIEW"
    private val synthetic = "apollorga://add?url=https%3A%2F%2Fsubscription.example.test%2Fqa"
    private val ownedLink = "https://rga.apollot.ru/import#https%3A%2F%2Fpan.gate-altas.tech%2Fqa"

    @Test fun acceptsOnlyExactApolloHttpsImportAndReturnsLiteralSubscriptionUrl() {
        assertEquals("https://pan.gate-altas.tech/qa",
            AndroidExternalImportIntentPolicy.allowedData(view, ownedLink))
        assertEquals("https://pan.gate-altas.tech/qa",
            AndroidExternalImportIntentPolicy.allowedData(
                view, "https://rga.apollot.ru/import#https://pan.gate-altas.tech/qa"))
    }

    @Test fun rejectsForeignNestedSubscriptionHostWithoutAffectingManualImport() {
        val foreignPayloads = listOf(
            "https://subscription.example.test/qa",
            "https://pan.gate-altas.tech.evil.test/qa",
            "https://pan.gate-altas.tech:444/qa"
        )
        foreignPayloads.forEach { payload ->
            assertNull(AndroidExternalImportIntentPolicy.allowedData(
                view, "https://rga.apollot.ru/import#$payload"))
        }
        assertNull(AndroidExternalImportIntentPolicy.allowedData(view,
            "https://rga.apollot.ru/import#https%3A%2F%2Fsubscription.example.test%2Fqa"))
    }

    @Test fun rejectsUnownedAndLegacySchemes() {
        assertNull(AndroidExternalImportIntentPolicy.allowedData(view, synthetic))
        assertNull(AndroidExternalImportIntentPolicy.allowedData(
            view, "https://foreign.example.test/add#synthetic"))
    }

    @Test fun rejectsOldSchemeAndLookalikeHttpsEnvelopes() {
        assertNull(AndroidExternalImportIntentPolicy.allowedData(view, synthetic))
        val importHost = "apollorga://import?url=https%3A%2F%2Fsubscription.example.test%2Fqa"
        assertNull(AndroidExternalImportIntentPolicy.allowedData(view, importHost))
        val lookalikes = listOf(
            "https://rga.apollot.ru.evil.test/import#https%3A%2F%2Fsubscription.example.test%2Fqa",
            "https://rga.apollot.ru/Import#https%3A%2F%2Fsubscription.example.test%2Fqa",
            "https://rga.apollot.ru/import?source=page#https%3A%2F%2Fsubscription.example.test%2Fqa",
            "http://rga.apollot.ru/import#https%3A%2F%2Fsubscription.example.test%2Fqa",
            "https://rga.apollot.ru/import#http%3A%2F%2Fsubscription.example.test%2Fqa",
        )
        lookalikes.forEach { assertNull(AndroidExternalImportIntentPolicy.allowedData(view, it)) }
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
