package org.olcbox.app.net

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OlcrtcMandatoryRulesTest {
    @Test fun mandatoryTunnelSuffixesAreSentBeforeOverlappingCustomDirectRules() = runTest {
        val routing = Routing.Rules(
            "/data/rules", DirectDns.Servers(emptyList()), Routing.Policy.Tunnel,
            CustomRules.of(direct = listOf("vk.cc", "icq.at"), tunnel = emptyList()),
            mandatoryTunnelSuffixes = listOf("example", "cc")
        )
        assertEquals(
            "tunnel-domain:cc\ntunnel-domain:at\ntunnel-domain:example\ndomain:vk.cc\ndomain:icq.at\n",
            OlcrtcDirectRules.forRouting(routing)
        )
    }

    @Test fun russianBypassKeepsRuAndLocalRulesButCarriesForcedProxyPrecedence() = runTest {
        val rules = OlcrtcDirectRules.forRouting(
            Routing.Rules("/data/rules", DirectDns.Servers(emptyList()), "ru")
        ).lines()
        assertEquals(listOf("tunnel-domain:cc", "tunnel-domain:at"), rules.take(2))
        assertTrue("domain:ru" in rules)
        assertTrue("10.0.0.0/8" in rules)
        // The deny must override these positives in native, not merely delete four names.
        assertTrue("domain:vk.cc" in rules)
        assertTrue("domain:icq.at" in rules)
    }

    @Test fun globalStillSendsNoRulesAndLegacyTextDoesNotRequireNewNativeSyntax() = runTest {
        assertEquals("", OlcrtcDirectRules.forRouting(Routing.Global))
        val legacy = OlcrtcDirectRules.text(XrayGeodata.Lists(listOf("domain:vk.cc"), emptyList()))
        assertTrue("domain:vk.cc" in legacy.lines())
        assertTrue(legacy.lines().none { it.startsWith("tunnel-domain:") })
    }
}
