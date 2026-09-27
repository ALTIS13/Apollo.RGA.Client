package org.olcbox.app.data.datasource

// Entirely invented values. Shape only is based on the approved structural snapshot.
internal object XrayJsonSubscriptionFixtures {
    val vless = """{
      "remarks":"Synthetic exit A",
      "log":{"loglevel":"warning"},
      "dns":{"servers":["192.0.2.53","198.51.100.53","localhost"],"queryStrategy":"UseIPv4"},
      "inbounds":[
        {"tag":"local-socks","port":10808,"listen":"127.0.0.1","protocol":"socks","settings":{"udp":true,"auth":"noauth"},"sniffing":{"enabled":true,"routeOnly":true,"destOverride":["http","tls","quic"]}},
        {"tag":"local-http","port":10809,"listen":"127.0.0.1","protocol":"http","settings":{"allowTransparent":false}}
      ],
      "outbounds":[
        {"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"exit-a.invalid","port":443,"users":[{"id":"00000000-0000-4000-8000-000000000000","encryption":"none","flow":""}]}]},
         "streamSettings":{"network":"xhttp","security":"reality","xhttpSettings":{"mode":"auto","host":"cover.invalid","path":"/synthetic","extra":{"xmux":{"maxConcurrency":8}}},"realitySettings":{"serverName":"cover.invalid","publicKey":"SYNTHETIC_PUBLIC_KEY_NOT_REAL","shortId":"00","fingerprint":"chrome"}}},
        {"tag":"direct","protocol":"freedom","settings":{"domainStrategy":"UseIP"}},
        {"tag":"block","protocol":"blackhole","settings":{}}
      ],
      "routing":{"domainMatcher":"hybrid","domainStrategy":"IPIfNonMatch","rules":[
        {"type":"field","protocol":["bittorrent"],"outboundTag":"direct"},
        {"type":"field","domain":["domain:example.invalid"],"inboundTag":["local-socks"],"outboundTag":"proxy"}
      ]}
    }"""
    val hysteria = vless.replace("Synthetic exit A", "Synthetic exit B")
        .replace("\"queryStrategy\":\"UseIPv4\"", "\"queryStrategy\":\"UseIP\"")
        .replace("\"domainStrategy\":\"IPIfNonMatch\"", "\"domainStrategy\":\"AsIs\"")
        .replace(vless.substringAfter("\"outbounds\":[").substringBefore("{\"tag\":\"direct\""), """
        {"tag":"proxy","protocol":"hysteria","settings":{"address":"exit-b.invalid","port":8443,"version":2},
         "streamSettings":{"network":"hysteria","security":"tls","hysteriaSettings":{"version":2,"auth":"SYNTHETIC_AUTH_NOT_REAL"},"tlsSettings":{"serverName":"cover.invalid","enableSessionResumption":true,"fingerprint":"chrome","alpn":["h3"],"pinnedPeerCertSha256":"SYNTHETIC_PIN_NOT_REAL"}}},
        """)
    val body = "[$vless,$hysteria]"

    // Synthetic shape of the non-runnable VLESS/TCP notice emitted after a
    // Remnawave entitlement refusal. No real subscription IDs or endpoints.
    val informationalBody = """[
      {"remarks":"Subscription unavailable","log":{"loglevel":"warning"},
       "dns":{"servers":["192.0.2.53"]},"inbounds":[],
       "outbounds":[
         {"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"0.0.0.0","port":1,"users":[{"id":"00000000-0000-4000-8000-000000000000","encryption":"none"}]}]},
          "streamSettings":{"network":"tcp","security":"none"}},
         {"tag":"direct","protocol":"freedom","settings":{}},
         {"tag":"block","protocol":"blackhole","settings":{}}
       ],"routing":{"rules":[]}},
      {"remarks":"Subscription unavailable 2","log":{"loglevel":"warning"},
       "dns":{"servers":["192.0.2.53"]},"inbounds":[],
       "outbounds":[
         {"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"0.0.0.0","port":1,"users":[{"id":"00000000-0000-4000-8000-000000000000","encryption":"none"}]}]},
          "streamSettings":{"network":"tcp","security":"none"}},
         {"tag":"direct","protocol":"freedom","settings":{}},
         {"tag":"block","protocol":"blackhole","settings":{}}
       ],"routing":{"rules":[]}}
    ]"""
}
