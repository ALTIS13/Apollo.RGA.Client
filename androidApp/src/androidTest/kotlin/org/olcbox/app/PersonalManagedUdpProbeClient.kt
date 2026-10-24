package org.olcbox.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One-off independent-UID UDP QA; Sample deliberately cannot print addresses or nonce. */
internal object PersonalManagedUdpProbeClient {
    class Sample internal constructor(
        private val nonce: String,
        private val localIpv4: String,
        private val localPort: Int,
        private val remoteIpv4: String,
        private val remotePort: Int,
        private val helperUid: Int,
        private val count: Int,
    ) {
        fun sameSourceTuple(other: Sample): Boolean =
            localIpv4 == other.localIpv4 && localPort == other.localPort && helperUid == other.helperUid
        fun sameTarget(other: Sample): Boolean = remoteIpv4 == other.remoteIpv4 && remotePort == other.remotePort
        fun matchesSourceTuple(ipv4: String, port: Int): Boolean = localIpv4 == ipv4 && localPort == port
        fun matchesTarget(ipv4: String, port: Int): Boolean = remoteIpv4 == ipv4 && remotePort == port
        fun matchesNonce(value: String): Boolean = nonce == value
        fun nonceSha256(): String = MessageDigest.getInstance("SHA-256")
            .digest(nonce.toByteArray(StandardCharsets.US_ASCII))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        fun matchesHelperUid(uid: Int): Boolean = helperUid == uid
        fun matchesCount(value: Int): Boolean = count == value
        fun independentOf(uid: Int): Boolean = helperUid != uid
        override fun toString(): String = "ManagedUdpSample(redacted)"
    }

    fun sample(context: Context, targetIpv4: String, count: Int, targetPort: Int = PORT): Sample? {
        check(Thread.currentThread() !== Looper.getMainLooper().thread)
        if (context.packageName != MAIN_PACKAGE || !isPublicLiteral(targetIpv4) ||
            !allowedPort(targetPort) || count !in 1..3) return null
        val pm = context.packageManager
        val component = ComponentName(HELPER_PACKAGE, SERVICE)
        val helperUid = try { pm.getPackageUid(HELPER_PACKAGE, 0) }
            catch (_: PackageManager.NameNotFoundException) { return null }
        val service = try { pm.getServiceInfo(component, 0) }
            catch (_: PackageManager.NameNotFoundException) { return null }
        if (helperUid <= 0 || helperUid == Process.myUid() || service.packageName != HELPER_PACKAGE ||
            service.name != SERVICE || !service.exported || service.permission != PERMISSION ||
            pm.checkSignatures(MAIN_PACKAGE, HELPER_PACKAGE) != PackageManager.SIGNATURE_MATCH ||
            context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED ||
            pm.checkPermission(android.Manifest.permission.INTERNET, HELPER_PACKAGE) != PackageManager.PERMISSION_GRANTED
        ) return null

        val nonce = ByteArray(16).also(SecureRandom()::nextBytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val replyThread = HandlerThread("personal-managed-udp-reply").also { it.start() }
        val finished = CountDownLatch(1)
        val monitor = Any()
        var invalidated = false
        var liveBinder: IBinder? = null
        var preparedPort = 0
        var result: Sample? = null

        fun currentHelper(): Boolean = try {
            pm.getPackageUid(HELPER_PACKAGE, 0) == helperUid &&
                pm.checkSignatures(MAIN_PACKAGE, HELPER_PACKAGE) == PackageManager.SIGNATURE_MATCH
        } catch (_: Exception) { false }
        fun fail() {
            invalidated = true
            result = null
            finished.countDown()
        }

        val reply = Messenger(Handler(replyThread.looper) { message ->
            synchronized(monitor) {
                try {
                    val data = message.data
                    if (invalidated || liveBinder?.isBinderAlive != true || !currentHelper() ||
                        message.sendingUid != helperUid || data.getInt(KEY_UID, -1) != helperUid ||
                        data.getString(KEY_NONCE) != nonce || data.getString(KEY_REMOTE_IPV4) != targetIpv4 ||
                        data.getInt(KEY_REMOTE_PORT, -1) != targetPort || data.getInt(KEY_COUNT, -1) != count
                    ) {
                        fail()
                    } else when (message.what) {
                        PREPARED -> {
                            val port = data.getInt(KEY_LOCAL_PORT, -1)
                            val binder = liveBinder
                            if (preparedPort != 0 || port !in 1..65535 || binder == null) fail()
                            else {
                                preparedPort = port
                                try {
                                    Messenger(binder).send(Message.obtain(null, GO).apply {
                                        this.data = Bundle().apply { putString(KEY_NONCE, nonce) }
                                    })
                                } catch (_: Exception) { fail() }
                            }
                        }
                        RESULT -> {
                            val sent = data.getInt(KEY_SENT_COUNT, -1)
                            val echoed = data.getInt(KEY_ECHO_COUNT, -1)
                            val local = data.getString(KEY_LOCAL_IPV4).orEmpty()
                            if (preparedPort == 0 || data.getInt(KEY_LOCAL_PORT, -1) != preparedPort ||
                                !data.getBoolean(KEY_OK, false) || sent != count || echoed != count ||
                                data.getString(KEY_FAILURE) != null || !isUsableLocalIpv4(local)
                            ) fail()
                            else {
                                result = Sample(nonce, local, preparedPort, targetIpv4, targetPort, helperUid, count)
                                finished.countDown()
                            }
                        }
                        else -> fail()
                    }
                } catch (_: RuntimeException) { fail() }
            }
            true
        })

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                synchronized(monitor) {
                    if (invalidated || name != component || !binder.isBinderAlive || !currentHelper()) {
                        fail()
                        return
                    }
                    liveBinder = binder
                    try {
                        Messenger(binder).send(Message.obtain(null, PREPARE).apply {
                            replyTo = reply
                            data = Bundle().apply {
                                putString(KEY_NONCE, nonce)
                                putString(KEY_REMOTE_IPV4, targetIpv4)
                                putInt(KEY_REMOTE_PORT, targetPort)
                                putInt(KEY_COUNT, count)
                            }
                        })
                    } catch (_: Exception) { fail() }
                }
            }
            private fun invalidate() = synchronized(monitor) { fail(); liveBinder = null }
            override fun onServiceDisconnected(name: ComponentName) = invalidate()
            override fun onBindingDied(name: ComponentName) = invalidate()
            override fun onNullBinding(name: ComponentName) = invalidate()
        }

