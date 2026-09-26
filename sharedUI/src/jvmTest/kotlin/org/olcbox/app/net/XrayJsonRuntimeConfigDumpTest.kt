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

            val outbounds = source.getValue("outbounds").jsonArray.toMutableList()
            outbounds[2] = JsonObject(outbounds[2].jsonObject - "settings")
            val missingBlackholeSettings = JsonObject(source + ("outbounds" to JsonArray(outbounds)))
            val adaptedMissing = XrayJsonRuntimeConfig.adapt(
                missingBlackholeSettings, 32001, SocksLogin("local-user", "synthetic-secret")
            )
            assertEquals(missingBlackholeSettings["outbounds"], adaptedMissing.config["outbounds"])
            File(dir, "$name-no-blackhole-settings.json").writeText(adaptedMissing.config.toString())

            val collisionInbounds = missingBlackholeSettings.getValue("inbounds").jsonArray.toMutableList()
            val http = collisionInbounds[1].jsonObject
            collisionInbounds[1] = JsonObject(http + ("tag" to JsonPrimitive("http")))
            val collision = JsonObject(missingBlackholeSettings + ("inbounds" to JsonArray(collisionInbounds)))
            val adaptedCollision = XrayJsonRuntimeConfig.adapt(
                collision, 32001, SocksLogin("local-user", "synthetic-secret")
            )
            assertEquals(collision["outbounds"], adaptedCollision.config["outbounds"])
            File(dir, "$name-http-tag-collision-no-blackhole-settings.json").writeText(adaptedCollision.config.toString())
        }
    }
}
