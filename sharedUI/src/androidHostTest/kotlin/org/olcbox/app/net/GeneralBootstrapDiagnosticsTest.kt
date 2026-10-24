package org.olcbox.app.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class GeneralBootstrapDiagnosticsTest {
    @Test fun failedBeginIdentifiesOnlyFixedBoundaryWithoutPrivateInput(): Unit = runBlocking {
        ShadowLog.clear()
        val producer = AndroidVerifiedGeneralBootstrapProducer(GeneralBootstrapHttpTransport { _, _ ->
            throw IllegalStateException("private response TestToken_123 secret-body")
        }) { GeneralTestFixture.now }
        assertNull(producer.completeLinkAndPrefetch(GeneralTestFixture.link, GeneralTestFixture.signer()))
        val messages = ShadowLog.getLogsForTag("RgaGeneralBootstrap").map { it.msg }
        assertEquals(listOf("phase=LINK", "phase=KEY", "phase=BEGIN", "phase=DENIED"), messages)
        assertFalse(messages.any { it.contains("TestToken_123") || it.contains("secret-body") || it.contains(GeneralTestFixture.link) })
    }

    @Test fun transportCancellationIsRecordedButStillPropagates(): Unit = runBlocking {
        ShadowLog.clear()
        val producer = AndroidVerifiedGeneralBootstrapProducer(GeneralBootstrapHttpTransport { _, _ ->
            throw CancellationException("private detail")
        }) { GeneralTestFixture.now }
        try { producer.completeLinkAndPrefetch(GeneralTestFixture.link, GeneralTestFixture.signer()); fail("Cancellation swallowed") }
        catch (_: CancellationException) { }
        assertEquals(listOf("phase=LINK", "phase=KEY", "phase=BEGIN", "phase=CANCELLED"),
            ShadowLog.getLogsForTag("RgaGeneralBootstrap").map { it.msg })
    }
}
