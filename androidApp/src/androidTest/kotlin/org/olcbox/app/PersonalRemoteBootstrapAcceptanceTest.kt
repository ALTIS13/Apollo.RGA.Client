@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE") // Test-only access; production policy gates remain unchanged.

package org.olcbox.app

import android.content.Context
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyPair
import java.security.KeyStore
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.olcbox.app.data.datasource.LocationsDataSourceImpl
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.net.AndroidKeystoreInstallationIdentity
import org.olcbox.app.net.AndroidKeystoreP256MaterialStore
import org.olcbox.app.net.AndroidRuntimeLocationPolicy
import org.olcbox.app.net.AndroidVerifiedPersonalBootstrapProducer
import org.olcbox.app.net.InstallationKeyMaterialStore
import org.olcbox.app.net.PersonalBootstrapEndpointPolicy
import org.olcbox.app.net.RuntimeLocationPolicy
import org.olcbox.app.net.SubscriptionTunnelAccess
import org.olcbox.app.vpn.AndroidConnectionMode
import org.olcbox.app.vpn.service.selectPersonalSubscriptionUrl

/** Physical Wi-Fi acceptance only: no imports, identity creation, cache writes or native admission. */
@RunWith(AndroidJUnit4::class)
class PersonalRemoteBootstrapAcceptanceTest {
    @Test(timeout = 90_000L)
    fun storedSubscriptionAndExistingKeystoreCompletePublicBootstrap() {
        var stage = "personal build policy"
        try {
            runBlocking {
                val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                assertTrue("Personal package required", context.packageName == PERSONAL_PACKAGE)
                val policy = AndroidRuntimeLocationPolicy.forApp(context)
                assertTrue("Personal debug policy required", policy == RuntimeLocationPolicy.ExperimentalDebug)
                assertTrue("Exact public origin required", GeneratedAppInfo.PERSONAL_BRIDGE_REMOTE_ORIGIN == REMOTE_ORIGIN)
                assertTrue("USB pin must be absent", GeneratedAppInfo.PERSONAL_BRIDGE_CERT_SHA256.isEmpty())
                val endpoint = PersonalBootstrapEndpointPolicy.select(context.packageName, true,
                    GeneratedAppInfo.PERSONAL_BRIDGE_REMOTE_ORIGIN, GeneratedAppInfo.PERSONAL_BRIDGE_CERT_SHA256)
                assertTrue("Remote endpoint policy rejected", endpoint != null && endpoint.usbCertificatePin == null &&
                    endpoint.urlFor("/v1/personal/link/begin")?.toExternalForm() == "$REMOTE_ORIGIN/v1/personal/link/begin" &&
                    endpoint.urlFor("/v1/personal/bootstrap")?.toExternalForm() == "$REMOTE_ORIGIN/v1/personal/bootstrap")

                stage = "existing subscription admission"
                val repository = LocationsRepositoryImpl(LocationsDataSourceImpl(context), runtimePolicy = policy)
                val bundle = repository.getBundle()
                val selectedId = bundle.activeLocationId
                val subscription = selectPersonalSubscriptionUrl(context.packageName, policy,
                    AndroidConnectionMode.Tun, bundle, selectedId)
                assertTrue("Stored selected subscription required", subscription != null)
                assertTrue("Stored subscription admission denied", SubscriptionTunnelAccess.denial(
                    bundle, selectedId, System.currentTimeMillis(), policy) == null)

                stage = "existing installation preflight"
                val preferences = context.getSharedPreferences(IDENTITY_PREFERENCES, Context.MODE_PRIVATE)
                val savedId = preferences.getString("installation_id", null)
                val savedFingerprint = preferences.getString("public_key_sha256", null)
                assertTrue("Existing installation required", savedId != null && savedId.matches(Regex("[A-Za-z0-9_-]{16,128}")))
                val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                assertTrue("Existing Keystore key required", store.containsAlias(KEY_ALIAS))
                val publicDer = store.getCertificate(KEY_ALIAS)?.publicKey?.encoded
                assertTrue("Existing public key required", publicDer != null)
                val fingerprint = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(publicDer!!),
                    Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                assertTrue("Existing identity binding mismatch", savedFingerprint == fingerprint)

                // Even if state drifts after preflight, the production loader cannot create/rotate a key.
                val material = AndroidKeystoreP256MaterialStore()
                val readOnlyMaterial = object : InstallationKeyMaterialStore {
                    override fun read(): KeyPair? = material.read()
                    override fun create(): KeyPair = error("Existing identity required")
                    override fun delete(): Unit = error("Identity rotation forbidden")
                }
                val identity = AndroidKeystoreInstallationIdentity(preferences, readOnlyMaterial).loadOrCreate()
                assertTrue("Identity changed during preflight", identity.installationId == savedId &&
                    identity.publicKeyDer.contentEquals(publicDer))

                stage = "public challenge and PoP completion"
                val producer = AndroidVerifiedPersonalBootstrapProducer.forPersonalTest(context)
                assertTrue("Production remote producer unavailable", producer != null)
                val bootstrap = withTimeout(45_000L) {
                    producer!!.completeLinkAndPrefetch(subscription!!, identity)
                }
                assertTrue("Public challenge/PoP completion rejected", bootstrap != null)
                val now = System.currentTimeMillis()
                assertTrue("Bootstrap unusable or expiry not finite", bootstrap!!.isUsableAt(now) &&
                    bootstrap.expiresAtEpochMs in (now + 1)..MAX_FINITE_UTC_EPOCH_MS)

                stage = "identity and selection preservation"
                assertTrue("Installation preferences changed", preferences.getString("installation_id", null) == savedId &&
                    preferences.getString("public_key_sha256", null) == savedFingerprint)
                val remainingDer = store.getCertificate(KEY_ALIAS)?.publicKey?.encoded
                assertTrue("Keystore identity changed", remainingDer != null && remainingDer.contentEquals(publicDer))
                val after = repository.getBundle()
                assertTrue("Selected subscription changed", after.activeLocationId == selectedId &&
                    selectPersonalSubscriptionUrl(context.packageName, policy, AndroidConnectionMode.Tun,
                        after, selectedId) == subscription)
            }
        } catch (assertion: AssertionError) {
            throw assertion // All assertions use fixed text and boolean values, never private values.
        } catch (_: Exception) {
            fail("Remote bootstrap acceptance failed at $stage") // Deliberately omit exception details/cause.
        }
    }

    private companion object {
        const val PERSONAL_PACKAGE = "tech.gatealtas.rga.personal"
        const val REMOTE_ORIGIN = "https://rga.apollot.ru"
        const val IDENTITY_PREFERENCES = "olcrtc_personal_join_installation_v1"
        const val KEY_ALIAS = "org.olcbox.app.olcrtc_personal_join_p256_v1"
        const val MAX_FINITE_UTC_EPOCH_MS = 253_402_300_799_999L // Last millisecond of UTC year 9999, not Long.MAX_VALUE.
    }
}
