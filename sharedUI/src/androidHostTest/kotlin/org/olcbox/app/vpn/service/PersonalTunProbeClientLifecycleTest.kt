package org.olcbox.app.vpn.service

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import java.net.InetSocketAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowConnectivityManager
import org.robolectric.shadows.ShadowBinder
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSigningInfo
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [PersonalProbeOwnerShadow::class])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PersonalTunProbeClientLifecycleTest {
    @Test fun bothOwnerLookupsRunOffMainThread() = runBlocking {
        installMainLooperDispatcher()
        val context = ProbeContext()
        PersonalProbeOwnerShadow.rejectMainThread = true
        try {
            var liveCallbackEntered = false
            val result = async {
                PersonalTunProbeClient.probe(context, EXPECTED_IP, { true }) {
                    liveCallbackEntered = true
                    true
                }
            }
            awaitReady { result.isCompleted }
            val value = result.await()
            assertTrue("main-thread owner lookup must not reject a valid live receipt",
                value is PersonalTunProbeClient.Result.CompanionReceipt)
            assertTrue(liveCallbackEntered)
            assertEquals("both initial and final owner lookup must run off main", 2,
                PersonalProbeOwnerShadow.offMainCalls)
        } finally {
            PersonalProbeOwnerShadow.rejectMainThread = false
            releaseMainLooperDispatcher()
        }
    }
    @Test fun prepareCarriesChosenPublicAddressRatherThanHelperDns() = runBlocking {
        installMainLooperDispatcher()
        val context = ProbeContext()
        try {
            val result = async { PersonalTunProbeClient.probe(context, EXPECTED_IP, { true }, { true }) }
            awaitReady { result.isCompleted }
            assertTrue(result.await() is PersonalTunProbeClient.Result.CompanionReceipt)
            assertEquals(EXPECTED_IP, context.requestedAddress)
        } finally { releaseMainLooperDispatcher() }
    }

    @Test fun differentPublicPreparedAddressRejectedBeforeNativeArm() = runBlocking {
        installMainLooperDispatcher()
        val context = ProbeContext("8.8.8.8")
        var armCalled = false
        try {
            val result = async {
                PersonalTunProbeClient.probe(context, EXPECTED_IP, { armCalled = true; true }, { true })
            }
            awaitReady { result.isCompleted }
            val value = result.await()
            assertTrue("a substituted public target must be rejected before arm",
                value is PersonalTunProbeClient.Result.Rejected)
            assertEquals(PersonalTunProbeClient.Reason.PROTOCOL,
                (value as PersonalTunProbeClient.Result.Rejected).reason)
            assertFalse(armCalled)
            assertEquals(0, context.goCount)
        } finally { releaseMainLooperDispatcher() }
    }

    @Test fun fakeDnsTargetRejectedBeforeAnyBinding() = runBlocking {
        installMainLooperDispatcher()
        val context = ProbeContext()
        try {
            val result = async { PersonalTunProbeClient.probe(context, "100.64.0.1", { true }, { true }) }
            awaitReady { result.isCompleted }
            val value = result.await()
            assertTrue("a CGNAT target must be rejected before bind",
                value is PersonalTunProbeClient.Result.Rejected)
            assertEquals(PersonalTunProbeClient.Reason.PROTOCOL,
                (value as PersonalTunProbeClient.Result.Rejected).reason)
            assertEquals(0, context.bindCount)
        } finally { releaseMainLooperDispatcher() }
    }
    @Test fun disconnectDuringLiveReceiptCannotReturnCompanionReceipt() = interruptedReceipt(false)
    @Test fun bindingDeathDuringLiveReceiptCannotReturnCompanionReceipt() = interruptedReceipt(true)

    private fun interruptedReceipt(bindingDeath: Boolean) = runBlocking {
        installMainLooperDispatcher()
        val context = ProbeContext()
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        var callbackCancelled = false
        try {
            val result = async {
                PersonalTunProbeClient.probe(context, EXPECTED_IP, { true }) {
                    entered.complete(Unit)
                    try { release.await(); true } finally { callbackCancelled = !release.isCompleted }
                }
            }
            awaitReady { entered.isCompleted || result.isCompleted }
            assertTrue("must reach live callback; result=${if (result.isCompleted) result.getCompleted() else "pending"}", entered.isCompleted)
            if (bindingDeath) context.connection.onBindingDied(context.component)
            else context.connection.onServiceDisconnected(context.component)
            // Do not release the suspended callback to make a broken implementation pass.
            // Binding loss must complete cancellation on its own within this real-time bound.
            awaitReady(timeoutMs = 1_000L) { result.isCompleted }
            val promptlyInvalidated = result.isCompleted && callbackCancelled
            release.complete(Unit)
            val value = result.await()
            assertTrue("binding loss must cancel a suspended live callback immediately", promptlyInvalidated)
            assertTrue("receipt must not outlive its binding", value is PersonalTunProbeClient.Result.Rejected)
            assertEquals(1, context.unbound)
        } finally { release.complete(Unit); releaseMainLooperDispatcher() }
    }

    @Test fun socketOwnerDriftDuringLiveCallbackCannotReturnReceipt() = runBlocking {
        installMainLooperDispatcher()
        val context = ProbeContext()
        try {
            var callbackEntered = false
            val result = async {
                PersonalTunProbeClient.probe(context, EXPECTED_IP, { true }) {
                    callbackEntered = true
                    PersonalProbeOwnerShadow.ownerUid = HELPER_UID + 1
                    true
                }
            }
            awaitReady { result.isCompleted }
            val value = result.await()
            assertTrue("callback not entered; result=${if (result.isCompleted) result.getCompleted() else "pending"}", callbackEntered)
            assertTrue(value is PersonalTunProbeClient.Result.Rejected)
            assertEquals(1, context.unbound)
        } finally { releaseMainLooperDispatcher() }
    }

    @Test fun unchangedLiveBindingAndOwnerStillReturnReceipt() = runBlocking {
        installMainLooperDispatcher()
        val context = ProbeContext()
        try {
            val result = async { PersonalTunProbeClient.probe(context, EXPECTED_IP, { true }, { true }) }
            awaitReady { result.isCompleted }
            val value = result.await()
            assertTrue("unexpected result=$value", value is PersonalTunProbeClient.Result.CompanionReceipt)
            assertEquals(1, context.unbound)
        } finally { releaseMainLooperDispatcher() }
    }

    private fun installMainLooperDispatcher() {
        // Real Main message queue + real IO threads, with no virtual-time auto-advance.
        Dispatchers.setMain(Handler(Looper.getMainLooper()).asCoroutineDispatcher())
    }

    private suspend fun awaitReady(timeoutMs: Long = 5_000L, ready: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!ready()) {
                ShadowLooper.idleMainLooper()
                delay(5L)
            }
        }
    }

    private suspend fun releaseMainLooperDispatcher() {
        // Even a failed assertion must let the client's cancellable binding cleanup finish
        // while its Main dispatcher is still available, rather than leave queued children.
        val children = coroutineContext[Job]?.children?.toList().orEmpty()
        try {
            withContext(NonCancellable) {
                children.forEach { it.cancel() }
                awaitReady(timeoutMs = 1_000L) { children.all { it.isCompleted } }
                ShadowLooper.idleMainLooper()
            }
        } finally { Dispatchers.resetMain() }
    }

    private class ProbeContext(private val replyAddress: String = EXPECTED_IP) : ContextWrapper(RuntimeEnvironment.getApplication()) {
        lateinit var connection: ServiceConnection
        val component = ComponentName(HELPER_PACKAGE, "org.olcbox.app.PersonalTunProbeService")
        var unbound = 0
        var bindCount = 0
        var goCount = 0
        var requestedAddress: String? = null
        private val helper = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (message.what == 1) requestedAddress = message.data.getString("remoteIpv4")
            if (message.what == 3) goCount++
            if (message.what == 1 || message.what == 3) {
                val response = Message.obtain(null, if (message.what == 1) 2 else 4).apply {
                    sendingUid = HELPER_UID
                    data = Bundle().apply {
                        putString("nonce", message.data.getString("nonce"))
                        putInt("uid", HELPER_UID)
                        putString("remoteIpv4", replyAddress)
                        putString("localIpv4", "10.0.88.88")
                        putInt("localPort", 43127)
                        putInt("httpStatus", 200)
                        putBoolean("egressMatched", true)
                    }
                }
                ShadowBinder.setCallingUid(HELPER_UID)
                try { message.replyTo.send(response) } finally { ShadowBinder.setCallingUid(android.os.Process.myUid()) }
            }
            true
        })

        init {
            PersonalProbeOwnerShadow.ownerUid = HELPER_UID
            PersonalProbeOwnerShadow.offMainCalls = 0
            PersonalProbeOwnerShadow.rejectMainThread = false
            val pm = Shadows.shadowOf(packageManager)
            for ((name, uid) in listOf(MAIN_PACKAGE to 10001, HELPER_PACKAGE to HELPER_UID)) {
                val certificate = Signature("0123456789abcdef")
                val signing = ReflectionHelpers.callConstructor(SigningInfo::class.java)
                Shadow.extract<ShadowSigningInfo>(signing).setSignatures(arrayOf(certificate))
                pm.installPackage(PackageInfo().apply {
                    packageName = name
                    applicationInfo = ApplicationInfo().apply { packageName = name; this.uid = uid }
                    signatures = arrayOf(certificate)
                    signingInfo = signing
                    requestedPermissions = arrayOf(android.Manifest.permission.INTERNET)
                    requestedPermissionsFlags = intArrayOf(PackageInfo.REQUESTED_PERMISSION_GRANTED)
                })
            }
        }

        override fun getPackageName() = MAIN_PACKAGE
        override fun checkSelfPermission(permission: String) = PackageManager.PERMISSION_GRANTED
        override fun bindService(intent: Intent, conn: ServiceConnection, flags: Int): Boolean {
            bindCount++
            assertEquals(component, intent.component)
            connection = conn
            conn.onServiceConnected(component, helper.binder)
            return true
        }
        override fun unbindService(conn: ServiceConnection) { assertSame(connection, conn); unbound++ }
    }

    companion object {
        private const val MAIN_PACKAGE = "tech.gatealtas.rga.personal"
        private const val HELPER_PACKAGE = "tech.gatealtas.rga.personal.test"
        private const val HELPER_UID = 10342
        private const val EXPECTED_IP = "1.1.1.1"
    }
}

@Implements(ConnectivityManager::class)
class PersonalProbeOwnerShadow : ShadowConnectivityManager() {
    @Implementation
    fun getConnectionOwnerUid(protocol: Int, local: InetSocketAddress, remote: InetSocketAddress): Int {
        val onMain = Thread.currentThread() === Looper.getMainLooper().thread
        if (rejectMainThread && onMain) throw android.os.NetworkOnMainThreadException()
        if (!onMain) offMainCalls++
        assertEquals(android.system.OsConstants.IPPROTO_TCP, protocol)
        assertEquals("10.0.88.88", local.address.hostAddress)
        assertEquals(43127, local.port)
        assertEquals("1.1.1.1", remote.address.hostAddress)
        assertEquals(443, remote.port)
        return ownerUid
    }

    companion object {
        @Volatile var ownerUid = 10342
        @Volatile var rejectMainThread = false
        @Volatile var offMainCalls = 0
    }
}
