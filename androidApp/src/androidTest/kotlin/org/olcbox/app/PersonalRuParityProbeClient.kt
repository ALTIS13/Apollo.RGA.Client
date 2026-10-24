@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.olcbox.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.olcbox.app.vpn.service.PersonalTunProof

/** One-off physical QA only. No address is written, logged, or included in assertions. */
internal object PersonalRuParityProbeClient {
    class Sample private constructor(private val ipv4: String) {
        fun sameEgress(other: Sample): Boolean = ipv4 == other.ipv4
        override fun toString(): String = "RuParitySample(redacted)"
        companion object {
            fun validated(value: String): Sample? =
                if (PersonalTunProof.isPublicProbeIpv4(value)) Sample(value) else null
        }
    }

    fun sample(context: Context, vpnExpected: Boolean, physicalCellularHandle: Long): Sample? {
        check(Thread.currentThread() !== Looper.getMainLooper().thread)
        if (context.packageName != MAIN_PACKAGE || physicalCellularHandle <= 0) return null
        val pm = context.packageManager
        val helperUid = try { pm.getPackageUid(HELPER_PACKAGE, 0) }
            catch (_: PackageManager.NameNotFoundException) { return null }
        if (helperUid <= 0 || helperUid == Process.myUid() ||
            pm.checkSignatures(MAIN_PACKAGE, HELPER_PACKAGE) != PackageManager.SIGNATURE_MATCH ||
            context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED ||
            pm.checkPermission(android.Manifest.permission.INTERNET, HELPER_PACKAGE) != PackageManager.PERMISSION_GRANTED ||
            pm.checkPermission(android.Manifest.permission.ACCESS_NETWORK_STATE, HELPER_PACKAGE) != PackageManager.PERMISSION_GRANTED
        ) return null
        val nonce = ByteArray(16).also(SecureRandom()::nextBytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val completed = CountDownLatch(1)
        val monitor = Any()
        var liveBinder: IBinder? = null
        var invalidated = false
        var result: Sample? = null
        val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
            synchronized(monitor) {
                if (!invalidated && liveBinder?.isBinderAlive == true && message.what == RESULT &&
                    message.sendingUid == helperUid && message.data.getInt("uid", -1) == helperUid &&
                    message.data.getString("nonce") == nonce && message.data.getBoolean("ok", false) &&
                    message.data.getBoolean("networkMatched", false)
                ) result = Sample.validated(message.data.getString("ipv4").orEmpty())
                completed.countDown()
            }
            true
        })
        // Do not reuse a process that may still have VPN FakeDNS aliases in its JVM cache.
        // The baseline keeps the SAME UID/default socket and checks its own no-VPN network.
        val component = ComponentName(HELPER_PACKAGE, if (vpnExpected) SERVICE else BASELINE_SERVICE)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                synchronized(monitor) {
                    if (invalidated || name != component || !binder.isBinderAlive) {
                        invalidated = true
                        completed.countDown()
                        return
                    }
                    liveBinder = binder
                    try {
                        Messenger(binder).send(Message.obtain(null, REQUEST).apply {
                            replyTo = reply
                            data = Bundle().apply {
                                putString("nonce", nonce)
                                putBoolean("vpnExpected", vpnExpected)
                                putLong("physicalCellularHandle", physicalCellularHandle)
                            }
                        })
                    } catch (_: Exception) { invalidated = true; completed.countDown() }
                }
            }
            private fun invalidate() = synchronized(monitor) {
                invalidated = true; result = null; liveBinder = null; completed.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) = invalidate()
            override fun onBindingDied(name: ComponentName) = invalidate()
            override fun onNullBinding(name: ComponentName) = invalidate()
        }
        var bound = false
        try {
            bound = context.bindService(Intent(ACTION).setComponent(component), connection, Context.BIND_AUTO_CREATE)
            if (!bound || !completed.await(20, TimeUnit.SECONDS)) return null
            return synchronized(monitor) {
                result.takeIf { !invalidated && liveBinder?.isBinderAlive == true }
            }
        } catch (_: Exception) { return null }
        finally {
            synchronized(monitor) { invalidated = true; liveBinder = null; result = null }
            if (bound) try { context.unbindService(connection) } catch (_: Exception) { /* Already gone. */ }
        }
    }

    private const val MAIN_PACKAGE = "tech.gatealtas.rga.personal"
    private const val HELPER_PACKAGE = "tech.gatealtas.rga.personal.test"
    private const val SERVICE = "org.olcbox.app.PersonalRuParityProbeService"
    private const val BASELINE_SERVICE = "org.olcbox.app.PersonalRuParityBaselineService"
    private const val ACTION = "tech.gatealtas.rga.personal.action.RU_PARITY_PROBE"
    private const val PERMISSION = "tech.gatealtas.rga.personal.permission.PERSONAL_TUN_PROBE"
    private const val REQUEST = 100
    private const val RESULT = 101
}
