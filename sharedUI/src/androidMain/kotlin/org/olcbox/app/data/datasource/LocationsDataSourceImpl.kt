package org.olcbox.app.data.datasource

import android.content.Context
import android.util.AtomicFile
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.olcbox.app.data.ACTIVE_LOCATION_CONFIG_FILE_NAME
import org.olcbox.app.data.LEGACY_LOCATIONS_BUNDLE_FILE_NAME
import org.olcbox.app.data.LOCATIONS_BUNDLE_FILE_NAME
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.vpn.data.KEY_IS_VPN_CONFIG_READY
import org.olcbox.app.vpn.data.KEY_VPN_CONFIG_PATH
import org.olcbox.app.vpn.data.vpnPrefDataStore
import java.io.File

private val KEY_LEGACY_SELECTED_LOCATION_ID = stringPreferencesKey("selected_hysteria_id")
private val KEY_DEVICE_IDENTITY = stringPreferencesKey("olcbox_device_identity")

class LocationsDataSourceImpl(
    private val context: Context
) : LocationsDataSource {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }

    override suspend fun loadLocationBundle(): LocationBundleV4? = withContext(Dispatchers.IO) {
        val primary = File(context.filesDir, LOCATIONS_BUNDLE_FILE_NAME)
        val file = primary.takeIf { it.exists() || File(it.path + ".bak").exists() }
            ?: File(context.filesDir, LEGACY_LOCATIONS_BUNDLE_FILE_NAME).takeIf { it.exists() }
            ?: return@withContext null
        val bundle = runCatching {
            val content = if (file == primary) AtomicFile(file).openRead().bufferedReader().use { it.readText() }
                else file.readText()
            json.decodeFromString(LocationBundleV4.serializer(), content).normalized()
        }.getOrNull()
        if (bundle != null) reconcileActiveLocationConfig(bundle)
        bundle
    }

    override suspend fun saveLocationBundle(bundle: LocationBundleV4): Unit = withContext(Dispatchers.IO) {
        val normalized = bundle.normalized()
        // The active file is derived from this master. Disable readiness before
        // either commit so a crash between the two cannot advertise stale config.
        context.vpnPrefDataStore.edit { it[KEY_IS_VPN_CONFIG_READY] = false }
        writeAtomic(
            File(context.filesDir, LOCATIONS_BUNDLE_FILE_NAME),
            json.encodeToString(LocationBundleV4.serializer(), normalized)
        )
        updateActiveLocationConfig(normalized)
    }

    override suspend fun loadLegacyLocations(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val settings = context.filesDir.listFiles { file ->
            file.name.startsWith("hysteria_settings_") && file.name.endsWith(".json")
        }.orEmpty().map { file ->
            val storageId = file.name.removePrefix("hysteria_settings_").removeSuffix(".json")
            storageId to file.readText()
        }

        val master = File(context.filesDir, "hysteria.yaml")
            .takeIf { it.exists() }
            ?.let { listOf("legacy_master" to it.readText()) }
            .orEmpty()

        settings + master
    }

    override suspend fun loadLegacyActiveLocationId(): String? {
        return context.vpnPrefDataStore.data.first()[KEY_LEGACY_SELECTED_LOCATION_ID]?.ifBlank { null }
    }

    override suspend fun loadDeviceIdentity(): String? {
        return context.vpnPrefDataStore.data.first()[KEY_DEVICE_IDENTITY]?.ifBlank { null }
    }

    override suspend fun saveDeviceIdentity(value: String) {
        context.vpnPrefDataStore.edit {
            it[KEY_DEVICE_IDENTITY] = value
        }
    }

    private suspend fun updateActiveLocationConfig(bundle: LocationBundleV4) {
        val active = bundle.locations.firstOrNull { it.storageId == bundle.activeLocationId }
        val file = File(context.filesDir, ACTIVE_LOCATION_CONFIG_FILE_NAME)

        if (active == null) {
            AtomicFile(file).delete()
            context.vpnPrefDataStore.edit {
                it[KEY_IS_VPN_CONFIG_READY] = false
                it.remove(KEY_VPN_CONFIG_PATH)
            }
            return
        }

        writeAtomic(file,
            json.encodeToString(LocationBundleV4.serializer(), bundle.copy(locations = listOf(active)))
        )
        context.vpnPrefDataStore.edit {
            it[KEY_IS_VPN_CONFIG_READY] = active.location.isComplete()
            it[KEY_VPN_CONFIG_PATH] = file.absolutePath
        }
    }

    private suspend fun reconcileActiveLocationConfig(bundle: LocationBundleV4) {
        val active = bundle.locations.firstOrNull { it.storageId == bundle.activeLocationId }
        val file = File(context.filesDir, ACTIVE_LOCATION_CONFIG_FILE_NAME)
        val prefs = context.vpnPrefDataStore.data.first()
        val expected = active?.let { bundle.copy(locations = listOf(it)).normalized() }
        val actual = runCatching {
            AtomicFile(file).openRead().bufferedReader().use { reader ->
                json.decodeFromString(LocationBundleV4.serializer(), reader.readText()).normalized()
            }
        }.getOrNull()
        val ready = active?.location?.isComplete() == true
        val pathMatches = if (active == null) {
            prefs[KEY_VPN_CONFIG_PATH] == null && !file.exists() && !File(file.path + ".bak").exists()
        } else {
            prefs[KEY_VPN_CONFIG_PATH] == file.absolutePath
        }
        if (actual == expected && prefs[KEY_IS_VPN_CONFIG_READY] == ready && pathMatches
        ) return

        context.vpnPrefDataStore.edit { it[KEY_IS_VPN_CONFIG_READY] = false }
        updateActiveLocationConfig(bundle)
    }

    private fun writeAtomic(file: File, content: String) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(content.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (failure: Throwable) {
            atomic.failWrite(stream)
            throw failure
        }
    }
}
