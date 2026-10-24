package org.olcbox.app.vpn.service

import org.junit.Assert.*
import org.junit.Test

class PersonalControlPlaneRulesTest {
    @Test fun subscriptionControlHostWinsOverRegionalDirect() {
        val base = "tunnel-domain:cc\ntunnel-domain:at\ndomain:ru\nfull:pan.gate-altas.tech\n"
        val rules = personalControlPlaneRules(base)
        assertTrue("The existing Remnawave subscription must ride OlcRTC, not RU direct",
            rules.lineSequence().contains("tunnel-domain:pan.gate-altas.tech"))
        assertTrue(rules.indexOf("tunnel-domain:pan.gate-altas.tech") < rules.indexOf("domain:ru"))
        assertTrue(rules.endsWith(base))
    }

    @Test fun controlPlaneRemainsTunnelledWithGlobalRules() {
        val lines = personalControlPlaneRules("").lines().filter(String::isNotEmpty)
        assertEquals(listOf("tunnel-domain:rga.apollot.ru", "tunnel-domain:pan.gate-altas.tech"), lines)
    }

    @Test fun noSubscriptionBearerOrUnrelatedHostsEnterRules() {
        val rules = personalControlPlaneRules("domain:ru\n")
        assertFalse(rules.contains("https://"))
        assertFalse(rules.contains("letscube"))
        assertFalse(rules.contains("apollo.tf"))
        assertTrue(rules.startsWith("tunnel-domain:rga.apollot.ru\n"))
    }
}
