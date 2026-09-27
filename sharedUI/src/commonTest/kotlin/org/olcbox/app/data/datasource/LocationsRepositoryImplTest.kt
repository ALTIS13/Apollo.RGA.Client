package org.olcbox.app.data.datasource

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.test.runTest
import org.olcbox.app.CurrentAppInfo
import org.olcbox.app.crypt.CryptCodec
import org.olcbox.app.data.identity.DeviceIdentityProvider
import org.olcbox.app.data.identity.PersistentDeviceIdentityProvider
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.data.model.RoutingMode
import org.olcbox.app.data.model.RoutingSettings
import org.olcbox.app.data.model.SubscriptionSettings
import org.olcbox.app.data.share.ConfigShareService
import io.ktor.http.HttpStatusCode
import org.olcbox.app.net.PartnerLinkResolver
import org.olcbox.app.net.PartnerLinkResult
import org.olcbox.app.net.HttpPartnerLinkResolver
import org.olcbox.app.net.RuntimeLocationPolicy
import org.olcbox.app.net.SubscriptionTunnelAccess
import org.olcbox.app.data.repository.SubscriptionRefreshError
import kotlin.test.assertFalse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocationsRepositoryImplTest {
    @Test fun firstLiteralImportKeepsEmptyDevicesSettingsRoutingAndAcknowledgements() = runTest {
        val localSettings = SubscriptionSettings(connectOnLaunch = true, autoUpdate = false)
        val localRouting = RoutingSettings(mode = RoutingMode.Global, verboseDebugLogs = true)
        val source = FakeLocationsDataSource(LocationBundleV4(
            settings = localSettings,
            routing = localRouting,
            vpnDisclosureAcceptedAt = 111L,
            apolloVpnDisclosureAcceptedAt = 222L,
            apolloVpnDisclosureVersion = 1,
            onboardingSeenAt = 333L
        ))
        val repo = LocationsRepositoryImpl(source)
        val link = "vless://11111111-1111-1111-1111-111111111111@9.9.9.9:443" +
            "?security=reality&pbk=PUBKEY&sid=ab&sni=example.test&flow=xtls-rprx-vision&type=tcp#NL"

        assertTrue(repo.importText(link))
        val stored = assertNotNull(source.stored)
        assertEquals(1, stored.locations.size)
        assertEquals(stored.locations.single().storageId, stored.activeLocationId)
        assertEquals(localSettings, stored.settings)
        assertEquals(localRouting, stored.routing)
        assertEquals(111L, stored.vpnDisclosureAcceptedAt)
        assertEquals(222L, stored.apolloVpnDisclosureAcceptedAt)
        assertEquals(1, stored.apolloVpnDisclosureVersion)
        assertEquals(333L, stored.onboardingSeenAt)
    }

    @Test fun releaseRepositoryHidesStoredOlcrtcWithoutDeletingItsRecord() = runTest {
        val room = LocationEntry.from("room", LocationConfig(name = "Private room", id = "room-a", key = "key-a"))
        val ordinary = LocationEntry.from(
            "ordinary",
            LocationConfig(name = "NL via RU", kind = org.olcbox.app.net.LocationKind.Vless,
                xrayConfig = kotlinx.serialization.json.buildJsonObject { })
        )
        val source = FakeLocationsDataSource(LocationBundleV4(activeLocationId = "room", locations = listOf(room, ordinary)))
        val repo = LocationsRepositoryImpl(source)

        assertEquals(listOf("ordinary"), repo.getAllLocations().map { it.storageId })
        assertEquals(listOf("ordinary"), repo.getVisibleBundle().locations.map { it.storageId })
        assertNull(repo.getVisibleBundle().activeLocationId)
        assertNull(repo.getActiveLocationId())
        assertNull(repo.getActiveLocation())
        assertEquals(listOf("room", "ordinary"), source.stored?.locations?.map { it.storageId })
    }

    @Test fun releaseRejectsOlcrtcImportAndRestoreAtomically() = runTest {
        val ordinary = LocationEntry.from(
            "ordinary", LocationConfig(name = "NL", kind = org.olcbox.app.net.LocationKind.Vless,
                xrayConfig = kotlinx.serialization.json.buildJsonObject { })
        )
        val source = FakeLocationsDataSource(LocationBundleV4(activeLocationId = "ordinary", locations = listOf(ordinary)))
        val repo = LocationsRepositoryImpl(source)
        val before = source.stored
        val roomLink = "olcrtc://wbstream?vp8channel@room-a#${"c".repeat(64)}${'$'}Room"

        assertFalse(repo.importText(roomLink))
        val restore = kotlinx.serialization.json.Json.encodeToString(
            LocationBundleV4.serializer(),
            LocationBundleV4(locations = listOf(LocationEntry.from("room", LocationConfig("Room", "r", "k"))))
        )
        assertFalse(repo.importText(restore))
        assertEquals(before, source.stored)
    }

    @Test fun releaseDirectSaveAndSelectionCannotActivateOlcrtc() = runTest {
        val ordinary = LocationEntry.from(
            "ordinary", LocationConfig(name = "NL", kind = org.olcbox.app.net.LocationKind.Vless,
                xrayConfig = kotlinx.serialization.json.buildJsonObject { })
        )
        val legacyRoom = LocationEntry.from("legacy-room", LocationConfig("Room", "r", "k"))
        val source = FakeLocationsDataSource(LocationBundleV4(activeLocationId = "ordinary", locations = listOf(ordinary, legacyRoom)))
        val repo = LocationsRepositoryImpl(source)

        repo.saveLocation("new-room", LocationConfig("New room", "r2", "k2"))
        repo.setActiveLocationId("legacy-room")
        assertEquals(listOf("ordinary", "legacy-room"), source.stored?.locations?.map { it.storageId })
        assertEquals("ordinary", source.stored?.activeLocationId)

        repo.saveBundle(LocationBundleV4(activeLocationId = "ordinary", locations = listOf(ordinary)))
        assertEquals(listOf("ordinary", "legacy-room"), source.stored?.locations?.map { it.storageId })

        repo.saveLocation("legacy-room", ordinary.location)
        assertEquals(org.olcbox.app.net.LocationKind.Olcrtc,
            source.stored?.locations?.first { it.storageId == "legacy-room" }?.location?.kind)
        repo.saveBundle(LocationBundleV4(activeLocationId = "ordinary", locations = listOf(
            ordinary, ordinary.copy(storageId = "legacy-room")
        )))
        assertEquals(org.olcbox.app.net.LocationKind.Olcrtc,
            source.stored?.locations?.first { it.storageId == "legacy-room" }?.location?.kind)
    }

    @Test fun releaseSaveBundleWithoutSelectionDoesNotReinstateHiddenRoomAsActive() = runTest {
        val ordinary = LocationEntry.from(
            "ordinary", LocationConfig(name = "NL", kind = org.olcbox.app.net.LocationKind.Vless,
                xrayConfig = kotlinx.serialization.json.buildJsonObject { })
        )
        val room = LocationEntry.from("room", LocationConfig("Room", "r", "k"))
        val source = FakeLocationsDataSource(LocationBundleV4(activeLocationId = "room", locations = listOf(room, ordinary)))
        val repo = LocationsRepositoryImpl(source)

        repo.saveBundle(LocationBundleV4(activeLocationId = null, locations = listOf(ordinary)))
        assertEquals("ordinary", source.stored?.activeLocationId)
        assertEquals(listOf("ordinary", "room"), source.stored?.locations?.map { it.storageId })
    }

    @Test fun releaseRefreshesOrdinaryEntryWithoutErasingLegacyRoom() = runTest {
        val url = "https://example.invalid/sub/synthetic"
        val room = LocationEntry.from("room", LocationConfig("Room", "r", "k"), subscriptionUrl = url)
        val source = FakeLocationsDataSource()
        val body = "vless://11111111-1111-1111-1111-111111111111@9.9.9.9:443?security=reality&pbk=P&sid=ab&sni=x&flow=xtls-rprx-vision&type=tcp#NL"
        val repo = LocationsRepositoryImpl(source, HttpClient(MockEngine { respond(body) }))
        assertTrue(repo.importText(url))
        source.stored = source.stored!!.copy(locations = source.stored!!.locations + room)

        val refresh = repo.refreshSubscriptions()
        assertEquals(1, refresh.updatedCount, refresh.failures.toString())
        assertEquals(2, source.stored?.locations?.size)
        assertTrue(source.stored?.locations?.any { it.storageId == "room" && it.location.kind == org.olcbox.app.net.LocationKind.Olcrtc } == true)
    }

    @Test fun releaseDoesNotFetchAnOlcrtcOnlySubscription() = runTest {
        val url = "https://example.invalid/sub/legacy-room"
        val room = LocationEntry.from("room", LocationConfig("Room", "r", "k"), subscriptionUrl = url)
        val source = FakeLocationsDataSource(LocationBundleV4(locations = listOf(room)))
        val repo = LocationsRepositoryImpl(source, HttpClient(MockEngine { error("Hidden OlcRTC subscription was fetched") }))

        assertEquals(0, repo.refreshSubscriptions().updatedCount)
        assertEquals(listOf(room), source.stored?.locations)
    }

    @Test fun releaseRefreshOfAnotherSubscriptionKeepsSeparateHiddenRoom() = runTest {
        val ordinaryUrl = "https://example.invalid/sub/ordinary"
        val hiddenUrl = "https://example.invalid/sub/hidden"
        val body = "vless://11111111-1111-1111-1111-111111111111@9.9.9.9:443?security=reality&pbk=P&sid=ab&sni=x&flow=xtls-rprx-vision&type=tcp#NL"
        val source = FakeLocationsDataSource()
        val repo = LocationsRepositoryImpl(source, HttpClient(MockEngine { respond(body) }))
        assertTrue(repo.importText(ordinaryUrl))
        val room = LocationEntry.from("room", LocationConfig("Room", "r", "k"), subscriptionUrl = hiddenUrl)
        source.stored = source.stored!!.copy(locations = source.stored!!.locations + room)

        assertEquals(1, repo.refreshSubscriptions().updatedCount)
        assertTrue(source.stored!!.locations.any { it.storageId == "room" })
    }

    @Test fun releaseFailedMixedRefreshKeepsHiddenRoomWhenOtherListSucceeds() = runTest {
        val goodUrl = "https://example.invalid/sub/good"
        val badUrl = "https://example.invalid/sub/bad"
        val body = "vless://11111111-1111-1111-1111-111111111111@9.9.9.9:443?security=reality&pbk=P&sid=ab&sni=x&flow=xtls-rprx-vision&type=tcp#NL"
        var failBad = false
        val source = FakeLocationsDataSource()
        val repo = LocationsRepositoryImpl(source, HttpClient(MockEngine { request ->
            respond(if (failBad && request.url.toString() == badUrl) "invalid-response" else body)
        }))
        assertTrue(repo.importText(goodUrl))
        assertTrue(repo.importText(badUrl))
        val room = LocationEntry.from("room", LocationConfig("Room", "r", "k"), subscriptionUrl = badUrl)
        source.stored = source.stored!!.copy(locations = source.stored!!.locations + room)
        failBad = true

        assertEquals(1, repo.refreshSubscriptions().updatedCount)
        assertTrue(source.stored!!.locations.any { it.storageId == "room" })
    }

    @Test fun releaseOrdinaryImportAndRestoreCannotReplaceHiddenRoom() = runTest {
        val url = "https://example.invalid/sub/shared"
        val room = LocationEntry.from("room", LocationConfig("Room", "r", "k"), subscriptionUrl = url)
        val source = FakeLocationsDataSource(LocationBundleV4(locations = listOf(room)))
        val body = "vless://11111111-1111-1111-1111-111111111111@9.9.9.9:443?security=reality&pbk=P&sid=ab&sni=x&flow=xtls-rprx-vision&type=tcp#NL"
        val repo = LocationsRepositoryImpl(source, HttpClient(MockEngine { respond(body) }))

        assertTrue(repo.importText(url))
        assertTrue(source.stored!!.locations.any { it.storageId == "room" && it.location.kind == org.olcbox.app.net.LocationKind.Olcrtc })
        val restore = kotlinx.serialization.json.Json.encodeToString(
            LocationBundleV4.serializer(),
            LocationBundleV4(locations = listOf(LocationEntry.from("room", LocationConfig(
                name = "NL", kind = org.olcbox.app.net.LocationKind.Vless,
                xrayConfig = kotlinx.serialization.json.buildJsonObject { }
            ))))
        )
        assertTrue(repo.importText(restore))
        assertTrue(source.stored!!.locations.any { it.storageId == "room" && it.location.kind == org.olcbox.app.net.LocationKind.Olcrtc })
    }

    @Test fun releaseDeletionOfOrdinarySubscriptionKeepsHiddenRoomRecord() = runTest {
        val url = "https://example.invalid/sub/shared"
        val ordinary = LocationEntry.from("ordinary", LocationConfig(name = "NL", kind = org.olcbox.app.net.LocationKind.Vless,
            xrayConfig = kotlinx.serialization.json.buildJsonObject { }), subscriptionUrl = url)
        val room = LocationEntry.from("room", LocationConfig("Room", "r", "k"), subscriptionUrl = url)
        val source = FakeLocationsDataSource(LocationBundleV4(activeLocationId = "ordinary", locations = listOf(ordinary, room)))
        val repo = LocationsRepositoryImpl(source)

        repo.deleteLocation("room")
        assertEquals(2, source.stored?.locations?.size)
        assertEquals(1, repo.deleteSubscription(url))
        assertEquals(listOf("room"), source.stored?.locations?.map { it.storageId })
    }

    private fun experimentalRepository(
        dataSource: LocationsDataSource,
        httpClient: HttpClient = createProxyHttpClient(followRedirects = false),
        deviceIdentityProvider: DeviceIdentityProvider = PersistentDeviceIdentityProvider(dataSource),
        nowEpochMs: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
        cryptCodec: CryptCodec? = CryptCodec.default(),
        partnerLinkResolver: PartnerLinkResolver? = HttpPartnerLinkResolver(httpClient)
    ): LocationsRepositoryImpl = LocationsRepositoryImpl(
        dataSource = dataSource,
        httpClient = httpClient,
        deviceIdentityProvider = deviceIdentityProvider,
        nowEpochMs = nowEpochMs,
        cryptCodec = cryptCodec,
        partnerLinkResolver = partnerLinkResolver,
        runtimePolicy = RuntimeLocationPolicy.ExperimentalDebug
    )

    @Test fun cancellingARefreshPreservesTheListAndPropagatesCancellation() = runTest {
        var cancel = false
        var now = 1_000L
        val source = FakeLocationsDataSource()
        val repo = experimentalRepository(source, HttpClient(MockEngine {
            if (cancel) throw kotlinx.coroutines.CancellationException("settings changed")
            respond("olcrtc://wbstream?vp8channel@first#${"c".repeat(64)}${'$'}First")
        }), nowEpochMs = { now })
        assertTrue(repo.importText("https://example.test/list"))
        val before = repo.getBundle()
        now += 86_400_000L
        cancel = true
        kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> { repo.refreshDueSubscriptions() }
        assertEquals(before, repo.getBundle())
    }

    @Test fun dueRefreshHonorsUserIntervalAddsServersAndPreservesSelection() = runTest {
        var now = 1_000L
        var body = "olcrtc://wbstream?vp8channel@first#${"c".repeat(64)}${'$'}First"
        var requests = 0
        val source = FakeLocationsDataSource()
        val repo = experimentalRepository(source, HttpClient(MockEngine {
            requests++
            respond(body, headers = headersOf("profile-update-interval", "24"))
        }), nowEpochMs = { now })
        assertTrue(repo.importText("https://example.test/list"))
        val selected = repo.getActiveLocationId()
        repo.saveSubscriptionSettings(org.olcbox.app.data.model.SubscriptionSettings(updateIntervalHours = 1))
        now += 3_600_000L
        body += "\n" + "olcrtc://wbstream?vp8channel@second#${"d".repeat(64)}${'$'}Second"
        assertEquals(1, repo.refreshDueSubscriptions().updatedCount)
        assertEquals(2, repo.getAllLocations().size)
        assertEquals(selected, repo.getActiveLocationId())
        assertEquals(0, repo.refreshDueSubscriptions().updatedCount)
        repo.saveSubscriptionSettings(org.olcbox.app.data.model.SubscriptionSettings(autoUpdate = false))
        now += 86_400_000L
        assertEquals(0, repo.refreshDueSubscriptions().updatedCount)
        assertEquals(2, requests)
    }



    @Test
    fun exportsAndImportsBundleV5WithActiveLocation() = runTest {
        val first = LocationEntry.from(
            "amsterdam",
            LocationConfig("Amsterdam", "room-a", "key-a", LocationConfig.PROVIDER_SALUTEJAZZ)
        )
        val second = LocationEntry.from(
            "berlin",
            LocationConfig("Berlin", "room-b", "key-b", LocationConfig.PROVIDER_TELEMOST)
        )
        val source = FakeLocationsDataSource(
            stored = LocationBundleV4(
                activeLocationId = "berlin",
                locations = listOf(first, second)
            )
        )
        val exported = experimentalRepository(source).exportBundle()
        assertTrue("\"version\": 5" in exported)
        assertTrue("\"endpoint\"" in exported)
        assertTrue("\"auth_provider\"" in exported)
        assertTrue("\"client_id\"" !in exported)
        assertTrue("\"bypass_provider\"" !in exported)
        val importedSource = FakeLocationsDataSource()

        experimentalRepository(importedSource).importText(exported)

        val imported = importedSource.stored
        assertNotNull(imported)
        assertEquals(5, imported.version)
        assertEquals("berlin", imported.activeLocationId)
        assertEquals(listOf("amsterdam", "berlin"), imported.locations.map { it.storageId })
        assertEquals(
            LocationConfig.PROVIDER_TELEMOST,
            imported.locations[1].location.bypassProvider
        )
    }

    @Test
    fun migratesLegacyLocationsAndPreservesActiveSelection() = runTest {
        val source = FakeLocationsDataSource(
            legacy = listOf(
                "legacy_a" to """{"name":"A","server":"room-a","password":"key-a","provider":"jazz"}""",
                "legacy_b" to """{"name":"B","server":"room-b","password":"key-b","turn":{"type":"wbstream"}}"""
            ),
            legacyActive = "legacy_b"
        )
        val bundle = experimentalRepository(source).getBundle()

        assertEquals("legacy_b", bundle.activeLocationId)
        assertEquals(listOf("legacy_a", "legacy_b"), bundle.locations.map { it.storageId })
        assertEquals(LocationConfig.PROVIDER_WB_STREAM, bundle.locations[1].location.bypassProvider)
        // "jazz" is SaluteJazz under upstream's old name: a location stored
        // under it lands on the carrier the engine runs today.
        assertEquals(LocationConfig.PROVIDER_SALUTEJAZZ, bundle.locations[0].location.bypassProvider)
        assertEquals(bundle, source.stored)
    }

    @Test
    fun normalizesStoredWbStreamAliasToCanonicalProvider() = runTest {
        val source = FakeLocationsDataSource(
            stored = LocationBundleV4(
                activeLocationId = "wb",
                locations = listOf(
                    LocationEntry(
                        storageId = "wb",
                        name = "WB",
                        legacyId = "room-wb",
                        legacyKey = "key-wb",
                        legacyBypassProvider = "wbstream"
                    )
                )
            )
        )

        val active = experimentalRepository(source).getActiveLocation()

        assertNotNull(active)
        assertEquals(LocationConfig.PROVIDER_WB_STREAM, active.bypassProvider)
        assertEquals(LocationConfig.PROVIDER_WB_STREAM, active.location.bypassProvider)
    }

    @Test
    fun importsWbStreamAliasAsCanonicalProvider() = runTest {
        val source = FakeLocationsDataSource()
        val input = """
            {
              "version": 3,
              "active_location_id": "wb",
              "locations": [
                {
                  "storage_id": "wb",
                  "name": "WB",
                  "id": "room-wb",
                  "key": "key-wb",
                  "bypass_provider": "wbstream"
                }
              ]
            }
        """.trimIndent()

        experimentalRepository(source).importText(input)

        val imported = source.stored
        assertNotNull(imported)
        assertEquals(LocationConfig.PROVIDER_WB_STREAM, imported.locations.first().bypassProvider)
    }

    @Test
    fun importsSingleLegacyLocationWithTurnProvider() = runTest {
        val source = FakeLocationsDataSource()
        val input = """
            {
              "hysteria": {
                "name": "Paris",
                "server": "room-paris",
                "password": "key-paris"
              },
              "turn": {
                "type": "telemost"
              }
            }
        """.trimIndent()

        experimentalRepository(source).importText(input)

        val imported = source.stored
        assertNotNull(imported)
        assertEquals("imported_paris", imported.activeLocationId)
        assertEquals(1, imported.locations.size)
        assertEquals("room-paris", imported.locations.first().location.id)
        assertEquals(
            LocationConfig.PROVIDER_TELEMOST,
            imported.locations.first().location.bypassProvider
        )
    }

    @Test
    fun importsLegacyOlcRtcUriWithClientIdAndMimoName() = runTest {
        val source = FakeLocationsDataSource()
        val input = "olcrtc://wbstream?seichannel@room-01#${"a".repeat(64)}%android-01${'$'}RU / olc free sub / IPv6"

        experimentalRepository(source).importText(input)

        val imported = source.stored
        assertNotNull(imported)
        val entry = imported.locations.single()
        val location = entry.location
        assertEquals(LocationConfig.PROVIDER_WB_STREAM, location.bypassProvider)
        assertEquals(LocationConfig.TRANSPORT_SEICHANNEL, location.transport)
        assertEquals("room-01", location.id)
        assertEquals("RU / olc free sub / IPv6", location.name)
        assertEquals("RU / olc free sub / IPv6", entry.metadata?.mimo)
        assertNull(entry.metadata?.subscription)
    }

    // The third carrier (Sber SaluteJazz, LiveKit-as-JSON over pion), merged
    // into the engine 2026-09-22. The room is a `<code>:<password>` pair
    // carried whole in the URI's room segment - unlike wbstream/telemost it
    // has no separate splitting of its own, so the parser must not touch it.
    @Test
    fun importsSalutejazzOlcRtcUri() = runTest {
        val source = FakeLocationsDataSource()
        val key = "e".repeat(64)
        val input = "olcrtc://salutejazz?datachannel@zzz999:pw123456#$key${'$'}DE · olcRTC · SJ"

        experimentalRepository(source).importText(input)

        val imported = source.stored
        assertNotNull(imported)
        val location = imported.locations.single().location
        assertEquals(LocationConfig.PROVIDER_SALUTEJAZZ, location.bypassProvider)
        assertEquals(LocationConfig.TRANSPORT_DATACHANNEL, location.transport)
        assertEquals("zzz999:pw123456", location.id)
        assertEquals(key, location.key)
        assertEquals("DE · olcRTC · SJ", location.name)
    }

    // The engine carries a Jitsi room over DataChannel only, so the app runs
    // one over DataChannel whatever the link says - and used to say nothing
    // about it. A server set up for vp8channel then waited for a peer that
    // never spoke its transport (olcbox#15). The request is kept on the entry,
    // so the board and the connect button can say what happened.
    @Test
    fun keepsTheTransportALinkAskedForWhenTheProviderCannotCarryIt() = runTest {
        val source = FakeLocationsDataSource()
        val key = "b".repeat(64)
        val input = "olcrtc://jitsi?vp8channel<vp8-fps=25&vp8-batch=1>@https://meet.egovm.ru/olcrtc-x#$key"

        experimentalRepository(source).importText(input)

        val entry = assertNotNull(source.stored).locations.single()
        assertEquals(LocationConfig.TRANSPORT_DATACHANNEL, entry.location.transport)
        assertEquals(LocationConfig.TRANSPORT_VP8CHANNEL, entry.metadata?.requestedTransport)
    }

    @Test
    fun aTransportTheProviderCarriesIsNotARequest() = runTest {
        val source = FakeLocationsDataSource()
        val key = "b".repeat(64)
        val input = listOf(
            "olcrtc://telemost?vp8channel@12345#$key",
            "olcrtc://jitsi?datachannel@https://meet.egovm.ru/olcrtc-y#$key"
        ).joinToString("\n")

        experimentalRepository(source).importText(input)

        val entries = assertNotNull(source.stored).locations
        assertEquals(2, entries.size)
        entries.forEach { entry ->
            assertNull(entry.metadata?.requestedTransport, "${entry.location.bypassProvider} asked for nothing it cannot have")
        }
    }

    @Test
    fun importsJitsiOlcRtcUriWithRoomUrl() = runTest {
        val source = FakeLocationsDataSource()
        val key = "b".repeat(64)
        val input = "olcrtc://jitsi?datachannel@https://meet.cryptopro.ru/myroom#$key${'$'}Jitsi room"

        experimentalRepository(source).importText(input)

        val imported = source.stored
        assertNotNull(imported)
        val location = imported.locations.single().location
        assertEquals(LocationConfig.PROVIDER_JITSI, location.bypassProvider)
        assertEquals(LocationConfig.TRANSPORT_DATACHANNEL, location.transport)
        assertEquals("https://meet.cryptopro.ru/myroom", location.id)
        assertEquals("Jitsi room", location.name)
    }

    @Test
    fun importsOlcRtcSubscriptionAndAppliesLocalNames() = runTest {
        val source = FakeLocationsDataSource()
        val input = """
            #name: Test subscription
            #update: 1778011200
            #refresh: 10m
            #color: #4A90E2
            #icon: flag-ru
            #used: 10mb/10gb
            #available: 9.99gb

            olcrtc://wbstream?seichannel@room-01#${"a".repeat(64)}%android-01${'$'}RU / default name
            ##name: RU-1
            ##color: #4A90E2
            ##icon: node-ru
            ##used: 500mb/10gb
            ##available: 9.5gb
            ##ip: 203.0.113.10
            ##comment: primary

            olcrtc://jazz?datachannel@room-02#${"b".repeat(64)}%android-02${'$'}DE / backup
            ##name: DE-Backup
        """.trimIndent()

        experimentalRepository(source).importText(input)

        val imported = source.stored
        assertNotNull(imported)
        assertEquals(listOf("RU-1", "DE-Backup"), imported.locations.map { it.location.name })
        assertEquals(
            listOf(LocationConfig.PROVIDER_WB_STREAM, LocationConfig.PROVIDER_SALUTEJAZZ),
            imported.locations.map { it.location.bypassProvider }
        )
        assertEquals("imported_ru-1", imported.activeLocationId)

        val firstMetadata = imported.locations[0].metadata
        assertNotNull(firstMetadata)
        assertEquals("RU-1", firstMetadata.name)
        assertEquals("#4A90E2", firstMetadata.color)
        assertEquals("node-ru", firstMetadata.icon)
        assertEquals("500mb/10gb", firstMetadata.used)
        assertEquals("9.5gb", firstMetadata.available)
        assertEquals("203.0.113.10", firstMetadata.ip)
        assertEquals("primary", firstMetadata.comment)
        assertEquals("RU / default name", firstMetadata.mimo)

        val subscriptionMetadata = firstMetadata.subscription
        assertNotNull(subscriptionMetadata)
        assertEquals("Test subscription", subscriptionMetadata.name)
        assertEquals("1778011200", subscriptionMetadata.update)
        assertEquals("10m", subscriptionMetadata.refresh)
        assertEquals("#4A90E2", subscriptionMetadata.color)
        assertEquals("flag-ru", subscriptionMetadata.icon)
        assertEquals("10mb/10gb", subscriptionMetadata.used)
        assertEquals("9.99gb", subscriptionMetadata.available)
        assertEquals(subscriptionMetadata, imported.locations[1].metadata?.subscription)
    }

    fun importUpdatesMatchingStorageIdsAndAppendsNewLocations() = runTest {
        val source = FakeLocationsDataSource(
            stored = LocationBundleV4(
                activeLocationId = "custom_paris",
                locations = listOf(
                    LocationEntry.from(
                        "custom_paris",
                        LocationConfig(
                            name = "Paris",
                            id = "room-old",
                            key = "a".repeat(64),
                            bypassProvider = LocationConfig.PROVIDER_WB_STREAM
                        )
                    ),
                    LocationEntry.from(
                        "custom_berlin",
                        LocationConfig(
                            name = "Berlin",
                            id = "room-berlin",
                            key = "b".repeat(64),
                            bypassProvider = LocationConfig.PROVIDER_TELEMOST
                        )
                    )
                )
            )
        )
        val input = """
            {
              "version": 4,
              "active_location_id": "sub_wb",
              "locations": [
                {
                  "storage_id": "custom_paris",
                  "name": "Paris updated",
                  "endpoint": {
                    "room_id": "room-new",
                    "key": "${"c".repeat(64)}",
                    "client_id": "phone-1"
                  },
                  "carrier": "wbstream",
                  "transport": {
                    "type": "datachannel"
                  }
                },
                {
                  "storage_id": "sub_wb",
                  "name": "WB sub",
                  "subscription_url": "https://example.com/sub.md",
                  "endpoint": {
                    "room_id": "room-sub",
                    "key": "${"d".repeat(64)}",
                    "client_id": "phone-2"
                  },
                  "carrier": "wbstream",
                  "transport": {
                    "type": "vp8channel"
                  }
                }
              ]
            }
        """.trimIndent()

        experimentalRepository(source).importText(input)

        val imported = source.stored
        assertNotNull(imported)
        assertEquals(listOf("custom_paris", "custom_berlin", "sub_wb"), imported.locations.map { it.storageId })
        assertEquals("room-new", imported.locations[0].location.id)
        assertEquals("room-berlin", imported.locations[1].location.id)
        assertEquals("https://example.com/sub.md", imported.locations[2].subscriptionUrl)
        assertEquals("sub_wb", imported.activeLocationId)
    }

    @Test
    fun invalidLocationCannotBecomeActiveLocation() = runTest {
        val source = FakeLocationsDataSource()
        val incomplete = LocationConfig(name = "Broken", id = "room", key = "")

        experimentalRepository(source).saveLocation("broken", incomplete)

        val bundle = source.stored
        assertNotNull(bundle)
        assertNull(bundle.activeLocationId)
        assertTrue(bundle.locations.isEmpty())
    }

    @Test
    fun telemostLocationsForceVp8AndOtherProvidersCanUseDatachannel() = runTest {
        val source = FakeLocationsDataSource()
        val input = """
            {
              "version": 3,
              "locations": [
                {
                  "storage_id": "telemost",
                  "name": "Telemost",
                  "id": "75047680642749",
                  "key": "${"a".repeat(64)}",
                  "bypass_provider": "telemost",
                  "transport": "datachannel"
                },
                {
                  "storage_id": "wb",
                  "name": "WB",
                  "id": "room-wb",
                  "key": "${"b".repeat(64)}",
                  "bypass_provider": "wbstream",
                  "transport": "datachannel"
                }
              ]
            }
        """.trimIndent()

        experimentalRepository(source).importText(input)

        val imported = source.stored
        assertNotNull(imported)
        assertEquals(LocationConfig.TRANSPORT_VP8CHANNEL, imported.locations[0].location.transport)
        assertEquals(LocationConfig.TRANSPORT_DATACHANNEL, imported.locations[1].location.transport)
    }

    @Test
    fun importsUnsupportedVideochannelAsDefaultTransport() = runTest {
        val source = FakeLocationsDataSource()
        val input = """
            {
              "version": 4,
              "active_location_id": "telemost-video",
              "locations": [
                {
                  "storage_id": "telemost-video",
                  "name": "Telemost Video",
                  "endpoint": {
                    "room_id": "75047680642749",
                    "key": "${"c".repeat(64)}"
                  },
                  "carrier": "telemost",
                  "transport": {
                    "type": "videochannel"
                  }
                }
              ]
            }
        """.trimIndent()

        experimentalRepository(source).importText(input)

        val imported = source.stored
        assertNotNull(imported)
        val location = imported.locations.first().location
        assertEquals(5, imported.version)
        assertEquals(LocationConfig.PROVIDER_TELEMOST, location.bypassProvider)
        assertEquals(LocationConfig.TRANSPORT_VP8CHANNEL, location.transport)
    }

    @Test
    fun exposesAllWorkingProviderTransportPairs() {
        assertEquals(
            listOf(
                LocationConfig.TRANSPORT_VP8CHANNEL,
                LocationConfig.TRANSPORT_SEICHANNEL
            ),
            LocationConfig.supportedTransportsForProvider(LocationConfig.PROVIDER_TELEMOST)
        )
        assertEquals(
            listOf(
                LocationConfig.TRANSPORT_DATACHANNEL,
                LocationConfig.TRANSPORT_VP8CHANNEL,
                LocationConfig.TRANSPORT_SEICHANNEL
            ),
            LocationConfig.supportedTransportsForProvider(LocationConfig.PROVIDER_WB_STREAM)
        )
        assertEquals(
            listOf(LocationConfig.TRANSPORT_DATACHANNEL),
            LocationConfig.supportedTransportsForProvider(LocationConfig.PROVIDER_JITSI)
        )
        assertEquals(
            LocationConfig.TRANSPORT_DATACHANNEL,
            LocationConfig.normalizeTransport(LocationConfig.TRANSPORT_VP8CHANNEL, LocationConfig.PROVIDER_JITSI)
        )
        // SaluteJazz guests get data channels only - no media track - so a
        // link that asked for vp8/sei still lands the room on datachannel.
        assertEquals(
            listOf(LocationConfig.TRANSPORT_DATACHANNEL),
            LocationConfig.supportedTransportsForProvider(LocationConfig.PROVIDER_SALUTEJAZZ)
        )
        assertEquals(
            LocationConfig.TRANSPORT_DATACHANNEL,
            LocationConfig.normalizeTransport(LocationConfig.TRANSPORT_VP8CHANNEL, LocationConfig.PROVIDER_SALUTEJAZZ)
        )
    }

    @Test
    fun olcRtcSingleProfileImportDoesNotOverwriteExistingStorageId() = runTest {
        val source = FakeLocationsDataSource(
            stored = LocationBundleV4(
                activeLocationId = "imported_room-01",
                locations = listOf(
                    LocationEntry.from(
                        "imported_room-01",
                        LocationConfig("Old", "room-old", "a".repeat(64), LocationConfig.PROVIDER_WB_STREAM)
                    )
                )
            )
        )

        experimentalRepository(source).importText(
            "olcrtc://wbstream?seichannel@room-01#${"b".repeat(64)}${'$'}New"
        )

        val imported = source.stored
        assertNotNull(imported)
        assertEquals(listOf("imported_room-01", "imported_new"), imported.locations.map { it.storageId })
        assertEquals("room-old", imported.locations[0].location.id)
        assertEquals("room-01", imported.locations[1].location.id)
        assertEquals("imported_new", imported.activeLocationId)
    }

    @Test
    fun bundleRestoreUpdatesMatchingStorageIds() = runTest {
        val source = FakeLocationsDataSource(
            stored = LocationBundleV4(
                activeLocationId = "same",
                locations = listOf(
                    LocationEntry.from(
                        "same",
                        LocationConfig("Old", "room-old", "a".repeat(64), LocationConfig.PROVIDER_WB_STREAM)
                    )
                )
            )
        )

        val input = """
            {
              "version": 4,
              "active_location_id": "same",
              "locations": [
                {
                  "storage_id": "same",
                  "name": "Updated",
                  "endpoint": {
                    "room_id": "room-new",
                    "key": "${"b".repeat(64)}",
                    "client_id": "desktop"
                  },
                  "carrier": "wbstream",
                  "transport": {"type": "datachannel"}
                }
              ]
            }
        """.trimIndent()

        experimentalRepository(source).importText(input)

        val imported = source.stored
        assertNotNull(imported)
        assertEquals(listOf("same"), imported.locations.map { it.storageId })
        assertEquals("room-new", imported.locations.single().location.id)
    }

    @Test
    fun subscriptionHeaderSetsIntervalAndIdentityHeaders() = runTest {
        var userAgent: String? = null
        var hwid: String? = null
        val engine = MockEngine { request ->
            userAgent = request.headers[HttpHeaders.UserAgent]
            hwid = request.headers["x-hwid"]
            respond(
                content = "olcrtc://wbstream?vp8channel@room#${"c".repeat(64)}${'$'}Sub",
                headers = headersOf("profile-update-interval", "6")
            )
        }
        val source = FakeLocationsDataSource()

        experimentalRepository(
            dataSource = source,
            httpClient = HttpClient(engine),
            deviceIdentityProvider = StaticIdentityProvider("hwid-test")
        ).importText("https://example.test/sub.txt")

        val imported = source.stored
        assertNotNull(imported)
        assertEquals(CurrentAppInfo.userAgent, userAgent)
        assertEquals("hwid-test", hwid)
        assertEquals(6, imported.locations.single().metadata?.subscription?.updateIntervalHours)
        assertEquals("https://example.test/sub.txt", imported.locations.single().subscriptionUrl)
    }

    @Test
    fun subscriptionImportDoesNotRetryRejectedIdentityAsBrowser() = runTest {
        val userAgents = mutableListOf<String?>()
        val hwids = mutableListOf<String?>()
        val engine = MockEngine { request ->
            userAgents += request.headers[HttpHeaders.UserAgent]
            hwids += request.headers["x-hwid"]
            if (userAgents.size == 1) {
                respond("<html>blocked</html>")
            } else {
                respond(
                    content = "\uFEFFolcrtc://wbstream?vp8channel@room#${"d".repeat(64)}${'$'}Fallback",
                    headers = headersOf("profile-update-interval", "12")
                )
            }
        }
        val source = FakeLocationsDataSource()

        val imported = experimentalRepository(
            dataSource = source,
            httpClient = HttpClient(engine),
            deviceIdentityProvider = StaticIdentityProvider("hwid-test")
        ).importText("https://example.test/sub.txt")

        val bundle = source.stored
        assertFalse(imported)
        assertNull(bundle)
        assertEquals(1, userAgents.size)
        assertEquals(CurrentAppInfo.userAgent, userAgents[0])
        assertEquals("hwid-test", hwids[0])
    }

    @Test
    fun xrayJsonImportKeepsEveryValidatedProfileAndOriginalConfig() = runTest {
        val source = FakeLocationsDataSource()
        val repo = experimentalRepository(source, HttpClient(MockEngine {
            respond(XrayJsonSubscriptionFixtures.body)
        }))

        assertTrue(repo.importText("https://example.test/sub"))
        val stored = assertNotNull(source.stored)
        assertEquals(2, stored.locations.size)
        assertEquals(listOf("Synthetic exit A", "Synthetic exit B"), stored.locations.map { it.name })
        assertEquals(
            listOf(XrayJsonSubscriptionFixtures.vless, XrayJsonSubscriptionFixtures.hysteria)
                .map { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject },
            stored.locations.map { it.location.xrayConfig }
        )
        assertEquals("https://example.test/sub", stored.locations.single { it.name == "Synthetic exit A" }.subscriptionUrl)
    }

    @Test
    fun invalidXrayRefreshPreservesWholeSelectedVersion() = runTest {
        var body = XrayJsonSubscriptionFixtures.body
        val source = FakeLocationsDataSource()
        val repo = experimentalRepository(source, HttpClient(MockEngine { respond(body) }))
        assertTrue(repo.importText("https://example.test/sub"))
        val first = assertNotNull(source.stored)
        repo.setActiveLocationId(first.locations[1].storageId)
        val before = assertNotNull(source.stored)

        body = "[${XrayJsonSubscriptionFixtures.vless},{\"remarks\":\"broken\"}]"
        val report = repo.refreshSubscription("https://example.test/sub")

        assertEquals(0, report.updatedCount)
        assertTrue(report.hasFailures)
        assertEquals(before, source.stored)
        assertEquals(before.activeLocationId, repo.getActiveLocationId())
    }

    @Test
    fun successfulXrayRefreshKeepsSelectionAcrossDnsChange() = runTest {
        var body = XrayJsonSubscriptionFixtures.body
        val source = FakeLocationsDataSource()
        val repo = experimentalRepository(source, HttpClient(MockEngine { respond(body) }))
        assertTrue(repo.importText("https://example.test/sub"))
        val chosen = assertNotNull(source.stored).locations[1].storageId
        repo.setActiveLocationId(chosen)

        body = XrayJsonSubscriptionFixtures.body.replace("198.51.100.53", "203.0.113.53")
        assertEquals(1, repo.refreshSubscription("https://example.test/sub").updatedCount)
        assertEquals(chosen, repo.getActiveLocationId())
        assertEquals(2, assertNotNull(source.stored).locations.size)
    }

    @Test
    fun reorderedSameEndpointXrayProfilesKeepSelectedExactConfig() = runTest {
        val first = XrayJsonSubscriptionFixtures.vless
        val second = first
            .replace("Synthetic exit A", "Synthetic exit C")
            .replace("00000000-0000-4000-8000-000000000000", "11111111-1111-4111-8111-111111111111")
            .replace("domain:example.invalid", "domain:alternate.invalid")
        var body = "[$first,$second]"
        val source = FakeLocationsDataSource()
        val repo = experimentalRepository(source, HttpClient(MockEngine { respond(body) }))
        assertTrue(repo.importText("https://example.test/sub"))
        val selected = source.stored!!.locations[1]
        repo.setActiveLocationId(selected.storageId)

        body = "[$second,$first]"
        assertEquals(1, repo.refreshSubscription("https://example.test/sub").updatedCount)
        assertEquals(selected.storageId, repo.getActiveLocationId())
        assertEquals(selected.location.xrayConfig, repo.getActiveLocation()!!.location.xrayConfig)
    }

    @Test
    fun changedAmbiguousSelectedXrayProfileDoesNotGuessByListOrder() = runTest {
        val first = XrayJsonSubscriptionFixtures.vless
        val second = first.replace("Synthetic exit A", "Synthetic exit C")
            .replace("00000000-0000-4000-8000-000000000000", "11111111-1111-4111-8111-111111111111")
        var body = "[$first,$second]"
        val source = FakeLocationsDataSource()
        val repo = experimentalRepository(source, HttpClient(MockEngine { respond(body) }))
        assertTrue(repo.importText("https://example.test/sub"))
        repo.setActiveLocationId(source.stored!!.locations[1].storageId)
        val before = source.stored

        val changedSecond = second.replace("11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222")
        body = "[$changedSecond,$first]"
        val report = repo.refreshSubscription("https://example.test/sub")
        assertEquals(0, report.updatedCount)
        assertTrue(report.hasFailures)
        assertEquals(before, source.stored)
    }

    @Test
    fun ambiguousSelectedXrayRefreshKeepsProfilesButAppliesFreshTrafficDenial() = runTest {
        val first = XrayJsonSubscriptionFixtures.vless
        val second = first.replace("Synthetic exit A", "Synthetic exit C")
            .replace("00000000-0000-4000-8000-000000000000", "11111111-1111-4111-8111-111111111111")
        var body = "[$first,$second]"
        var userinfo = "upload=1; download=1; total=10; expire=3000"
        val now = 2_000_000L
        val source = FakeLocationsDataSource()
        val repo = experimentalRepository(source, HttpClient(MockEngine {
            respond(body, headers = headersOf("subscription-userinfo", userinfo))
        }), nowEpochMs = { now })
        assertTrue(repo.importText("https://example.test/sub"))
        val selectedId = source.stored!!.locations[1].storageId
        val imported = assertNotNull(source.stored)
        val manual = LocationEntry.from("manual", LocationConfig(name = "Manual", kind = org.olcbox.app.net.LocationKind.Vless,
            xrayConfig = kotlinx.serialization.json.buildJsonObject { }))
        source.stored = imported.copy(locations = listOf(imported.locations[0], manual, imported.locations[1]))
        repo.setActiveLocationId(selectedId)
        val before = assertNotNull(source.stored)
        assertNull(SubscriptionTunnelAccess.denial(before, selectedId, now, RuntimeLocationPolicy.ExperimentalDebug))

        val changedSecond = second.replace("11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222")
        body = "[$changedSecond,$first]"
        userinfo = "upload=6; download=5; total=10; expire=3000"
        val report = repo.refreshSubscription("https://example.test/sub")

        assertEquals(0, report.updatedCount)
        assertEquals(SubscriptionRefreshError.Rejected, report.failures.single().error)
        val stored = assertNotNull(source.stored)
        assertEquals(selectedId, stored.activeLocationId)
        assertEquals(before.locations.map { it.storageId to it.location.xrayConfig },
            stored.locations.map { it.storageId to it.location.xrayConfig })
        assertEquals(11L, stored.locations.single { it.storageId == selectedId }.metadata?.subscription?.usedBytes)
        assertEquals(10L, stored.locations.single { it.storageId == selectedId }.metadata?.subscription?.totalBytes)
        assertEquals(before.locations.single { it.storageId == selectedId }.metadata?.subscription?.expiresAtEpochMs,
            stored.locations.single { it.storageId == selectedId }.metadata?.subscription?.expiresAtEpochMs)
        assertEquals(before.locations.single { it.storageId == selectedId }.metadata?.subscription?.lastRefreshAtEpochMs,
            stored.locations.single { it.storageId == selectedId }.metadata?.subscription?.lastRefreshAtEpochMs)
        assertEquals("Subscription traffic limit reached",
            SubscriptionTunnelAccess.denial(stored, selectedId, now, RuntimeLocationPolicy.ExperimentalDebug))
        assertNull(repo.getActiveLocation())
    }

    @Test
    fun informationalLimitReplyKeepsHostsButBlocksTunnelUntilValidRefresh() = runTest {
        val url = "https://example.test/sub"
        var body = XrayJsonSubscriptionFixtures.body
        var userinfo = "upload=1; download=1; total=10; expire=3000"
        val now = 2_000_000L
        val source = FakeLocationsDataSource()
        val repo = experimentalRepository(source, HttpClient(MockEngine {
            respond(body, headers = if (userinfo.isEmpty()) headersOf()
                else headersOf("subscription-userinfo", userinfo))
        }), nowEpochMs = { now })
        assertTrue(repo.importText(url))
        val imported = assertNotNull(source.stored)
        val selectedId = imported.locations.last().storageId
        val manual = LocationEntry.from("manual", LocationConfig(name = "Manual",
            kind = org.olcbox.app.net.LocationKind.Vless,
            xrayConfig = kotlinx.serialization.json.buildJsonObject { }))
        source.stored = imported.copy(locations = imported.locations + manual)
        repo.setActiveLocationId(selectedId)
        val before = assertNotNull(source.stored)

        // Remnawave returns informational, non-runnable entries with a 200 and
        // current subscription-userinfo after a quota limit is reached.
        body = XrayJsonSubscriptionFixtures.informationalBody
        userinfo = ""
        assertEquals(0, repo.refreshSubscription(url).updatedCount)
        assertEquals(before, source.stored, "a missing header must not invent a limit")

        userinfo = "upload=6; download=5; total=10; expire=3000"
        val rejected = repo.refreshSubscription(url)
        assertEquals(0, rejected.updatedCount)
        assertTrue(rejected.hasFailures)
        val limited = assertNotNull(source.stored)
        assertEquals(before.activeLocationId, limited.activeLocationId)
        assertEquals(before.locations.map { it.storageId to it.location.xrayConfig },
            limited.locations.map { it.storageId to it.location.xrayConfig })
        assertEquals(manual, limited.locations.single { it.storageId == manual.storageId })
        assertEquals(before.locations.first().metadata?.subscription?.lastRefreshAtEpochMs,
            limited.locations.first().metadata?.subscription?.lastRefreshAtEpochMs)
        assertEquals(11L, limited.locations.single { it.storageId == selectedId }.metadata?.subscription?.usedBytes)
        assertEquals(10L, limited.locations.single { it.storageId == selectedId }.metadata?.subscription?.totalBytes)
        assertEquals("Subscription traffic limit reached",
            SubscriptionTunnelAccess.denial(limited, selectedId, now, RuntimeLocationPolicy.ExperimentalDebug))
        assertNull(repo.getActiveLocation())

        body = XrayJsonSubscriptionFixtures.body
        userinfo = "upload=1; download=2; total=10; expire=4000"
        assertEquals(1, repo.refreshSubscription(url).updatedCount)
        assertNull(SubscriptionTunnelAccess.denial(assertNotNull(source.stored), selectedId, now,
            RuntimeLocationPolicy.ExperimentalDebug))
        assertNotNull(repo.getActiveLocation())
    }

    @Test
    fun informationalExpiryReplyKeepsHostsButBlocksTunnel() = runTest {
        val url = "https://example.test/sub"
        var body = XrayJsonSubscriptionFixtures.body
        var userinfo = "upload=1; download=1; total=10; expire=3000"
        val now = 2_000_000L
        val source = FakeLocationsDataSource()
        val repo = experimentalRepository(source, HttpClient(MockEngine {
            respond(body, headers = headersOf("subscription-userinfo", userinfo))
        }), nowEpochMs = { now })
        assertTrue(repo.importText(url))
        val selectedId = assertNotNull(source.stored).locations.last().storageId
        repo.setActiveLocationId(selectedId)
        val before = assertNotNull(source.stored)

        body = XrayJsonSubscriptionFixtures.informationalBody
        userinfo = "upload=1; download=1; total=10; expire=1999"
        val rejected = repo.refreshSubscription(url)
        assertEquals(0, rejected.updatedCount)
        assertTrue(rejected.hasFailures)
        val expired = assertNotNull(source.stored)
        assertEquals(before.locations.map { it.storageId to it.location.xrayConfig },
            expired.locations.map { it.storageId to it.location.xrayConfig })
        assertEquals(before.locations.first().metadata?.subscription?.lastRefreshAtEpochMs,
            expired.locations.first().metadata?.subscription?.lastRefreshAtEpochMs)
        assertEquals(1_999_000L, expired.locations.single { it.storageId == selectedId }
            .metadata?.subscription?.expiresAtEpochMs)
        assertEquals("Subscription expired",
            SubscriptionTunnelAccess.denial(expired, selectedId, now, RuntimeLocationPolicy.ExperimentalDebug))
        assertNull(repo.getActiveLocation())
    }

    @Test
    fun emptySuccessReplyStillAppliesFreshTrafficDenial() = runTest {
        val url = "https://example.test/sub"
        var body = XrayJsonSubscriptionFixtures.body
        var userinfo = "upload=1; download=1; total=10; expire=3000"
        val now = 2_000_000L
        val source = FakeLocationsDataSource()
        val repo = experimentalRepository(source, HttpClient(MockEngine {
            respond(body, headers = headersOf("subscription-userinfo", userinfo))
        }), nowEpochMs = { now })
        assertTrue(repo.importText(url))
        val before = assertNotNull(source.stored)
        val selectedId = assertNotNull(before.activeLocationId)

        body = ""
        userinfo = "upload=6; download=5; total=10; expire=3000"
        val rejected = repo.refreshSubscription(url)
        assertEquals(0, rejected.updatedCount)
        assertEquals(SubscriptionRefreshError.Empty, rejected.failures.single().error)
        val limited = assertNotNull(source.stored)
        assertEquals(before.locations.map { it.storageId to it.location.xrayConfig },
            limited.locations.map { it.storageId to it.location.xrayConfig })
        assertEquals(before.locations.first().metadata?.subscription?.lastRefreshAtEpochMs,
            limited.locations.first().metadata?.subscription?.lastRefreshAtEpochMs)
        assertEquals("Subscription traffic limit reached",
            SubscriptionTunnelAccess.denial(limited, selectedId, now, RuntimeLocationPolicy.ExperimentalDebug))
        assertNull(repo.getActiveLocation())
    }

    @Test
    fun ambiguousSelectedXrayRefreshKeepsProfilesButAppliesFreshExpiryDenial() = runTest {
        val first = XrayJsonSubscriptionFixtures.vless
        val second = first.replace("Synthetic exit A", "Synthetic exit C")
            .replace("00000000-0000-4000-8000-000000000000", "11111111-1111-4111-8111-111111111111")
        var body = "[$first,$second]"
        var userinfo = "upload=1; download=1; total=10; expire=3000"
        val now = 2_000_000L
        val source = FakeLocationsDataSource()
        val repo = experimentalRepository(source, HttpClient(MockEngine {
            respond(body, headers = headersOf("subscription-userinfo", userinfo))
        }), nowEpochMs = { now })
        assertTrue(repo.importText("https://example.test/sub"))
        val selectedId = source.stored!!.locations[1].storageId
        repo.setActiveLocationId(selectedId)
        val before = assertNotNull(source.stored)

        body = "[${second.replace("11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222")},$first]"
        userinfo = "upload=1; download=1; total=10; expire=1999"
        val report = repo.refreshSubscription("https://example.test/sub")

        assertEquals(0, report.updatedCount)
        assertEquals(SubscriptionRefreshError.Rejected, report.failures.single().error)
        val stored = assertNotNull(source.stored)
        assertEquals(selectedId, stored.activeLocationId)
        assertEquals(before.locations.map { it.storageId to it.location.xrayConfig },
            stored.locations.map { it.storageId to it.location.xrayConfig })
        assertEquals(before.locations[1].metadata?.subscription?.lastRefreshAtEpochMs,
            stored.locations[1].metadata?.subscription?.lastRefreshAtEpochMs)
        assertEquals(1_999_000L, stored.locations.single { it.storageId == selectedId }.metadata?.subscription?.expiresAtEpochMs)
        assertEquals(before.locations[1].metadata?.subscription?.usedBytes,
            stored.locations[1].metadata?.subscription?.usedBytes)
        assertEquals("Subscription expired",
            SubscriptionTunnelAccess.denial(stored, selectedId, now, RuntimeLocationPolicy.ExperimentalDebug))
        assertNull(repo.getActiveLocation())
    }

    @Test
    fun ambiguousSelectedXrayRefreshRetainsEarlierFutureExpiry() = runTest {
        val first = XrayJsonSubscriptionFixtures.vless
        val second = first.replace("Synthetic exit A", "Synthetic exit C")
            .replace("00000000-0000-4000-8000-000000000000", "11111111-1111-4111-8111-111111111111")
        var body = "[$first,$second]"
        var userinfo = "upload=1; download=1; total=10; expire=3000"
        val now = 2_000_000L
        val source = FakeLocationsDataSource()
        val repo = experimentalRepository(source, HttpClient(MockEngine {
            respond(body, headers = headersOf("subscription-userinfo", userinfo))
        }), nowEpochMs = { now })
        assertTrue(repo.importText("https://example.test/sub"))
        val selectedId = source.stored!!.locations[1].storageId
        repo.setActiveLocationId(selectedId)
        val before = assertNotNull(source.stored)

        body = "[${second.replace("11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222")},$first]"
        userinfo = "upload=1; download=1; total=10; expire=2100"
        val report = repo.refreshSubscription("https://example.test/sub")

        assertEquals(0, report.updatedCount)
        assertEquals(SubscriptionRefreshError.Rejected, report.failures.single().error)
        val stored = assertNotNull(source.stored)
        assertEquals(selectedId, stored.activeLocationId)
        assertEquals(before.locations.map { it.storageId to it.location.xrayConfig },
            stored.locations.map { it.storageId to it.location.xrayConfig })
        assertEquals(before.locations[1].metadata?.subscription?.lastRefreshAtEpochMs,
            stored.locations[1].metadata?.subscription?.lastRefreshAtEpochMs)
        assertEquals(2_100_000L, stored.locations[1].metadata?.subscription?.expiresAtEpochMs)
        assertNull(SubscriptionTunnelAccess.denial(stored, selectedId, now, RuntimeLocationPolicy.ExperimentalDebug))
        assertEquals("Subscription expired",
            SubscriptionTunnelAccess.denial(stored, selectedId, 2_100_000L, RuntimeLocationPolicy.ExperimentalDebug))

        userinfo = "upload=1; download=1; total=10; expire=4000"
        assertEquals(0, repo.refreshSubscription("https://example.test/sub").updatedCount)
        assertEquals(2_100_000L, source.stored!!.locations.single { it.storageId == selectedId }
            .metadata?.subscription?.expiresAtEpochMs)
    }

    @Test
    fun ambiguousSelectedXrayRefreshRetainsTighterQuotaBeforeLimit() = runTest {
        val first = XrayJsonSubscriptionFixtures.vless
        val second = first.replace("Synthetic exit A", "Synthetic exit C")
            .replace("00000000-0000-4000-8000-000000000000", "11111111-1111-4111-8111-111111111111")
        var body = "[$first,$second]"
        var userinfo = "upload=1; download=1; total=10; expire=3000"
        val now = 2_000_000L
        val source = FakeLocationsDataSource()
        val repo = experimentalRepository(source, HttpClient(MockEngine {
            respond(body, headers = headersOf("subscription-userinfo", userinfo))
        }), nowEpochMs = { now })
        assertTrue(repo.importText("https://example.test/sub"))
        val selectedId = source.stored!!.locations[1].storageId
        repo.setActiveLocationId(selectedId)
        val before = assertNotNull(source.stored)

        body = "[${second.replace("11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222")},$first]"
        userinfo = "upload=1; download=2; total=5; expire=3000"
        val report = repo.refreshSubscription("https://example.test/sub")

        assertEquals(0, report.updatedCount)
        assertEquals(SubscriptionRefreshError.Rejected, report.failures.single().error)
        val stored = assertNotNull(source.stored)
        assertEquals(selectedId, stored.activeLocationId)
        assertEquals(before.locations.map { it.storageId to it.location.xrayConfig },
            stored.locations.map { it.storageId to it.location.xrayConfig })
        assertEquals(before.locations[1].metadata?.subscription?.lastRefreshAtEpochMs,
            stored.locations[1].metadata?.subscription?.lastRefreshAtEpochMs)
        assertEquals(3L, stored.locations[1].metadata?.subscription?.usedBytes)
        assertEquals(5L, stored.locations[1].metadata?.subscription?.totalBytes)
        assertNull(SubscriptionTunnelAccess.denial(stored, selectedId, now, RuntimeLocationPolicy.ExperimentalDebug))

        userinfo = "upload=0; download=1; total=20; expire=4000"
        assertEquals(0, repo.refreshSubscription("https://example.test/sub").updatedCount)
        val afterLooserHeader = source.stored!!.locations.single { it.storageId == selectedId }.metadata?.subscription
        assertEquals(3L, afterLooserHeader?.usedBytes)
        assertEquals(5L, afterLooserHeader?.totalBytes)
    }

    @Test
    fun xrayJsonImportRetainsLiteralUserUrlDespiteMoveHeader() = runTest {
        val source = FakeLocationsDataSource()
        val repo = experimentalRepository(source, HttpClient(MockEngine {
            respond(XrayJsonSubscriptionFixtures.body, headers = headersOf("new-url", "https://example.test/json"))
        }))

        assertTrue(repo.importText("https://example.test/literal"))
        assertTrue(assertNotNull(source.stored).locations.all { it.subscriptionUrl == "https://example.test/literal" })
    }

    @Test
    fun http200HwidDenialDoesNotRetryAsBrowserOrPublish() = runTest {
        var requests = 0
        val source = FakeLocationsDataSource()
        val repo = experimentalRepository(source, HttpClient(MockEngine {
            requests++
            respond(XrayJsonSubscriptionFixtures.body,
                headers = headersOf("x-hwid-active" to listOf("true"), "x-hwid-max-devices-reached" to listOf("true")))
        }))

        assertFalse(repo.importText("https://example.test/sub"))
        assertEquals(1, requests)
        assertNull(source.stored)
    }

    @Test
    fun explicitHwidDenialBlocksCachedHostsUntilAValidRefresh() = runTest {
        for (denialHeader in listOf("x-hwid-max-devices-reached", "x-hwid-not-supported")) {
            var deny = false
            val denialStatus = if (denialHeader == "x-hwid-not-supported") HttpStatusCode.NotFound else HttpStatusCode.OK
            val source = FakeLocationsDataSource()
            val repo = experimentalRepository(source, HttpClient(MockEngine {
                if (deny) {
                    respond(XrayJsonSubscriptionFixtures.body,
                        status = denialStatus,
                        headers = headersOf(denialHeader, "true"))
                } else {
                    respond(XrayJsonSubscriptionFixtures.body,
                        headers = headersOf("subscription-userinfo", "upload=1; download=1; total=100; expire=3000"))
                }
            }), nowEpochMs = { 2_000_000L })
            val url = "https://example.test/sub"
            assertTrue(repo.importText(url), denialHeader)
            val before = assertNotNull(source.stored)
            val oldExport = repo.exportBundle()
            val selectedId = assertNotNull(before.activeLocationId)
            assertNotNull(repo.getActiveLocation(), denialHeader)

            deny = true
            val report = repo.refreshSubscription(url)
            assertEquals(0, report.updatedCount, denialHeader)
            assertEquals(SubscriptionRefreshError.DeviceDenied, report.failures.single().error, denialHeader)
            assertEquals(denialStatus.value, report.failures.single().statusCode, denialHeader)
            assertEquals(before.locations.map { it.storageId to it.location.xrayConfig },
                source.stored!!.locations.map { it.storageId to it.location.xrayConfig }, denialHeader)
            assertEquals(selectedId, source.stored!!.activeLocationId, denialHeader)
            assertEquals("This device is not allowed to use the subscription",
                SubscriptionTunnelAccess.denial(source.stored!!, selectedId, 2_000_000L,
                    RuntimeLocationPolicy.ExperimentalDebug), denialHeader)
            assertNull(repo.getActiveLocation(), "cached host must be blocked after $denialHeader")

            assertTrue(repo.importText(oldExport), "restoring an older export should remain possible")
            assertNull(repo.getActiveLocation(), "old export must not clear a known HWID refusal")

            deny = false
            assertEquals(1, repo.refreshSubscription(url).updatedCount, denialHeader)
            assertNotNull(repo.getActiveLocation(), "valid refresh must restore access after $denialHeader")
        }
    }

    @Test
    fun transientRefreshFailureDoesNotBecomeDeviceDenial() = runTest {
        var fail = false
        val source = FakeLocationsDataSource()
        val repo = experimentalRepository(source, HttpClient(MockEngine {
            if (fail) respond("unavailable", status = HttpStatusCode.InternalServerError)
            else respond(XrayJsonSubscriptionFixtures.body,
                headers = headersOf("subscription-userinfo", "upload=1; download=1; total=100; expire=3000"))
        }), nowEpochMs = { 2_000_000L })
        val url = "https://example.test/sub"
        assertTrue(repo.importText(url))
        fail = true
        assertEquals(SubscriptionRefreshError.ServerError, repo.refreshSubscription(url).failures.single().error)
        assertNotNull(repo.getActiveLocation(), "temporary host failure must not revoke cached access")
    }

    @Test
    fun reachedDeviceCountAloneDoesNotRejectAnAlreadyAllowedDevice() = runTest {
        val source = FakeLocationsDataSource()
        val repo = experimentalRepository(source, HttpClient(MockEngine {
            respond(XrayJsonSubscriptionFixtures.body, headers = headersOf("x-hwid-limit", "true"))
        }))
        assertTrue(repo.importText("https://example.test/sub"))
        assertNotNull(repo.getActiveLocation())
    }

    @Test
    fun rejectedIdentityRefreshDoesNotUseSpareAddressToMaskDenial() = runTest {
        val url = "https://example.test/sub"
        val spare = "https://spare.example.test/sub"
        var deny = false
        val asked = mutableListOf<String>()
        val source = FakeLocationsDataSource()
        val repo = experimentalRepository(source, HttpClient(MockEngine { request ->
            asked += request.url.toString()
            if (deny && request.url.host == "example.test") respond("denied", HttpStatusCode.Forbidden)
            else respond(XrayJsonSubscriptionFixtures.body, headers = headersOf("fallback-url", spare))
        }))
        assertTrue(repo.importText(url))
        val before = assertNotNull(source.stored)

        deny = true
        asked.clear()
        assertEquals(0, repo.refreshSubscription(url).updatedCount)
        assertEquals(listOf(url), asked)
        assertEquals(before, source.stored)
    }

    @Test
    fun failedRefreshRetainsKnownQuotaAndExpiryWithoutMakingItUnlimited() = runTest {
        var reject = false
        var now = 2_000_000L
        val source = FakeLocationsDataSource()
        val repo = experimentalRepository(source, HttpClient(MockEngine {
            if (reject) respond("denied", HttpStatusCode.Forbidden)
            else respond(
                XrayJsonSubscriptionFixtures.body,
                headers = headersOf("subscription-userinfo", "upload=6; download=5; total=10; expire=2001")
            )
        }), nowEpochMs = { now })
        assertTrue(repo.importText("https://example.test/sub"))
        val before = assertNotNull(source.stored)
        val quota = assertNotNull(before.locations.first().metadata?.subscription)
        assertEquals(11L, quota.usedBytes)
        assertEquals(10L, quota.totalBytes)
        assertFalse(quota.isUsableAt(now))

        reject = true
        now = 2_002_000L
        assertEquals(0, repo.refreshSubscription("https://example.test/sub").updatedCount)
        assertEquals(before, source.stored)
        assertFalse(assertNotNull(source.stored).locations.first().metadata!!.subscription!!.isUsableAt(now))
        assertNull(repo.getActiveLocation(), "a known depleted subscription cannot supply an active tunnel")
    }

    @Test
    fun expiredStoredSubscriptionCannotSupplyAnActiveTunnelAfterFailedRefresh() = runTest {
        var now = 2_000_000L
        var reject = false
        val source = FakeLocationsDataSource()
        val repo = experimentalRepository(source, HttpClient(MockEngine {
            if (reject) respond("offline", HttpStatusCode.ServiceUnavailable)
            else respond(XrayJsonSubscriptionFixtures.body,
                headers = headersOf("subscription-userinfo", "upload=1; download=1; total=10; expire=2001"))
        }), nowEpochMs = { now })
        assertTrue(repo.importText("https://example.test/sub"))
        assertNotNull(repo.getActiveLocation())

        now = 2_002_000L
        reject = true
        assertEquals(0, repo.refreshSubscription("https://example.test/sub").updatedCount)
        assertNull(repo.getActiveLocation())
        assertEquals(2, repo.getAllLocations().size, "retain the URL and last valid version for later refresh")
    }

    @Test
    fun validRefreshWithoutUserinfoRetainsPreviouslyKnownLimitAndExpiry() = runTest {
        var userinfo = "upload=6; download=5; total=10; expire=2001"
        var now = 2_000_000L
        val source = FakeLocationsDataSource()
        val repo = experimentalRepository(source, HttpClient(MockEngine {
            respond(XrayJsonSubscriptionFixtures.body,
                headers = if (userinfo.isEmpty()) headersOf() else headersOf("subscription-userinfo", userinfo))
        }), nowEpochMs = { now })
        assertTrue(repo.importText("https://example.test/sub"))

        now = 2_002_000L
        userinfo = ""
        assertEquals(1, repo.refreshSubscription("https://example.test/sub").updatedCount)
        assertNull(repo.getActiveLocation())
        val metadata = source.stored!!.locations.first().metadata!!.subscription!!
        assertEquals(11L, metadata.usedBytes)
        assertEquals(10L, metadata.totalBytes)
        assertEquals(2_001_000L, metadata.expiresAtEpochMs)
    }

    @Test
    fun oldBundleExpiryRemainsEnforcedOfflineAndAfterHeaderlessRefresh() = runTest {
        val oldBundle = kotlinx.serialization.json.Json.decodeFromString<LocationBundleV4>("""
            {"active_location_id":"old","locations":[{"storage_id":"old","name":"Old",
             "subscription_url":"https://example.test/sub","endpoint":{"room_id":"room","key":"${"k".repeat(64)}"},
             "metadata":{"subscription":{"expires_at_epoch_ms":2001000}}}]}
        """.trimIndent())
        var online = false
        val source = FakeLocationsDataSource(oldBundle)
        val repo = experimentalRepository(source, HttpClient(MockEngine {
            if (online) respond(XrayJsonSubscriptionFixtures.body)
            else respond("offline", HttpStatusCode.ServiceUnavailable)
        }), nowEpochMs = { 2_002_000L })

        assertNull(repo.getActiveLocation(), "legacy persisted expiry is known, even while offline")
        assertEquals(0, repo.refreshSubscription("https://example.test/sub").updatedCount)
        assertNull(repo.getActiveLocation())

        online = true
        assertEquals(1, repo.refreshSubscription("https://example.test/sub").updatedCount)
        assertNull(repo.getActiveLocation(), "a headerless refresh cannot erase a known old expiry")
        assertTrue(source.stored!!.locations.all { it.metadata?.subscription?.expiryKnown == true })
    }

    @Test
    fun malformedNegativeExpiryRemainsUnknownRatherThanUnlimited() = runTest {
        val source = FakeLocationsDataSource()
        val repo = experimentalRepository(source, HttpClient(MockEngine {
            respond(XrayJsonSubscriptionFixtures.body,
                headers = headersOf("subscription-userinfo", "upload=1; download=1; total=10; expire=-1"))
        }))
        assertTrue(repo.importText("https://example.test/sub"))
        assertEquals(
            org.olcbox.app.data.model.SubscriptionMetadata.AccessState.UNKNOWN,
            source.stored!!.locations.first().metadata!!.subscription!!.accessStateAt(2_000_000L)
        )
    }

    @Test
    fun plainHttpSubscriptionUrlIsRejectedBeforeAnyRequest() = runTest {
        var requests = 0
        val source = FakeLocationsDataSource()
        val repo = experimentalRepository(
            dataSource = source,
            httpClient = HttpClient(MockEngine {
                requests++
                respond("olcrtc://wbstream?vp8channel@room#${"d".repeat(64)}${'$'}Unsafe")
            })
        )

        assertFalse(repo.importText("http://example.test/sub?token=private"))
        assertEquals(0, requests)
        assertNull(source.stored)
    }

    @Test
    fun refreshSingleSubscriptionPreservesOtherSubscriptions() = runTest {
        val source = FakeLocationsDataSource(
            stored = LocationBundleV4(
                activeLocationId = "beta",
                locations = listOf(
                    LocationEntry.from(
                        "alpha",
                        LocationConfig("Alpha", "room-alpha", "a".repeat(64), LocationConfig.PROVIDER_WB_STREAM),
                        subscriptionUrl = "https://example.test/alpha"
                    ),
                    LocationEntry.from(
                        "beta",
                        LocationConfig("Beta", "room-beta", "b".repeat(64), LocationConfig.PROVIDER_WB_STREAM),
                        subscriptionUrl = "https://example.test/beta"
                    )
                )
            )
        )
        val engine = MockEngine { request ->
            respond("olcrtc://wbstream?vp8channel@room-alpha-new#${"c".repeat(64)}${'$'}Alpha")
        }

        val updated = experimentalRepository(
            dataSource = source,
            httpClient = HttpClient(engine),
            deviceIdentityProvider = StaticIdentityProvider("hwid-test")
        ).refreshSubscription("https://example.test/alpha")

        val bundle = source.stored
        assertEquals(1, updated.updatedCount)
        assertFalse(updated.hasFailures)
        assertNotNull(bundle)
        assertEquals(listOf("beta", "imported_alpha"), bundle.locations.map { it.storageId })
        assertEquals("room-beta", bundle.locations.first { it.storageId == "beta" }.location.id)
        assertEquals("https://example.test/beta", bundle.locations.first { it.storageId == "beta" }.subscriptionUrl)
        assertEquals("room-alpha-new", bundle.locations.first { it.subscriptionUrl == "https://example.test/alpha" }.location.id)
        assertEquals("beta", bundle.activeLocationId)
    }

    @Test
    fun failedSingleSubscriptionRefreshDoesNotDropExistingSubscription() = runTest {
        val source = FakeLocationsDataSource(
            stored = LocationBundleV4(
                activeLocationId = "alpha",
                locations = listOf(
                    LocationEntry.from(
                        "alpha",
                        LocationConfig("Alpha", "room-alpha", "a".repeat(64), LocationConfig.PROVIDER_WB_STREAM),
                        subscriptionUrl = "https://example.test/alpha"
                    )
                )
            )
        )
        val engine = MockEngine {
            respond("<html>not a config</html>")
        }

        val updated = experimentalRepository(
            dataSource = source,
            httpClient = HttpClient(engine),
            deviceIdentityProvider = StaticIdentityProvider("hwid-test")
        ).refreshSubscription("https://example.test/alpha")

        val bundle = source.stored
        assertEquals(0, updated.updatedCount)
        // Unparseable body ⇒ the user is told the subscription returned nothing,
        // not the old ambiguous "not updated".
        assertEquals(SubscriptionRefreshError.Empty, updated.failures.single().error)
        assertNotNull(bundle)
        assertEquals(listOf("alpha"), bundle.locations.map { it.storageId })
        assertEquals("room-alpha", bundle.locations.single().location.id)
        assertEquals("alpha", bundle.activeLocationId)
    }

    @Test
    fun configShareRoundTripsTransportOptions() = runTest {
        val source = FakeLocationsDataSource()
        val config = LocationConfig(
            name = "Shared",
            id = "room",
            key = "d".repeat(64),
            bypassProvider = LocationConfig.PROVIDER_WB_STREAM,
            transport = LocationConfig.TRANSPORT_VP8CHANNEL,
            vp8Fps = 48,
            vp8Batch = 32
        )

        val shared = ConfigShareService.olcRtcUri(config)
        assertTrue("%" !in shared)

        experimentalRepository(source).importText(shared)

        val imported = source.stored
        assertNotNull(imported)
        assertEquals(48, imported.locations.single().location.vp8Fps)
        assertEquals(32, imported.locations.single().location.vp8Batch)
    }

    @Test
    fun subscriptionSharingListsDistinctUrls() {
        val first = LocationEntry.from(
            "first",
            LocationConfig("First", "room-a", "a".repeat(64), LocationConfig.PROVIDER_WB_STREAM),
            subscriptionUrl = "https://example.test/a"
        )
        val second = LocationEntry.from(
            "second",
            LocationConfig("Second", "room-b", "b".repeat(64), LocationConfig.PROVIDER_WB_STREAM),
            subscriptionUrl = "https://example.test/b"
        )
        val third = LocationEntry.from(
            "third",
            LocationConfig("Third", "room-c", "c".repeat(64), LocationConfig.PROVIDER_WB_STREAM),
            subscriptionUrl = "https://example.test/a"
        )

        val items = ConfigShareService.subscriptionShareItems(listOf(first, second, third))

        assertEquals(listOf("https://example.test/a", "https://example.test/b"), items.map { it.url })
        assertEquals(2, items.first().locationCount)
        assertEquals("https://example.test/b", ConfigShareService.subscriptionQrText(items[1].url))
    }

    @Test
    fun importsCryptLinkDecryptingToInlineOlcrtc() = runTest {
        // Fixtures from the Rust coordinator crypt_link::tests::print_fixture (key=[42;32]).
        // FIXTURE_LINE_BLOB = encrypt("olcrtc://telemost?vp8channel@12345#deadbeefdeadbeef$DE").
        val keyB64 = "KioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKio="
        val lineBlob =
            "Zqjg1M-z4N-c8Tr6FkkyALBXUMbQOMBnSreruYGLPpucGBUyebLLhNxo8VqV9MXMEjfiDiJm-CB8Z0y2-4DObbUMS4Z_sJElAdEoELy2nP2LThWtmT4zTYTD7RHXyENGI6I4webacShIYERWoNE25A"
        val source = FakeLocationsDataSource()
        val repo = experimentalRepository(
            source,
            cryptCodec = CryptCodec(CryptCodec.decodeMaster(keyB64)!!)
        )

        val ok = repo.importText("olcrtc://crypt1/$lineBlob")

        assertTrue(ok)
        val imported = source.stored
        assertNotNull(imported)
        val loc = imported.locations.map { it.location }.first { it.id == "12345" }
        assertEquals("deadbeefdeadbeef", loc.key)
        assertEquals(LocationConfig.PROVIDER_TELEMOST, loc.bypassProvider)
    }

    @Test
    fun plainOlcrtcLinkStillImportsWithCryptCodecPresent() = runTest {
        // Marker-only: a plain link is untouched even when a codec is baked.
        val keyB64 = "KioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKio="
        val source = FakeLocationsDataSource()
        val repo = experimentalRepository(
            source,
            cryptCodec = CryptCodec(CryptCodec.decodeMaster(keyB64)!!)
        )

        val ok = repo.importText("olcrtc://telemost?vp8channel@99999#feedfacefeedface\$FI")

        assertTrue(ok)
        val loc = source.stored!!.locations.map { it.location }.first { it.id == "99999" }
        assertEquals("feedfacefeedface", loc.key)
    }

    @Test
    fun oldBundleWithoutKindDeserializesAsOlcrtc() {
        val json = """{"name":"X","id":"room","key":"k","bypass_provider":"telemost","transport":"vp8channel"}"""
        val cfg = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(LocationConfig.serializer(), json)
        assertEquals(org.olcbox.app.net.LocationKind.Olcrtc, cfg.kind)
        assertEquals(null, cfg.rawLink)
        assertTrue(cfg.isComplete())
    }

    @Test
    fun vlessKindNeedsParseableRawLink() {
        val ok = LocationConfig(
            name = "DE",
            kind = org.olcbox.app.net.LocationKind.Vless,
            rawLink = "vless://u@1.2.3.4:443?security=reality&pbk=P&sid=s&sni=x#DE"
        )
        assertTrue(ok.isComplete())
        val bad = ok.copy(rawLink = "garbage")
        assertTrue(!bad.isComplete())
    }

    @Test
    fun importsMixedOlcrtcAndVlessSubscription() = runTest {
        val body = """
            olcrtc://telemost?vp8channel@12345#deadbeefdeadbeef${'$'}DE-rtc
            vless://11111111-1111-1111-1111-111111111111@9.9.9.9:443?security=reality&pbk=P&sid=ab&sni=x&flow=xtls-rprx-vision&type=tcp#DE-vless
            hysteria2://PW@8.8.8.8:443?sni=h#DE-hy2
        """.trimIndent()
        val source = FakeLocationsDataSource()
        experimentalRepository(source).importText(body)
        val locs = source.stored!!.locations.map { it.location }
        assertEquals(3, locs.size)
        assertEquals(1, locs.count { it.kind == org.olcbox.app.net.LocationKind.Olcrtc })
        assertEquals(1, locs.count { it.kind == org.olcbox.app.net.LocationKind.Vless })
        assertEquals(1, locs.count { it.kind == org.olcbox.app.net.LocationKind.Hysteria2 })
    }

    @Test
    fun mixedImportRejectsMalformedVlessWithoutSavingPartialList() = runTest {
        val source = FakeLocationsDataSource()
        val body = """
            olcrtc://telemost?vp8channel@12345#deadbeefdeadbeef${'$'}DE-rtc
            vless://11111111-1111-1111-1111-111111111111@9.9.9.9:443?type=unsupported#DE-vless
        """.trimIndent()

        assertFalse(experimentalRepository(source).importText(body))
        assertNull(source.stored)
    }

    @Test
    fun mixedImportRejectsUnknownProtocolWithoutSavingPartialList() = runTest {
        val source = FakeLocationsDataSource()
        val body = """
            olcrtc://telemost?vp8channel@12345#deadbeefdeadbeef${'$'}DE-rtc
            tuic://11111111-1111-1111-1111-111111111111@9.9.9.9:443#DE-tuic
        """.trimIndent()

        assertFalse(experimentalRepository(source).importText(body))
        assertNull(source.stored)
    }

    @Test
    fun mixedImportKeepsOlcrtcAndVlessWithTheSameDisplayName() = runTest {
        val source = FakeLocationsDataSource()
        val body = """
            olcrtc://telemost?vp8channel@12345#deadbeefdeadbeef${'$'}Shared
            vless://11111111-1111-1111-1111-111111111111@9.9.9.9:443?security=reality&pbk=P&sid=ab&sni=x&type=tcp#Shared
        """.trimIndent()

        assertTrue(experimentalRepository(source).importText(body))
        val entries = source.stored!!.locations
        assertEquals(2, entries.size)
        assertEquals(2, entries.map { it.storageId }.toSet().size)
        assertEquals(
            setOf(org.olcbox.app.net.LocationKind.Olcrtc, org.olcbox.app.net.LocationKind.Vless),
            entries.map { it.kind }.toSet()
        )
        // Restricted-network ingress is opt-in. A new mixed subscription must
        // offer the ordinary host first even if the issuer listed OlcRTC first.
        assertEquals(org.olcbox.app.net.LocationKind.Vless, entries.first().kind)
        assertEquals(entries.first().storageId, source.stored!!.activeLocationId)
    }

    @Test
    fun malformedMixedRefreshRetainsTheWholePreviousListAndSelection() = runTest {
        val url = "https://example.test/mixed"
        val validVless = "vless://11111111-1111-1111-1111-111111111111@9.9.9.9:443?security=reality&pbk=P&sid=ab&sni=x&type=tcp#DE-vless"
        val olc = "olcrtc://telemost?vp8channel@12345#deadbeefdeadbeef${'$'}DE-rtc"
        var body = "$olc\n$validVless"
        val source = FakeLocationsDataSource()
        val repo = experimentalRepository(source, HttpClient(MockEngine { respond(body) }))
        assertTrue(repo.importText(url))
        val selected = source.stored!!.locations.last().storageId
        repo.setActiveLocationId(selected)
        val before = repo.getBundle()
        body = "$olc\nvless://11111111-1111-1111-1111-111111111111@9.9.9.9:443?type=unsupported#DE-vless"

        val report = repo.refreshSubscription(url)

        assertEquals(0, report.updatedCount)
        assertEquals(SubscriptionRefreshError.Empty, report.failures.single().error)
        assertEquals(before, repo.getBundle())
    }

    @Test
    fun refreshDoesNotGiveSelectedHy2IdentityToVlessAtTheSameEndpoint() = runTest {
        val url = "https://example.test/two-protocols"
        val vless = "vless://11111111-1111-1111-1111-111111111111@9.9.9.9:443?security=reality&pbk=P&sid=ab&sni=x&type=tcp#Vless"
        val hy2 = "hysteria2://PW@9.9.9.9:443?sni=x#Hy2"
        var body = "$vless\n$hy2"
        val source = FakeLocationsDataSource()
        val repo = experimentalRepository(source, HttpClient(MockEngine { respond(body) }))
        assertTrue(repo.importText(url))
        val selected = source.stored!!.locations.single { it.kind == org.olcbox.app.net.LocationKind.Hysteria2 }.storageId
        repo.setActiveLocationId(selected)
        body = "$hy2\n$vless"

        val report = repo.refreshSubscription(url)

        assertEquals(1, report.updatedCount)
        val active = repo.getActiveLocation()
        assertNotNull(active)
        assertEquals(selected, active.storageId)
        assertEquals(org.olcbox.app.net.LocationKind.Hysteria2, active.kind)
    }

    // ---- refresh failure reporting ----------------------------------------
    // A revoked token, a dead server and an offline device used to be
    // indistinguishable from "nothing changed", so users read every one of them
    // as the app being broken.

    private fun subscribedSource(url: String) = FakeLocationsDataSource(
        stored = LocationBundleV4(
            activeLocationId = "alpha",
            locations = listOf(
                LocationEntry.from(
                    "alpha",
                    LocationConfig("Alpha", "room-alpha", "a".repeat(64), LocationConfig.PROVIDER_WB_STREAM),
                    subscriptionUrl = url
                )
            )
        )
    )

    private fun repoRespondingWith(source: FakeLocationsDataSource, engine: MockEngine) =
        experimentalRepository(
            dataSource = source,
            httpClient = HttpClient(engine),
            deviceIdentityProvider = StaticIdentityProvider("hwid-test")
        )

    @Test
    fun revokedSubscriptionReportsRejectedWithStatus() = runTest {
        val url = "https://example.test/alpha"
        val source = subscribedSource(url)
        val engine = MockEngine { respond("gone", HttpStatusCode.NotFound) }

        val report = repoRespondingWith(source, engine).refreshSubscription(url)

        assertEquals(0, report.updatedCount)
        val failure = report.failures.single()
        assertEquals(SubscriptionRefreshError.Rejected, failure.error)
        assertEquals(404, failure.statusCode)
        assertTrue(failure.message().contains("revoked"))
        // the existing locations must survive a failed refresh
        assertEquals(listOf("alpha"), source.stored!!.locations.map { it.storageId })
    }

    @Test
    fun serverErrorIsReportedSeparatelyFromRejection() = runTest {
        val url = "https://example.test/alpha"
        val source = subscribedSource(url)
        val engine = MockEngine { respond("boom", HttpStatusCode.InternalServerError) }

        val report = repoRespondingWith(source, engine).refreshSubscription(url)

        val failure = report.failures.single()
        assertEquals(SubscriptionRefreshError.ServerError, failure.error)
        assertEquals(500, failure.statusCode)
    }

    @Test
    fun unreachableServerIsReportedAsUnreachable() = runTest {
        val url = "https://example.test/alpha"
        val source = subscribedSource(url)
        val engine = MockEngine { throw IllegalStateException("no route to host") }

        val report = repoRespondingWith(source, engine).refreshSubscription(url)

        val failure = report.failures.single()
        assertEquals(SubscriptionRefreshError.Unreachable, failure.error)
        assertNull(failure.statusCode)
    }

    @Test
    fun emptyBodyIsReportedAsEmpty() = runTest {
        val url = "https://example.test/alpha"
        val source = subscribedSource(url)
        val engine = MockEngine { respond("   ") }

        val report = repoRespondingWith(source, engine).refreshSubscription(url)

        assertEquals(SubscriptionRefreshError.Empty, report.failures.single().error)
    }

    @Test
    fun successfulRefreshReportsNoFailures() = runTest {
        val url = "https://example.test/alpha"
        val source = subscribedSource(url)
        val engine = MockEngine {
            respond("olcrtc://wbstream?vp8channel@room-alpha-new#${"c".repeat(64)}${'$'}Alpha")
        }

        val report = repoRespondingWith(source, engine).refreshSubscription(url)

        assertEquals(1, report.updatedCount)
        assertFalse(report.hasFailures)
        assertEquals("Server list updated", report.singleMessage())
    }

    @Test
    fun blankUrlRefreshIsANoOpReport() = runTest {
        val source = subscribedSource("https://example.test/alpha")
        val engine = MockEngine { respond("unused") }

        val report = repoRespondingWith(source, engine).refreshSubscription("   ")

        assertEquals(0, report.updatedCount)
        assertFalse(report.hasFailures)
    }

    // ---- provider headers: keeping users through a blocked domain -----------
    // Spelled as Happ reads them: fallback-url, new-url / new-domain, announce.

    private val listBody = "olcrtc://wbstream?vp8channel@room-alpha-new#${"c".repeat(64)}${'$'}Alpha"

    private fun FakeLocationsDataSource.subscription() = stored!!.locations.single().metadata?.subscription
    private fun FakeLocationsDataSource.listUrl() = stored!!.locations.single().subscriptionUrl

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    @Test
    fun anAnnouncementIsKeptDecodedAndCappedUntilTheProviderSendsZero() = runTest {
        val url = "https://example.test/alpha"
        val source = subscribedSource(url)
        val note = "Серверы переехали, обновите подписку"
        val encoded = "base64:" + kotlin.io.encoding.Base64.Default.encode(note.encodeToByteArray())
        var announce: String? = encoded
        val repo = repoRespondingWith(source, MockEngine {
            val value = announce
            if (value == null) respond(listBody) else respond(listBody, headers = headersOf("announce", value))
        })

        repo.refreshSubscription(url)
        assertEquals(note, source.subscription()?.announce)

        announce = null
        repo.refreshSubscription(url)
        assertEquals(note, source.subscription()?.announce, "an answer without the header keeps the note")

        announce = "x".repeat(300)
        repo.refreshSubscription(url)
        assertEquals(200, source.subscription()?.announce?.length)

        announce = "0"
        repo.refreshSubscription(url)
        assertNull(source.subscription()?.announce)
    }

    @Test
    fun aListThatDoesNotAnswerIsAskedAtItsSpareAddress() = runTest {
        val url = "https://example.test/alpha"
        val spare = "https://spare.example.test/alpha"
        val source = subscribedSource(url)
        var primaryUp = true
        val asked = mutableListOf<String>()
        val repo = repoRespondingWith(source, MockEngine { request ->
            asked += request.url.toString()
            when {
                request.url.host == "example.test" && primaryUp ->
                    respond(listBody, headers = headersOf("fallback-url", spare))
                request.url.host == "example.test" -> throw IllegalStateException("blocked")
                else -> respond(listBody)
            }
        })

        repo.refreshSubscription(url)
        assertEquals(spare, source.subscription()?.fallbackUrl)

        primaryUp = false
        asked.clear()
        val report = repo.refreshSubscription(url)
        assertEquals(1, report.updatedCount)
        assertFalse(report.hasFailures)
        // The list's own address first (asked again in compatibility mode, as any
        // failed fetch is), the spare only after it.
        assertEquals(spare, asked.last())
        assertTrue(asked.dropLast(1).isNotEmpty() && asked.dropLast(1).all { it == url }, asked.toString())
        // The spare answered, but the list is still filed under its own address,
        // and the spare stays known for the next time.
        assertEquals(url, source.listUrl())
        assertEquals(spare, source.subscription()?.fallbackUrl)
    }

    @Test
    fun aListThatMovedIsFiledAtItsNewAddress() = runTest {
        val url = "https://example.test/alpha?token=1"
        val source = subscribedSource(url)
        val repo = repoRespondingWith(source, MockEngine { request ->
            if (request.url.host == "example.test") {
                respond(listBody, headers = headersOf("new-url", "https://new.example.test/beta"))
            } else {
                respond(listBody)
            }
        })

        repo.refreshSubscription(url)
        assertEquals("https://new.example.test/beta", source.listUrl())
    }

    @Test
    fun aNewDomainKeepsTheListsPathAndQuery() = runTest {
        val url = "https://example.test/sub/alpha?token=1"
        val source = subscribedSource(url)
        val repo = repoRespondingWith(source, MockEngine { request ->
            if (request.url.host == "example.test") {
                respond(listBody, headers = headersOf("new-domain", "mirror.example.test"))
            } else {
                respond(listBody)
            }
        })

        repo.refreshSubscription(url)
        assertEquals("https://mirror.example.test/sub/alpha?token=1", source.listUrl())
    }

    // Legacy plain-http subscriptions must remain untouched: even a refresh
    // must not send their bearer URL or consume untrusted response headers.
    @Test
    fun legacyPlainHttpSubscriptionIsNotFetchedOnRefresh() = runTest {
        val url = "http://example.test/alpha"
        val source = subscribedSource(url)
        var requests = 0
        val repo = repoRespondingWith(source, MockEngine {
            requests++
            respond(
                listBody,
                headers = headersOf(
                    "new-url" to listOf("https://elsewhere.example.test/alpha"),
                    "fallback-url" to listOf("https://spare.example.test/alpha"),
                    "announce" to listOf("still shown")
                )
            )
        })

        repo.refreshSubscription(url)
        assertEquals(0, requests)
        assertEquals(url, source.listUrl())
        assertNull(source.subscription()?.fallbackUrl)
        assertNull(source.subscription()?.announce)
    }

    @Test
    fun aMoveToAnythingButHttpsIsIgnored() = runTest {
        val url = "https://example.test/alpha"
        val source = subscribedSource(url)
        val repo = repoRespondingWith(source, MockEngine {
            respond(
                listBody,
                headers = headersOf(
                    "new-url" to listOf("http://downgrade.example.test/alpha"),
                    "fallback-url" to listOf("javascript:alert(1)")
                )
            )
        })

        repo.refreshSubscription(url)
        assertEquals(url, source.listUrl())
        assertNull(source.subscription()?.fallbackUrl)
    }

    // ---- deleteSubscription ------------------------------------------------
    // Removing a subscription must take exactly its own locations with it, leave
    // manually added ones alone, and never leave the bundle pointing at a location
    // that no longer exists.

    private fun subEntry(storageId: String, subscriptionUrl: String?) = LocationEntry.from(
        storageId = storageId,
        location = LocationConfig(
            name = storageId,
            id = "room-$storageId",
            key = "k".repeat(64)
        ),
        subscriptionUrl = subscriptionUrl
    )

    private fun subSource(active: String?, vararg entries: LocationEntry) = FakeLocationsDataSource(
        LocationBundleV4(activeLocationId = active, locations = entries.toList())
    )

    @Test
    fun deleteSubscriptionRemovesOnlyThatSubscription() = runTest {
        val subA = "https://proofkit.org/sub/aaa"
        val subB = "https://proofkit.org/sub/bbb"
        val source = subSource("a1", subEntry("a1", subA), subEntry("a2", subA), subEntry("b1", subB))
        val repo = experimentalRepository(source)

        assertEquals(2, repo.deleteSubscription(subA))
        assertEquals(listOf("b1"), repo.getAllLocations().map { it.storageId })
    }

    @Test
    fun deleteSubscriptionKeepsManuallyAddedLocations() = runTest {
        val subA = "https://proofkit.org/sub/aaa"
        val source = subSource("manual", subEntry("manual", null), subEntry("a1", subA))
        val repo = experimentalRepository(source)

        assertEquals(1, repo.deleteSubscription(subA))
        assertEquals(listOf("manual"), repo.getAllLocations().map { it.storageId })
    }

    @Test
    fun deleteSubscriptionRepointsActiveLocation() = runTest {
        val subA = "https://proofkit.org/sub/aaa"
        val subB = "https://proofkit.org/sub/bbb"
        val source = subSource("a1", subEntry("a1", subA), subEntry("b1", subB))
        val repo = experimentalRepository(source)

        repo.deleteSubscription(subA)

        assertEquals("b1", repo.getActiveLocationId())
    }

    @Test
    fun deleteSubscriptionClearsActiveWhenNothingRemains() = runTest {
        val subA = "https://proofkit.org/sub/aaa"
        val repo = experimentalRepository(subSource("a1", subEntry("a1", subA)))

        assertEquals(1, repo.deleteSubscription(subA))
        assertTrue(repo.getAllLocations().isEmpty())
        assertNull(repo.getActiveLocationId())
    }

    @Test
    fun deleteSubscriptionTrimsTheUrl() = runTest {
        val subA = "https://proofkit.org/sub/aaa"
        val repo = experimentalRepository(subSource("a1", subEntry("a1", subA)))

        assertEquals(1, repo.deleteSubscription("  $subA  "))
    }

    @Test
    fun deleteSubscriptionIgnoresUnknownAndBlankUrls() = runTest {
        val subA = "https://proofkit.org/sub/aaa"
        val repo = experimentalRepository(subSource("a1", subEntry("a1", subA)))

        assertEquals(0, repo.deleteSubscription("https://example.com/other"))
        assertEquals(0, repo.deleteSubscription("   "))
        assertEquals(1, repo.getAllLocations().size)
    }

    // --- partner (Happ) links -------------------------------------------

    private class FakeResolver(private val result: PartnerLinkResult) : PartnerLinkResolver {
        var asked: String? = null
        override suspend fun resolve(link: String): PartnerLinkResult {
            asked = link
            return result
        }
    }

    private val happLink = "happ://crypt5/fixtureEncryptedLinkPayload1234"

    @Test
    fun aPastedHappLinkIsResolvedThenImportedLikeAnyOtherLink() = runTest {
        val source = FakeLocationsDataSource()
        val resolved = "olcrtc://wbstream?seichannel@room-01#${"c".repeat(64)}${'$'}DE / partner"
        val resolver = FakeResolver(PartnerLinkResult.Resolved(resolved))

        val imported = experimentalRepository(
            dataSource = source,
            partnerLinkResolver = resolver,
        ).importText(happLink)

        assertTrue(imported)
        assertEquals(happLink, resolver.asked)
        assertNotNull(source.stored)
        assertEquals(1, source.stored!!.locations.size)
    }

    @Test
    fun anUnresolvableHappLinkFailsWithoutTouchingStoredLocations() = runTest {
        val source = FakeLocationsDataSource()
        val resolver = FakeResolver(PartnerLinkResult.NotFound)

        val imported = experimentalRepository(
            dataSource = source,
            partnerLinkResolver = resolver,
        ).importText(happLink)

        assertFalse(imported)
        assertNull(source.stored)
    }

    @Test
    fun aPlainInlineConfigNeverGoesNearTheResolver() = runTest {
        val source = FakeLocationsDataSource()
        val resolver = FakeResolver(PartnerLinkResult.NotFound)
        val input = "olcrtc://wbstream?seichannel@room-01#${"d".repeat(64)}${'$'}RU / plain"

        val imported = experimentalRepository(
            dataSource = source,
            partnerLinkResolver = resolver,
        ).importText(input)

        assertTrue(imported)
        assertNull(resolver.asked)
    }

    // --- an encrypted subscription stays encrypted -------------------------
    //
    // The crypt link exists so the endpoint is unreadable; the app decrypted it,
    // stored the plaintext URL and threw the link away, so the Server lists row
    // printed exactly what the encryption was hiding.

    private val cryptKeyB64 = "KioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKio="
    private val cryptUrlBlob =
        "PUtUSvcFHP7RaUdnoe3kiTxMd5t8R2fhKbezwNn4b346MTOM9L_Efda6HedlY1EVxprVRQu-bTgBUwc38IT-23bvBIhBrlFmgOviGK__tX3d8jKEsvUkFKcdd3FnO39oQFSFVcZH6ATMTJuMH6O8Sw"
    private val cryptUrl = "https://proofkit.org/sub/TESTTOKEN/olcrtc?crypt=1"
    private val subscriptionBody = "olcrtc://telemost?vp8channel@12345#deadbeefdeadbeef\$DE"

    @Test
    fun aCryptLinkImportRemembersTheLinkItArrivedAs() = runTest {
        val source = FakeLocationsDataSource()
        val engine = MockEngine { respond(subscriptionBody) }
        val link = "olcrtc://crypt1/$cryptUrlBlob"

        val ok = experimentalRepository(
            dataSource = source,
            httpClient = HttpClient(engine),
            cryptCodec = CryptCodec(CryptCodec.decodeMaster(cryptKeyB64)!!)
        ).importText(link)

        assertTrue(ok)
        val entry = source.stored!!.locations.single()
        assertEquals(cryptUrl, entry.subscriptionUrl)
        assertEquals(link, entry.subscriptionOriginLink)
    }

    @Test
    fun aHappLinkImportRemembersThePastedLinkNotTheResolvedOne() = runTest {
        // What the user can paste again is the happ link; the crypt1 link the
        // coordinator handed back is an implementation detail of the resolver.
        val source = FakeLocationsDataSource()
        val engine = MockEngine { respond(subscriptionBody) }
        val resolver = FakeResolver(PartnerLinkResult.Resolved("olcrtc://crypt1/$cryptUrlBlob"))

        val ok = experimentalRepository(
            dataSource = source,
            httpClient = HttpClient(engine),
            cryptCodec = CryptCodec(CryptCodec.decodeMaster(cryptKeyB64)!!),
            partnerLinkResolver = resolver
        ).importText(happLink)

        assertTrue(ok)
        assertEquals(happLink, source.stored!!.locations.single().subscriptionOriginLink)
    }

    @Test
    fun aPlainSubscriptionHasNoOriginLink() = runTest {
        val source = FakeLocationsDataSource()
        val engine = MockEngine { respond(subscriptionBody) }

        val ok = experimentalRepository(
            dataSource = source,
            httpClient = HttpClient(engine)
        ).importText("https://example.test/sub/plain")

        assertTrue(ok)
        assertNull(source.stored!!.locations.single().subscriptionOriginLink)
    }

    @Test
    fun refreshLeavesTheChosenServerChosen() = runTest {
        // Reported from a phone: refreshing a server list jumped the connection to
        // whatever was first in it. Refresh must update the list and nothing else —
        // it is not a command to change servers, still less to re-dial one.
        val body = "olcrtc://telemost?vp8channel@11111#deadbeefdeadbeef\$DE\n" +
            "olcrtc://telemost?vp8channel@22222#deadbeefdeadbeef\$NL"
        val url = "https://example.test/sub/two"
        val source = FakeLocationsDataSource()
        val repo = experimentalRepository(
            dataSource = source,
            httpClient = HttpClient(MockEngine { respond(body) })
        )

        repo.importText(url)
        val entries = source.stored!!.locations
        assertEquals(2, entries.size, "the fixture must offer a choice to keep")
        val chosen = entries.last().storageId
        repo.setActiveLocationId(chosen)

        repo.refreshSubscription(url)

        assertEquals(
            chosen,
            source.stored!!.activeLocationId,
            "the selection survived the refresh and must not be reassigned"
        )
    }

    @Test
    fun refreshMovesTheSelectionOnlyWhenItsServerIsGone() = runTest {
        // The behaviour the broken condition was reaching for: a chosen server that
        // the provider dropped has to land somewhere, and the first entry is the
        // least surprising place.
        val url = "https://example.test/sub/two"
        val source = FakeLocationsDataSource()
        val bodies = listOf(
            "olcrtc://telemost?vp8channel@11111#deadbeefdeadbeef\$DE\n" +
                "olcrtc://telemost?vp8channel@22222#deadbeefdeadbeef\$NL",
            "olcrtc://telemost?vp8channel@11111#deadbeefdeadbeef\$DE"
        )
        var call = 0
        val repo = experimentalRepository(
            dataSource = source,
            httpClient = HttpClient(MockEngine { respond(bodies[minOf(call++, 1)]) })
        )

        repo.importText(url)
        val gone = source.stored!!.locations.last().storageId
        repo.setActiveLocationId(gone)

        repo.refreshSubscription(url)

        val remaining = source.stored!!.locations
        assertEquals(1, remaining.size)
        assertEquals(remaining.single().storageId, source.stored!!.activeLocationId)
    }

    @Test
    fun refreshKeepsTheEncryptedOrigin() = runTest {
        // A refresh re-imports from the stored plaintext URL and never sees the
        // encrypted link, so without an explicit carry-over the first auto-refresh
        // would quietly declassify the subscription.
        val link = "olcrtc://crypt1/$cryptUrlBlob"
        val stored = LocationBundleV4(
            activeLocationId = "alpha",
            locations = listOf(
                LocationEntry.from(
                    "alpha",
                    LocationConfig("Alpha", "12345", "deadbeefdeadbeef", LocationConfig.PROVIDER_TELEMOST),
                    subscriptionUrl = cryptUrl,
                    subscriptionOriginLink = link
                )
            )
        )
        val source = FakeLocationsDataSource(stored = stored)
        val engine = MockEngine { respond(subscriptionBody) }

        experimentalRepository(
            dataSource = source,
            httpClient = HttpClient(engine)
        ).refreshSubscription(cryptUrl)

        assertEquals(link, source.stored!!.locations.single().subscriptionOriginLink)
    }

    @Test
    fun sharingAnEncryptedSubscriptionHandsBackTheEncryptedLink() {
        val encrypted = LocationEntry.from(
            "alpha",
            LocationConfig("Alpha", "room-a", "a".repeat(64), LocationConfig.PROVIDER_WB_STREAM),
            subscriptionUrl = "https://proofkit.org/sub/TESTTOKEN/olcrtc?crypt=1",
            subscriptionOriginLink = "olcrtc://crypt1/blob"
        )
        val plain = LocationEntry.from(
            "beta",
            LocationConfig("Beta", "room-b", "b".repeat(64), LocationConfig.PROVIDER_WB_STREAM),
            subscriptionUrl = "https://example.test/sub"
        )

        val items = ConfigShareService.subscriptionShareItems(listOf(encrypted, plain))

        assertEquals("olcrtc://crypt1/blob", items.single { it.originLink != null }.shareText)
        assertEquals("https://example.test/sub", items.single { it.originLink == null }.shareText)
    }

    @Test
    fun theOriginLinkSurvivesASaveRoundTrip() {
        // normalized() rebuilds the entry field by field and runs on every save, so
        // a field it forgets is a field that never persists.
        val entry = LocationEntry.from(
            "alpha",
            LocationConfig("Alpha", "12345", "deadbeefdeadbeef", LocationConfig.PROVIDER_TELEMOST),
            subscriptionUrl = "https://example.test/sub",
            subscriptionOriginLink = "olcrtc://crypt1/blob"
        )
        assertEquals("olcrtc://crypt1/blob", entry.normalized().subscriptionOriginLink)
    }

    @Test
    fun importsTheRoomsHeaderAndKeepsItInTheStoredEntry() = runTest {
        val source = FakeLocationsDataSource()
        val repo = experimentalRepository(source)
        val key = "a".repeat(64)
        val input = """
            olcrtc://telemost?vp8channel@11115586048655#$key${'$'}Israel
            ##name: IL-1
            ##rooms: 81055221156696, 52664279650262 11115586048655
        """.trimIndent()

        assertTrue(repo.importText(input))

        val entry = repo.getAllLocations().single()
        assertEquals(listOf("81055221156696", "52664279650262"), entry.location.failoverRoomIds)
        assertEquals(
            listOf("11115586048655", "81055221156696", "52664279650262"),
            entry.location.failoverRooms()
        )
        // The stored shape carries them too. A list that lived only in the
        // parse was dropped on save, and the one room the client then knew was
        // the one the server was about to retire.
        assertEquals(
            listOf("81055221156696", "52664279650262"),
            source.stored?.locations?.single()?.failoverRooms
        )
    }

    @Test
    fun refreshReplacesTheFailoverRoomsWithWhatTheServerAdvertisesNow() = runTest {
        val key = "c".repeat(64)
        var body = "olcrtc://telemost?vp8channel@R1#$key${'$'}Room\n##rooms: R2"
        val source = FakeLocationsDataSource()
        val repo = experimentalRepository(source, HttpClient(MockEngine { respond(body) }))
        assertTrue(repo.importText("https://example.test/sub"))
        assertEquals(listOf("R1", "R2"), repo.getActiveLocation()?.location?.failoverRooms())

        // The server retired R1 and now serves R2 with R3 on standby.
        body = "olcrtc://telemost?vp8channel@R2#$key${'$'}Room\n##rooms: R3"
        repo.refreshSubscriptions()

        assertEquals(listOf("R2", "R3"), repo.getActiveLocation()?.location?.failoverRooms())
    }

    // A partner's list names every carrier of one origin alike ("DE · VP8"). The
    // first refresh after WB and SaluteJazz joined the Telemost line kept Telemost
    // under its old id and named the WB line after it, "imported_de_vp8" again:
    // normalized() keeps one entry per id, so WB vanished and SaluteJazz stayed.
    @Test
    fun refreshKeepsEveryCarrierOfAnOriginEvenWhenTheirNamesMatch() = runTest {
        val key = "c".repeat(64)
        val telemost = "olcrtc://telemost?vp8channel@T1#$key${'$'}DE · VP8"
        var body = telemost
        val source = FakeLocationsDataSource()
        val repo = experimentalRepository(source, HttpClient(MockEngine { respond(body) }))
        assertTrue(repo.importText("https://example.test/sub"))
        assertEquals(1, source.stored?.locations?.size)

        body = listOf(
            telemost,
            "olcrtc://wbstream?vp8channel@W1#$key${'$'}DE · VP8",
            "olcrtc://salutejazz?datachannel@code:pass#$key${'$'}DE · VP8",
        ).joinToString("\n")
        repo.refreshSubscriptions()
        repo.refreshSubscriptions()

        val stored = source.stored?.locations.orEmpty()
        assertEquals(
            listOf("T1", "W1", "code:pass"),
            stored.map { it.location.id },
            "every carrier's line survives the refresh, each once"
        )
        assertEquals(stored.size, stored.map { it.storageId }.toSet().size, "no two lines share an id")
    }

    private class FakeLocationsDataSource(
        var stored: LocationBundleV4? = null,
        private val legacy: List<Pair<String, String>> = emptyList(),
        private val legacyActive: String? = null
    ) : LocationsDataSource {

        override suspend fun loadLocationBundle(): LocationBundleV4? = stored

        override suspend fun saveLocationBundle(bundle: LocationBundleV4) {
            stored = bundle
        }

        override suspend fun loadLegacyLocations(): List<Pair<String, String>> = legacy

        override suspend fun loadLegacyActiveLocationId(): String? = legacyActive
    }

    private class StaticIdentityProvider(
        private val value: String
    ) : DeviceIdentityProvider {
        override suspend fun hwid(): String = value
    }
}
