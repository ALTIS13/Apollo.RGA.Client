package org.olcbox.app.update.personal

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidPersonalUpdateRuntimeFactoryTest {
    private fun pin(curve: String = "secp256r1"): String = Base64.getEncoder().encodeToString(
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(curve)) }.generateKeyPair().public.encoded)

    private class NoIoContext(private val name: String = "tech.gatealtas.rga.personal") : ContextWrapper(null) {
        override fun getPackageName(): String = name
        override fun getApplicationContext(): Context = error("Factory performed context I/O")
        override fun getCacheDir(): File = error("Factory performed cache I/O")
        override fun getFilesDir(): File = error("Factory performed files I/O")
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = error("Factory performed preference I/O")
    }
    private fun create(context: Context = NoIoContext(), enabled: Boolean = true,
        variant: String = "githubDebug", encodedPin: String = pin()) = AndroidPersonalUpdateRuntimeFactory.create(
        context, enabled, variant, encodedPin) { error("Factory checked network route") }

    @Test fun exactPersonalGithubActivationCreatesFactoryWithoutIoOrChecking() {
        assertNotNull(create())
    }
    @Test fun activationAndCompiledVariantAndExactPackageAreAllRequired() {
        assertNull(create(enabled = false))
        assertNull(create(variant = "playDebug"))
        assertNull(create(variant = "githubRelease"))
        assertNull(create(variant = ""))
        assertNull(create(context = NoIoContext("tech.gatealtas.rga.debug")))
    }
    @Test fun absentMalformedAndUnsupportedPinsCannotActivate() {
        for (value in listOf("", "bad-base64", "AA==", pin("secp384r1"), pin() + "\n")) {
            assertNull(create(encodedPin = value))
        }
        val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public.encoded
        assertNull(create(encodedPin = Base64.getEncoder().encodeToString(rsa)))
    }
    @Test fun repeatedDeferredConstructionRetainsOneIdleControllerAndDoesNotCheck() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val context = object : ContextWrapper(app) { override fun getPackageName() = "tech.gatealtas.rga.personal" }
        val factory = create(context)
        assertNotNull(factory)
        val controller = factory!!.createController()
        assertSame(controller, factory.createController())
        assertEquals(PersonalUpdateState.Idle, controller.state.value)
    }
}
