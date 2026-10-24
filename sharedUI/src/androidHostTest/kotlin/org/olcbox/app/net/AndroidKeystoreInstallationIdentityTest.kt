package org.olcbox.app.net

import android.content.Context
import android.content.SharedPreferences
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AndroidKeystoreInstallationIdentityTest {
    @Test fun identityPersistsWithSameKeyAndSignsWithMatchingPublicKey() {
        val store = InMemoryKeyMaterialStore()
        val preferences = preferences()
        val identity = provider(preferences, store)
        val first = identity.loadOrCreate()
        val second = provider(preferences, store).loadOrCreate()
        assertEquals(first.installationId, second.installationId)
        assertArrayEquals(first.publicKeyDer, second.publicKeyDer)
        assertEquals(43, first.installationId.length)
        assertFalse(first.installationId.contains('='))
        assertEquals(1, store.created)
        val transcript = "bounded transcript".toByteArray()
        assertTrue(Signature.getInstance("SHA256withECDSA").apply {
            initVerify(canonicalP256PublicKey(first.publicKeyDer))
            update(transcript)
        }.verify(first.sign(transcript)))
    }

    @Test fun restoredPreferencesWithoutKeystoreKeyRotateInstallationId() {
        val store = InMemoryKeyMaterialStore()
        val identity = provider(preferences(), store)
        val first = identity.loadOrCreate()
        store.pair = null
        val replacement = identity.loadOrCreate()
        assertFalse(first.installationId == replacement.installationId)
        assertFalse(first.publicKeyDer.contentEquals(replacement.publicKeyDer))
        assertEquals(2, store.created)
    }

    @Test fun unexpectedKeyReplacementCannotReuseBoundInstallationId() {
        val store = InMemoryKeyMaterialStore()
        val identity = provider(preferences(), store)
        val first = identity.loadOrCreate()
        store.pair = newP256Pair()
        val replacement = identity.loadOrCreate()
        assertFalse(first.installationId == replacement.installationId)
        assertFalse(first.publicKeyDer.contentEquals(replacement.publicKeyDer))
    }

    private fun preferences(): SharedPreferences = RuntimeEnvironment.getApplication().getSharedPreferences(
            "personal_join_identity_test_${System.nanoTime()}", Context.MODE_PRIVATE
        )

    private fun provider(
        preferences: SharedPreferences,
        store: InMemoryKeyMaterialStore
    ): AndroidKeystoreInstallationIdentity {
        var seed = 0
        return AndroidKeystoreInstallationIdentity(preferences, store) { target ->
            target.fill((++seed).toByte())
        }
    }

    private class InMemoryKeyMaterialStore : InstallationKeyMaterialStore {
        var pair: KeyPair? = null
        var created = 0
        override fun read(): KeyPair? = pair
        override fun create(): KeyPair = newP256Pair().also { pair = it; created++ }
        override fun delete() { pair = null }
    }

    private companion object {
        fun newP256Pair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
    }
}
