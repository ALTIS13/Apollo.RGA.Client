package org.olcbox.app.data.datasource

import android.content.Context
import android.util.AtomicFile
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.olcbox.app.data.LOCATIONS_BUNDLE_FILE_NAME
import org.olcbox.app.data.ACTIVE_LOCATION_CONFIG_FILE_NAME
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.vpn.data.KEY_IS_VPN_CONFIG_READY
import org.olcbox.app.vpn.data.KEY_VPN_CONFIG_PATH
import org.olcbox.app.vpn.data.vpnPrefDataStore
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
class LocationsDataSourceImplTest {
    @Test
    fun interruptedBundleWriteRestoresPreviousCompleteVersion() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val source = LocationsDataSourceImpl(context)
        val previous = LocationBundleV4(
            locations = listOf(LocationEntry.from("kept", LocationConfig("Kept", "room", "k".repeat(64))))
        )
        source.saveLocationBundle(previous)

        val file = File(context.filesDir, LOCATIONS_BUNDLE_FILE_NAME)
        context.vpnPrefDataStore.edit { it[KEY_IS_VPN_CONFIG_READY] = false }
        val interrupted = AtomicFile(file).startWrite()
        interrupted.write("{\"locations\":[".toByteArray())
        interrupted.close() // Simulates process loss before finishWrite/failWrite.

        val recovered = assertNotNull(LocationsDataSourceImpl(context).loadLocationBundle())
        assertEquals(listOf("kept"), recovered.locations.map { it.storageId })
        assertEquals(previous.normalized(), recovered)
        assertDerivedReady(context, "kept")
    }

    @Test
    fun committedMasterRebuildsStaleDerivedActiveAfterCrash() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val source = LocationsDataSourceImpl(context)
        val previous = LocationBundleV4(locations = listOf(
            LocationEntry.from("old", LocationConfig("Old", "room-old", "a".repeat(64)))
        ))
        source.saveLocationBundle(previous)
        val next = LocationBundleV4(locations = listOf(
            LocationEntry.from("new", LocationConfig("New", "room-new", "b".repeat(64)))
        )).normalized()

        context.vpnPrefDataStore.edit { it[KEY_IS_VPN_CONFIG_READY] = false }
        val master = AtomicFile(File(context.filesDir, LOCATIONS_BUNDLE_FILE_NAME))
        val stream = master.startWrite()
        stream.write(Json.encodeToString(LocationBundleV4.serializer(), next).toByteArray())
        master.finishWrite(stream) // Process stops before writing the derived active file.

        assertEquals(next, LocationsDataSourceImpl(context).loadLocationBundle())
        assertDerivedReady(context, "new")
    }

    private suspend fun assertDerivedReady(context: Context, expectedId: String) {
        val prefs = context.vpnPrefDataStore.data.first()
        assertEquals(true, prefs[KEY_IS_VPN_CONFIG_READY])
        val path = assertNotNull(prefs[KEY_VPN_CONFIG_PATH])
        val file = File(context.filesDir, ACTIVE_LOCATION_CONFIG_FILE_NAME)
        assertEquals(file.absolutePath, path)
        val derived = Json.decodeFromString(
            LocationBundleV4.serializer(), AtomicFile(file).openRead().bufferedReader().use { it.readText() }
        )
        assertEquals(expectedId, derived.activeLocationId)
        assertEquals(listOf(expectedId), derived.locations.map { it.storageId })
    }
}
