package org.olcbox.app.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidManagedBootstrapProducerTest {
    @Test fun reserveDiscoveryUsesItsOwnPathAndCannotAcceptPrimaryMetadata(): Unit = runBlocking {
        val reserve = managedMetadata().replace("\"provider\":\"telemost\"", "\"provider\":\"salutejazz\"")
            .replace("\"transport\":\"vp8channel\"", "\"transport\":\"datachannel\"")
            .replace("https://telemost.yandex.ru/j/12345678901234", "abc123:abcd1234")
        for (reply in listOf(reserve, managedMetadata())) {
            val paths = mutableListOf<String>()
            val producer = AndroidVerifiedGeneralBootstrapProducer.managedReserve(GeneralBootstrapHttpTransport { path, _ ->
                paths += path
                GeneralTestFixture.response(if (path.endsWith("/link/begin")) GeneralTestFixture.challenge() else reply)
            }) { GeneralTestFixture.now }
            val result = producer.completeLinkAndPrefetch(GeneralTestFixture.link, GeneralTestFixture.signer())
            assertEquals(listOf("/v1/managed/reserve/link/begin", "/v1/managed/reserve/bootstrap"), paths)
            if (reply == reserve) assertEquals("salutejazz", result!!.provider) else assertNull(result)
        }
    }
    @Test fun explicitManagedDiscoveryUsesOnlyItsEndpointsAndRejectsLegacyResponse(): Unit = runBlocking {
        for (reply in listOf(managedMetadata(), GeneralTestFixture.metadata())) {
            val paths = mutableListOf<String>()
            val producer = AndroidVerifiedGeneralBootstrapProducer.managed(GeneralBootstrapHttpTransport { path, _ ->
                paths += path
                GeneralTestFixture.response(if (path == "/v1/managed/link/begin") GeneralTestFixture.challenge() else reply)
            }) { GeneralTestFixture.now }
            val result = producer.completeLinkAndPrefetch(GeneralTestFixture.link, GeneralTestFixture.signer())
            assertEquals(listOf("/v1/managed/link/begin", "/v1/managed/bootstrap"), paths)
            if (reply == managedMetadata()) assertEquals(3, result!!.schemaVersion) else assertNull(result)
        }
    }
    @Test fun legacyProducerCannotReinterpretManagedMetadata(): Unit = runBlocking {
        var calls = 0
        val producer = AndroidVerifiedGeneralBootstrapProducer(GeneralBootstrapHttpTransport { _, _ ->
            GeneralTestFixture.response(if (++calls == 1) GeneralTestFixture.challenge() else managedMetadata())
        }) { GeneralTestFixture.now }
        assertNull(producer.completeLinkAndPrefetch(GeneralTestFixture.link, GeneralTestFixture.signer()))
        assertEquals(2, calls)
    }
}
