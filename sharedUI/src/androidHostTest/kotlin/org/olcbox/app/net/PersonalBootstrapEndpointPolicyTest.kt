package org.olcbox.app.net

import org.junit.Assert.*
import org.junit.Test

class PersonalBootstrapEndpointPolicyTest {
    private val personal = "tech.gatealtas.rga.personal"
    private val pin = "ab".repeat(32)

    @Test fun remoteBuildSelectsExactPublicOriginWithoutUsbPin() {
        val endpoint = PersonalBootstrapEndpointPolicy.select(personal, true, "https://rga.apollot.ru", "")
        assertNotNull(endpoint)
        assertEquals("https://rga.apollot.ru/v1/personal/bootstrap", endpoint?.urlFor("/v1/personal/bootstrap")?.toExternalForm())
        assertNull(endpoint?.usbCertificatePin)
    }

    @Test fun remoteOriginCannotBeExpandedByUrlSyntaxOrImport() {
        for (origin in listOf("http://rga.apollot.ru", "https://rga.apollot.ru/", "https://rga.apollot.ru:443",
            "https://rga.apollot.ru.evil.invalid", "https://user@rga.apollot.ru", "https://rga.apollot.ru?x=1",
            "https://rga.apollot.ru#x", "https://pan.gate-altas.tech/TestToken_123", " https://rga.apollot.ru")) {
            assertNull(origin, PersonalBootstrapEndpointPolicy.select(personal, true, origin, ""))
        }
    }

    @Test fun contradictoryModesAndOrdinaryPackagesCannotSelectAnyEndpoint() {
        assertNull(PersonalBootstrapEndpointPolicy.select(personal, true, "https://rga.apollot.ru", pin))
        assertNull(PersonalBootstrapEndpointPolicy.select(personal, true, "https://rga.apollot.ru", "invalid"))
        assertNull(PersonalBootstrapEndpointPolicy.select("tech.gatealtas.rga", true, "https://rga.apollot.ru", ""))
        assertNull(PersonalBootstrapEndpointPolicy.select(personal, false, "https://rga.apollot.ru", ""))
        assertNull(PersonalBootstrapEndpointPolicy.select(personal, true, "", ""))
    }

    @Test fun usbModeRetainsFixedLoopbackAndExactPin() {
        val endpoint = PersonalBootstrapEndpointPolicy.select(personal, true, "", pin)
        assertNotNull(endpoint)
        assertEquals("https://127.0.0.1:18443/v1/personal/link/begin", endpoint?.urlFor("/v1/personal/link/begin")?.toExternalForm())
        assertEquals(pin, endpoint?.usbCertificatePin)
    }

    @Test fun exactPathsRejectQueriesTraversalFragmentsAndAbsoluteUrls() {
        val endpoint = PersonalBootstrapEndpointPolicy.select(personal, true, "", pin)!!
        for (path in listOf("/v1/personal/bootstrap?x=1", "/v1/personal/bootstrap#x", "/v1/personal/../bootstrap",
            "//evil.invalid/bootstrap", "https://evil.invalid/bootstrap", "/v1/personal/bootstrap/", "/V1/personal/bootstrap")) {
            assertNull(path, endpoint.urlFor(path))
        }
    }
}
