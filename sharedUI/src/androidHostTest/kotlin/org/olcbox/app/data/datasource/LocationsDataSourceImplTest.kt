package org.olcbox.app.data.datasource

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.olcbox.app.data.LOCATIONS_BUNDLE_FILE_NAME
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
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
        val interrupted = AtomicFile(file).startWrite()
        interrupted.write("{\"locations\":[".toByteArray())
        interrupted.close() // Simulates process loss before finishWrite/failWrite.

        val recovered = assertNotNull(LocationsDataSourceImpl(context).loadLocationBundle())
        assertEquals(listOf("kept"), recovered.locations.map { it.storageId })
        assertEquals(previous.normalized(), recovered)
    }
}
