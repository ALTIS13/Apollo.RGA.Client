package org.olcbox.app.vpn.service

/** The personal package's exact owned control plane must not inherit RU DIRECT. */
internal fun personalControlPlaneRules(baseRules: String): String =
    "tunnel-domain:rga.apollot.ru\ntunnel-domain:pan.gate-altas.tech\n" + baseRules
