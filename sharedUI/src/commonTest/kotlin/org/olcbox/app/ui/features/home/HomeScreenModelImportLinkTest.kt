package org.olcbox.app.ui.features.home

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import org.olcbox.app.data.datasource.LocationsDataSource
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.exporter.LogExporter
import org.olcbox.app.data.importer.ConfigImporter
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.net.ImportLink
import org.olcbox.app.vpn.TrafficCounters
import org.olcbox.app.vpn.VpnManager
import org.olcbox.app.vpn.VpnStatus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class HomeScreenModelImportLinkTest {
    private val source = MemoryLocationsDataSource()
    private val repository = LocationsRepositoryImpl(source)
    private val models = mutableListOf<HomeScreenViewModel>()

    @BeforeTest fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun restore() {
        // IO continuations must finish before resetting the test Main dispatcher;
        // otherwise a previous view model can crash the following test class.
        runBlocking { models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
        Dispatchers.resetMain()
    }

    private fun viewModel(
        vpn: IdleVpnManager = IdleVpnManager(),
        importer: ConfigImporter = NoConfigImporter,
        repo: LocationsRepositoryImpl = repository
    ) = HomeScreenViewModel(
        vpnManager = vpn,
        locationsRepository = repo,
        configImporter = importer,
        logExporter = NoLogExporter
    ).also { models += it }

    // The import hops to Dispatchers.IO, a real thread; virtual time would
    // race ahead of it, so the wait is measured in real seconds.
    private suspend fun awaitForReal(outcome: CompletableDeferred<String>): String =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(10_000) { outcome.await() } }

    private val realityLink = "vless://11111111-1111-1111-1111-111111111111@1.2.3.4:443" +
        "?security=reality&encryption=none&pbk=PUBKEY&sid=ab12&fp=chrome&sni=www.example.com&flow=xtls-rprx-vision&type=tcp#DE"

    @Test fun viewModelDoesNotTreatLegacyDisclosureAsApolloAcknowledgement() = runTest {
        source.stored = LocationBundleV4(vpnDisclosureAcceptedAt = 1_000L)
        val vm = viewModel()
        try {
            assertEquals(false, withContext(Dispatchers.Default) { withTimeout(10_000) {
                vm.apolloVpnDisclosureAccepted.first { it != null }
            } })
            assertEquals(true, vm.acceptApolloVpnDisclosure())
            assertEquals(true, repository.isApolloVpnDisclosureAccepted())
            assertEquals(true, vm.apolloVpnDisclosureAccepted.value)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun failedApolloAcknowledgementNeverEnablesAStart() = runTest {
        source.stored = LocationBundleV4(vpnDisclosureAcceptedAt = 1_000L)
        val vm = viewModel()
        try {
            withContext(Dispatchers.Default) { withTimeout(10_000) {
                vm.apolloVpnDisclosureAccepted.first { it != null }
            } }
            source.failSave = true
            assertEquals(false, vm.acceptApolloVpnDisclosure())
            assertEquals(false, vm.apolloVpnDisclosureAccepted.value)
            assertEquals(false, repository.isApolloVpnDisclosureAccepted())
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun selectedOnlyToggleBypassesPersistedLowestChoice() = runTest {
        source.stored = LocationBundleV4(
            activeLocationId = "one",
            locations = listOf(
                org.olcbox.app.data.model.LocationEntry(
                    storageId = "one", subscriptionUrl = "https://example.com/sub",
                    kind = org.olcbox.app.net.LocationKind.Vless, rawLink = realityLink
                ),
                org.olcbox.app.data.model.LocationEntry(
                    storageId = "two", subscriptionUrl = "https://example.com/sub",
                    kind = org.olcbox.app.net.LocationKind.Vless, rawLink = realityLink
                )
            ),
            settings = org.olcbox.app.data.model.SubscriptionSettings(autoUpdate = false, autoSelectLowest = true)
        )
        val vpn = IdleVpnManager()
        vpn.probe = { error("Selected-only connect must not probe another host") }
        val vm = viewModel(vpn)
        try {
            withContext(Dispatchers.Default) { withTimeout(10_000) { vm.subscriptionSettingsLoaded.first { it } } }
            vm.requestSelectedOnlyForNextToggle()
            vm.ToggleVpn()
            withContext(Dispatchers.Default) { withTimeout(10_000) {
                while (vpn.starts == 0) kotlinx.coroutines.delay(10)
            } }
            assertEquals(1, vpn.starts)
            assertEquals("one", repository.getActiveLocation()?.storageId)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun stopDuringLowestRankingCancelsThePendingStart() = runTest {
        source.stored = LocationBundleV4(
            activeLocationId = "one",
            locations = listOf(org.olcbox.app.data.model.LocationEntry(
                storageId = "one", subscriptionUrl = "https://example.com/sub",
                kind = org.olcbox.app.net.LocationKind.Vless, rawLink = realityLink
            )),
            settings = org.olcbox.app.data.model.SubscriptionSettings(autoUpdate = false, autoSelectLowest = true)
        )
        val vpn = IdleVpnManager()
        val entered = CompletableDeferred<Unit>()
        vpn.probe = { entered.complete(Unit); awaitCancellation() }
        val vm = viewModel(vpn)
        try {
            withContext(Dispatchers.Default) { withTimeout(10_000) { vm.subscriptionSettingsLoaded.first { it } } }
            vm.ToggleVpn()
            entered.await()
            assertTrue(vm.state.value.isVpnLoading)
            vm.ToggleVpn()
            assertEquals(0, vpn.starts)
            assertEquals(1, vpn.stops)
            assertEquals(false, vm.state.value.isVpnLoading)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun migrationKeepsStartupMeasurementAndMarksItsLateAnswerHistorical() = runTest {
        repository.importText(realityLink)
        val client = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine {
            error("Address measurements must not query room occupancy")
        })
        val vm = org.olcbox.app.ui.features.locations.LocationViewModel(
            repository, org.olcbox.app.net.OlcrtcStatusClient(client)
        )
        try {
            val answer = CompletableDeferred<Long>()
            val finished = CompletableDeferred<Unit>()
            vm.refreshPings(performPing = { answer.await() }, canPing = { true },
                onComplete = { _, _ -> finished.complete(Unit) })
            vm.markPingsStale()
            answer.complete(42)
            finished.await()
            val id = vm.locations.single().storageId
            assertEquals(42, (vm.pingsState as org.olcbox.app.ui.features.locations.PingsState.Success).pings[id])
            assertTrue(id in vm.stalePingIds)
            val refreshed = CompletableDeferred<Unit>()
            vm.refreshPings(performPing = { 17 }, canPing = { true },
                onComplete = { _, _ -> refreshed.complete(Unit) })
            refreshed.await()
            assertTrue(vm.stalePingIds.isEmpty())
            assertEquals(17, (vm.pingsState as org.olcbox.app.ui.features.locations.PingsState.Success).pings[id])
        } finally {
            vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            client.close()
        }
    }

    @Test fun cancellingAListMeasurementStillCompletesItsRequest() = runTest {
        repository.importText(realityLink)
        val client = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine {
            error("Address measurements must not query room occupancy")
        })
        val vm = org.olcbox.app.ui.features.locations.LocationViewModel(
            repository, org.olcbox.app.net.OlcrtcStatusClient(client)
        )
        try {
            val entered = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Pair<Int, Int>>()
            vm.refreshPings(
                performPing = { entered.complete(Unit); awaitCancellation() },
                canPing = { true },
                onComplete = { online, total -> finished.complete(online to total) }
            )
            entered.await()
            vm.cancelPings()
            assertEquals(0 to 1, withTimeout(5_000) { finished.await() })
        } finally {
            vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            client.close()
        }
    }

    @Test fun preconnectMeasurementCompletesAtItsCallerBudget() = runTest {
        repository.importText(realityLink)
        val client = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine {
            error("Address measurements must not query room occupancy")
        })
        val vm = org.olcbox.app.ui.features.locations.LocationViewModel(
            repository, org.olcbox.app.net.OlcrtcStatusClient(client)
        )
        try {
            val finished = CompletableDeferred<Pair<Int, Int>>()
            vm.refreshPings(
                performPing = { awaitCancellation() },
                canPing = { true },
                overallDeadlineMs = 50,
                onComplete = { online, total -> finished.complete(online to total) }
            )
            assertEquals(0 to 1, withTimeout(5_000) { finished.await() })
        } finally {
            vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            client.close()
        }
    }

    @Test fun migrationDiscardsAnInflightSampleEvenWhenTheConnectionClockIsUnchanged() = runTest {
        val vpn = IdleVpnManager()
        val vm = viewModel(vpn)
        try {
            withContext(Dispatchers.Default) { withTimeout(10_000) { vm.subscriptionSettingsLoaded.first { it } } }
            vpn.connectedSince.value = 123L
            vpn.status.value = VpnStatus.Connected
            val entered = CompletableDeferred<Unit>()
            val answer = CompletableDeferred<Long?>()
            vpn.measure = { entered.complete(Unit); answer.await() }
            val request = async { vm.measureActiveChannel() }
            entered.await()
            assertNull(vm.channelLatency.value)
            vpn.status.value = VpnStatus.Reconnecting
            vpn.status.value = VpnStatus.Connected
            answer.complete(42L)
            request.await()
            assertNull(vm.channelLatency.value)
            vpn.measure = { 17L }
            vm.measureActiveChannel()
            assertEquals("HTTP 17ms", vm.channelLatency.value?.label())
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun failureOnlyAppearsAfterTheFirstCompletedSample() = runTest {
        val vpn = IdleVpnManager()
        val vm = viewModel(vpn)
        try {
            vpn.status.value = VpnStatus.Connected
            assertNull(vm.channelLatency.value)
            vm.measureActiveChannel()
            assertEquals("HTTP —", vm.channelLatency.value?.label())
            vpn.status.value = VpnStatus.Disconnected
            assertNull(vm.channelLatency.value)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun anImportLinkGoesThroughTheSameImportAsAPaste() = runTest {
        val outcome = CompletableDeferred<String>()
        viewModel().onImportLink(
            uri = ImportLink.schemeLink(realityLink),
            onComplete = { outcome.complete("ok") },
            onError = { outcome.complete("error: $it") }
        )
        assertEquals("ok", awaitForReal(outcome))
        val names = repository.getAllLocations().map { it.name }
        assertEquals(listOf("DE"), names)
    }

    @Test fun somethingThatIsNotAnImportLinkIsRefusedBeforeAnyImport() = runTest {
        val outcome = CompletableDeferred<String>()
        viewModel().onImportLink(
            uri = "https://example.org/add#$realityLink",
            onComplete = { outcome.complete("ok") },
            onError = { outcome.complete("error: $it") }
        )
        assertEquals("error: Not an Apollo.RGA import link", awaitForReal(outcome))
        assertTrue(repository.getAllLocations().isEmpty())
    }

    @Test fun importFailureNeverReturnsExceptionUrlToCallback() = runTest {
        val secretMarker = "synthetic-secret"
        source.failSaveMessage = "failed for https://subscription.example.test/sub?token=$secretMarker"
        val outcome = CompletableDeferred<String>()
        val vm = viewModel()
        vm.onImportFullConfig(realityLink, onError = { outcome.complete(it) })

        val callback = awaitForReal(outcome)
        assertFalse(callback.contains(secretMarker))
        assertEquals("Import failed", callback)
        assertFalse(repository.isOnboardingSeen())
    }

    @Test fun importFailureNeverStoresExceptionUrlInStartBlockedReason() = runTest {
        val secretMarker = "synthetic-secret"
        source.failSaveMessage = "failed for https://subscription.example.test/sub?token=$secretMarker"
        val outcome = CompletableDeferred<String>()
        val vm = viewModel()
        vm.onImportFullConfig(realityLink, onError = { outcome.complete(it) })

        awaitForReal(outcome)
        val reason = vm.state.value.startBlockedReason.orEmpty()
        assertFalse(reason.contains(secretMarker))
        assertEquals("Import failed", reason)
    }

    @Test fun fileReadExceptionReturnsSafeErrorInsteadOfSilentlyEndingImport() = runTest {
        val marker = "synthetic-secret"
        val importer = object : ConfigImporter {
            override fun getFromClipboard(): String? = null
            override fun copyToClipboard(text: String) = Unit
            override suspend fun readTextFromSource(source: Any): String? =
                error("failed for token $marker")
        }
        val outcome = CompletableDeferred<String>()
        viewModel(importer = importer).onFileSelected(Any(), onError = { outcome.complete(it) })

        val message = awaitForReal(outcome)
        assertEquals("Could not read config file", message)
        assertFalse(message.contains(marker))
        assertTrue(repository.getAllLocations().isEmpty())
    }

    @Test fun manualEntryRejectsNonHttpsBeforeAnyFetch() = runTest {
        val client = HttpClient(MockEngine { error("Manual non-HTTPS entry must not fetch") })
        try {
            val outcome = CompletableDeferred<String>()
            val vm = viewModel(repo = LocationsRepositoryImpl(source, client))
            vm.onImportManualSubscriptionUrl(
                "http://example.test/sub",
                onComplete = { outcome.complete("imported") },
                onError = { outcome.complete(it) }
            )
            assertEquals("Enter a valid HTTPS subscription URL", awaitForReal(outcome))
            assertTrue(repository.getAllLocations().isEmpty())
            assertFalse(repository.isOnboardingSeen())
        } finally { client.close() }
    }

    @Test fun manualEntryImportsTheLiteralHttpsSubscriptionWithoutStartingVpn() = runTest {
        val synthetic = "https://example.test/sub?token=synthetic%26value"
        var fetched: String? = null
        val client = HttpClient(MockEngine { request ->
            fetched = request.url.toString()
            respond(realityLink)
        })
        val vpn = IdleVpnManager()
        try {
            val outcome = CompletableDeferred<String>()
            val repo = LocationsRepositoryImpl(source, client)
            val vm = viewModel(vpn = vpn, repo = repo)
            vm.onImportManualSubscriptionUrl(
                synthetic,
                onComplete = { outcome.complete("imported") },
                onError = { outcome.complete(it) }
            )
            assertEquals("imported", awaitForReal(outcome))
            assertEquals(synthetic, fetched)
            assertEquals(listOf("DE"), repo.getAllLocations().map { it.name })
            assertEquals(0, vpn.starts)
            // A successful one-tap import must leave the first-run screen now,
            // not only after the person presses its separate "Not now" action.
            assertEquals(true, vm.onboardingSeen.value)
            assertEquals(true, repo.isOnboardingSeen())
        } finally { client.close() }
    }

    @Test fun manualEntryRejectsUserInfoFragmentsAndControlsBeforeFetch() = runTest {
        val client = HttpClient(MockEngine { error("Malformed manual entry must not fetch") })
        try {
            val vm = viewModel(repo = LocationsRepositoryImpl(source, client))
            val invalid = listOf(
                "https://user@example.test/sub",
                "https://example.test/sub#fragment",
                "https://example.test/sub\nnext"
            )
            for (candidate in invalid) {
                val outcome = CompletableDeferred<String>()
                vm.onImportManualSubscriptionUrl(
                    candidate,
                    onComplete = { outcome.complete("imported") },
                    onError = { outcome.complete(it) }
                )
                assertEquals("Enter a valid HTTPS subscription URL", awaitForReal(outcome))
            }
            assertTrue(repository.getAllLocations().isEmpty())
        } finally { client.close() }
    }
}

private class MemoryLocationsDataSource(var stored: LocationBundleV4? = null) : LocationsDataSource {
    var failSave = false
    var failSaveMessage: String? = null
    override suspend fun loadLocationBundle(): LocationBundleV4? = stored
    override suspend fun saveLocationBundle(bundle: LocationBundleV4) {
        failSaveMessage?.let { throw IllegalStateException(it) }
        if (failSave) error("synthetic persistence failure")
        stored = bundle
    }
    override suspend fun loadLegacyLocations(): List<Pair<String, String>> = emptyList()
    override suspend fun loadLegacyActiveLocationId(): String? = null
}

private class IdleVpnManager : VpnManager {
    override val logs: StateFlow<List<String>> = MutableStateFlow(emptyList())
    override val status = MutableStateFlow<VpnStatus>(VpnStatus.Disconnected)
    override val isConnected: StateFlow<Boolean> = MutableStateFlow(false)
    override val connectedSince = MutableStateFlow<Long?>(null)
    var measure: suspend () -> Long? = { null }
    var probe: suspend () -> Long? = { null }
    var starts = 0
    var stops = 0
    override suspend fun measureCurrentChannel(): Long? = measure()
    override val traffic: StateFlow<TrafficCounters?> = MutableStateFlow(null)
    override fun needsPermission(): Boolean = false
    override fun startVpn() { starts++ }
    override fun stopVpn() { stops++ }
    override fun canPing(locationConfig: LocationConfig) = true
    override suspend fun ping(locationConfig: LocationConfig): Long? = probe()
    override suspend fun checkConnection(locationConfig: LocationConfig): Long? = null
}

private object NoConfigImporter : ConfigImporter {
    override fun getFromClipboard(): String? = null
    override fun copyToClipboard(text: String) {}
    override suspend fun readTextFromSource(source: Any): String? = null
}

private object NoLogExporter : LogExporter {
    override suspend fun writeLogs(target: Any, content: String): Result<String> = Result.success("")
}
