package org.olcbox.app.net

import java.io.File
import kotlinx.serialization.json.*
import org.olcbox.app.data.datasource.XrayJsonSubscriptionFixtures
import kotlin.test.Test
import kotlin.test.assertEquals

/** Synthetic adapted subscription shapes for host Xray schema verification.
 * Host acceptance does not prove the packaged Android binary or real traffic.
 */
class XrayJsonRuntimeConfigDumpTest {
    @Test fun dumpAdaptedProfilesForCoreValidation() {
        val vless = XrayJsonSubscriptionFixtures.vless.replace(
            "SYNTHETIC_PUBLIC_KEY_NOT_REAL", "jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0"
        )
        val hy2 = XrayJsonSubscriptionFixtures.hysteria.replace("SYNTHETIC_PIN_NOT_REAL", "0".repeat(64))
        val dir = File("build/xray-runtime-configs").apply { mkdirs() }
        for ((name, body) in listOf("vless-xhttp" to vless, "hysteria2" to hy2)) {
            val source = Json.parseToJsonElement(body).jsonObject
            val adapted = XrayJsonRuntimeConfig.adapt(source, 32001, SocksLogin("local-user", "synthetic-secret"))
            assertEquals(source["outbounds"], adapted.config["outbounds"])
            File(dir, "$name.json").writeText(adapted.config.toString())
        }
    }
}