        var bound = false
        try {
            bound = context.bindService(Intent(ACTION).setComponent(component), connection, Context.BIND_AUTO_CREATE)
            if (!bound || !finished.await(15, TimeUnit.SECONDS)) return null
            return synchronized(monitor) {
                result.takeIf { !invalidated && liveBinder?.isBinderAlive == true && currentHelper() }
            }
        } catch (_: Exception) { return null }
        finally {
            val binder = synchronized(monitor) {
                val held = liveBinder
                invalidated = true
                result = null
                liveBinder = null
                held
            }
            binder?.let { current ->
                if (current.isBinderAlive) try {
                    Messenger(current).send(Message.obtain(null, CANCEL).apply {
                        data = Bundle().apply { putString(KEY_NONCE, nonce) }
                    })
                } catch (_: Exception) { /* Unbind remains mandatory. */ }
            }
            if (bound) try { context.unbindService(connection) }
                catch (_: Exception) { /* Already gone. */ }
            replyThread.quitSafely()
        }
    }

    private fun parseIpv4(value: String): IntArray? {
        val parts = value.split('.', ignoreCase = false, limit = 5)
        if (parts.size != 4) return null
        val numbers = IntArray(4)
        for (i in parts.indices) {
            val part = parts[i]
            if (!part.matches(Regex("0|[1-9][0-9]{0,2}"))) return null
            val number = part.toIntOrNull() ?: return null
            if (number > 255) return null
            numbers[i] = number
        }
        return numbers
    }

    private fun isUsableLocalIpv4(value: String): Boolean {
        val n = parseIpv4(value) ?: return false
        return n[0] != 0 && n[0] != 127 && n[0] < 224
    }

    private fun isPublicLiteral(value: String): Boolean {
        val n = parseIpv4(value) ?: return false
        val (a, b, c) = n
        return a != 0 && a != 10 && a != 127 && a < 224 &&
            !(a == 100 && b in 64..127) && !(a == 169 && b == 254) &&
            !(a == 172 && b in 16..31) && !(a == 192 && b == 168) &&
            !(a == 192 && b == 0 && (c == 0 || c == 2)) &&
            !(a == 198 && (b == 18 || b == 19)) && !(a == 198 && b == 51 && c == 100) &&
            !(a == 203 && b == 0 && c == 113)
    }

    private fun allowedPort(value: Int): Boolean = value == PORT || value == 443

    private const val MAIN_PACKAGE = "tech.gatealtas.rga.personal"
    private const val HELPER_PACKAGE = "tech.gatealtas.rga.personal.test"
    private const val SERVICE = "org.olcbox.app.PersonalManagedUdpProbeService"
    private const val ACTION = "tech.gatealtas.rga.personal.action.MANAGED_UDP_PROBE"
    private const val PERMISSION = "tech.gatealtas.rga.personal.permission.PERSONAL_TUN_PROBE"
    private const val PORT = 18446
    private const val PREPARE = 1
    private const val PREPARED = 2
    private const val GO = 3
    private const val RESULT = 4
    private const val CANCEL = 5
    private const val KEY_NONCE = "nonce"
    private const val KEY_UID = "uid"
    private const val KEY_REMOTE_IPV4 = "remoteIpv4"
    private const val KEY_REMOTE_PORT = "remotePort"
    private const val KEY_LOCAL_IPV4 = "localIpv4"
    private const val KEY_LOCAL_PORT = "localPort"
    private const val KEY_COUNT = "count"
    private const val KEY_SENT_COUNT = "sentCount"
    private const val KEY_ECHO_COUNT = "echoCount"
    private const val KEY_OK = "ok"
    private const val KEY_FAILURE = "failure"
}
