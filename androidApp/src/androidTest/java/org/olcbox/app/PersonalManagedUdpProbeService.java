package org.olcbox.app;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Independent-UID androidTest-only UDP diagnostic. Never binds or protects a network. */
public final class PersonalManagedUdpProbeService extends Service {
    private final PersonalManagedUdpProbeSocketOwner sockets = new PersonalManagedUdpProbeSocketOwner();
    private HandlerThread thread;
    private Handler handler;
    private Messenger messenger;
    private ThreadPoolExecutor worker;
    private Attempt active; // Handler thread only; socket authority is separately synchronized.

    @Override public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("personal-managed-udp-probe");
        thread.start();
        handler = new Handler(thread.getLooper(), message -> {
            if (!isPersonalCaller(message)) return true;
            try {
                switch (message.what) {
                    case PREPARE: prepare(message); break;
                    case GO: go(message); break;
                    case CANCEL: cancel(message); break;
                    default: break;
                }
            } catch (RuntimeException ignored) {
                // Malformed IPC is never allowed to crash the diagnostic service.
            }
            return true;
        });
        messenger = new Messenger(handler);
        worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), runnable -> new Thread(runnable, "personal-managed-udp-worker"),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Override public IBinder onBind(Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction()) || intent.getComponent() == null ||
                !HELPER_PACKAGE.equals(getPackageName()) ||
                !HELPER_PACKAGE.equals(intent.getComponent().getPackageName()) ||
                !getClass().getName().equals(intent.getComponent().getClassName())) return null;
        sockets.bind();
        return messenger.getBinder();
    }

    @Override public boolean onUnbind(Intent intent) {
        sockets.invalidate(); // Close before this lifecycle callback returns.
        return false;
    }

    @Override public void onDestroy() {
        sockets.shutdown();
        worker.shutdownNow();
        thread.quit();
        super.onDestroy();
    }

    private boolean isPersonalCaller(Message message) {
        try {
            PackageManager pm = getPackageManager();
            return HELPER_PACKAGE.equals(getPackageName()) && message.sendingUid > 0 &&
                    message.sendingUid != Process.myUid() &&
                    message.sendingUid == pm.getPackageUid(MAIN_PACKAGE, 0) &&
                    pm.checkSignatures(MAIN_PACKAGE, HELPER_PACKAGE) == PackageManager.SIGNATURE_MATCH;
        } catch (Exception ignored) { return false; }
    }

    private void prepare(Message message) {
        Messenger reply = message.replyTo;
        if (reply == null) return;
        Bundle request = message.getData();
        String nonce = request.getString(KEY_NONCE);
        Inet4Address remote = publicIpv4(request.getString(KEY_REMOTE_IPV4));
        int remotePort = request.getInt(KEY_REMOTE_PORT, -1);
        int count = request.getInt(KEY_COUNT, -1);
        if (nonce == null || !NONCE.matcher(nonce).matches() || remote == null ||
                !allowedPort(remotePort) || count < 1 || count > 3) return;

        closeActive();
        PersonalManagedUdpProbeSocketOwner.Token token = sockets.beginAttempt();
        if (token == null) return;
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(null);
            socket.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[] {0, 0, 0, 0}), 0));
            if (!sockets.publish(token, socket)) return;
            Attempt attempt = new Attempt(token, nonce, remote, remotePort, count, socket, reply,
                    SystemClock.elapsedRealtime() + ATTEMPT_TIMEOUT_MS);
            active = attempt;
            Bundle data = baseTuple(attempt);
            data.putInt(KEY_COUNT, count);
            Message prepared = Message.obtain(null, PREPARED);
            prepared.setData(data);
            boolean[] sent = {false};
            sockets.runIfCurrent(token, socket, () -> {
                try { reply.send(prepared); sent[0] = true; }
                catch (RemoteException ignored) { /* Caller has gone away. */ }
            });
            if (!sent[0]) { closeActive(); return; }
            handler.postDelayed(() -> {
                if (active == attempt && sockets.isCurrent(token)) closeActive();
            }, Math.max(0L, attempt.deadlineMs - SystemClock.elapsedRealtime()));
        } catch (Exception ignored) {
            sockets.finish(token);
            if (socket != null) socket.close();
            if (active != null && active.token == token) active = null;
        }
    }

    private void go(Message message) {
        Attempt attempt = active;
        if (attempt == null || attempt.started || !sockets.owns(attempt.token, attempt.socket) ||
                !attempt.nonce.equals(message.getData().getString(KEY_NONCE))) return;
        attempt.started = true;
        long now = SystemClock.elapsedRealtime();
        if (now >= attempt.deadlineMs) { closeActive(); return; }
        long networkDeadline = Math.min(attempt.deadlineMs, now + NETWORK_TIMEOUT_MS);
        // Independent of the network worker: close even if connect/send (not just receive) blocks.
        if (!sockets.armNetworkDeadline(attempt.token, attempt.socket, networkDeadline - now)) {
            closeActive();
            return;
        }
        try { worker.execute(() -> execute(attempt, networkDeadline)); }
        catch (RuntimeException ignored) { publishResult(attempt, false, 0, 0, "", "busy"); }
    }

    private void execute(Attempt attempt, long deadline) {
        int sent = 0;
        int echoed = 0;
        String localIpv4 = "";
        String failure = "network";
        byte[] payload = attempt.nonce.getBytes(StandardCharsets.US_ASCII);
        if (payload.length > 1200 || !sockets.owns(attempt.token, attempt.socket)) return;
        try {
            // DatagramSocket is an ordinary default-network socket of the test APK UID.
            attempt.socket.connect(attempt.remote, attempt.port);
            InetAddress local = attempt.socket.getLocalAddress();
            if (local instanceof Inet4Address && !local.isAnyLocalAddress())
                localIpv4 = local.getHostAddress();
            if (localIpv4.isEmpty()) throw new IllegalStateException();
            for (int i = 0; i < attempt.count; i++) {
                if (!sockets.owns(attempt.token, attempt.socket)) return;
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) { failure = "timeout"; break; }
                attempt.socket.send(new DatagramPacket(payload, payload.length, attempt.remote, attempt.port));
                sent++;
                byte[] bytes = new byte[1201];
                DatagramPacket response = new DatagramPacket(bytes, bytes.length);
                remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) { failure = "timeout"; break; }
                attempt.socket.setSoTimeout((int) Math.max(1, Math.min(remaining, NETWORK_TIMEOUT_MS)));
                attempt.socket.receive(response);
                if (SystemClock.elapsedRealtime() >= deadline) { failure = "timeout"; break; }
                if (!attempt.remote.equals(response.getAddress()) || response.getPort() != attempt.port ||
                        response.getLength() != payload.length ||
                        !Arrays.equals(payload, Arrays.copyOfRange(response.getData(),
                                response.getOffset(), response.getOffset() + response.getLength()))) {
                    failure = "echo";
                    break;
                }
                echoed++;
            }
            if (echoed == attempt.count) failure = null;
        } catch (java.net.SocketTimeoutException ignored) { failure = "timeout"; }
        catch (Exception ignored) { failure = "network"; }
        final int sentCount = sent, echoedCount = echoed;
        final String source = localIpv4, outcome = failure;
        handler.post(() -> publishResult(attempt, outcome == null, sentCount, echoedCount, source, outcome));
    }

    private void publishResult(Attempt attempt, boolean ok, int sent, int echoed,
                               String localIpv4, String failure) {
        if (active != attempt || SystemClock.elapsedRealtime() >= attempt.deadlineMs) return;
        Bundle data = baseTuple(attempt);
        data.putBoolean(KEY_OK, ok);
        data.putInt(KEY_COUNT, attempt.count);
        data.putInt(KEY_SENT_COUNT, sent);
        data.putInt(KEY_ECHO_COUNT, echoed);
        data.putString(KEY_LOCAL_IPV4, localIpv4);
        data.putString(KEY_FAILURE, failure);
        Message result = Message.obtain(null, RESULT);
        result.setData(data);
        boolean[] sentReply = {false};
        sockets.runIfCurrent(attempt.token, attempt.socket, () -> {
            try { attempt.reply.send(result); sentReply[0] = true; }
            catch (RemoteException ignored) { /* Caller has gone away. */ }
        });
        if (!sentReply[0]) closeActive();
    }

    private void cancel(Message message) {
        Attempt attempt = active;
        if (attempt != null && attempt.nonce.equals(message.getData().getString(KEY_NONCE))) closeActive();
    }

    private void closeActive() {
        Attempt attempt = active;
        active = null;
        if (attempt != null) sockets.finish(attempt.token);
    }

    private Bundle baseTuple(Attempt attempt) {
        Bundle data = new Bundle();
        data.putString(KEY_NONCE, attempt.nonce);
        data.putInt(KEY_UID, Process.myUid());
        data.putInt(KEY_LOCAL_PORT, attempt.socket.getLocalPort());
        data.putString(KEY_REMOTE_IPV4, attempt.remote.getHostAddress());
        data.putInt(KEY_REMOTE_PORT, attempt.port);
        return data;
    }

    private static Inet4Address publicIpv4(String value) {
        if (value == null) return null;
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return null;
        int[] n = new int[4];
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            if (!parts[i].matches("0|[1-9][0-9]{0,2}")) return null;
            try { n[i] = Integer.parseInt(parts[i]); }
            catch (NumberFormatException ignored) { return null; }
            if (n[i] > 255) return null;
            bytes[i] = (byte) n[i];
        }
        int a = n[0], b = n[1], c = n[2];
        if (a == 0 || a == 10 || a == 127 || a >= 224 ||
                (a == 100 && b >= 64 && b <= 127) || (a == 169 && b == 254) ||
                (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168) ||
                (a == 192 && b == 0 && (c == 0 || c == 2)) ||
                (a == 198 && (b == 18 || b == 19)) || (a == 198 && b == 51 && c == 100) ||
                (a == 203 && b == 0 && c == 113)) return null;
        try { return (Inet4Address) InetAddress.getByAddress(bytes); }
        catch (Exception ignored) { return null; }
    }

    private static boolean allowedPort(int value) {
        return value == PORT || value == 443;
    }

    private static final class Attempt {
        final PersonalManagedUdpProbeSocketOwner.Token token;
        final String nonce;
        final Inet4Address remote;
        final int port;
        final int count;
        final DatagramSocket socket;
        final Messenger reply;
        final long deadlineMs;
        boolean started; // Handler thread only.
        Attempt(PersonalManagedUdpProbeSocketOwner.Token token, String nonce, Inet4Address remote,
                int port, int count, DatagramSocket socket, Messenger reply, long deadlineMs) {
            this.token = token; this.nonce = nonce; this.remote = remote; this.port = port; this.count = count;
            this.socket = socket; this.reply = reply; this.deadlineMs = deadlineMs;
        }
    }

    private static final String MAIN_PACKAGE = "tech.gatealtas.rga.personal";
    private static final String HELPER_PACKAGE = "tech.gatealtas.rga.personal.test";
    private static final String ACTION = "tech.gatealtas.rga.personal.action.MANAGED_UDP_PROBE";
    private static final int PORT = 18446;
    private static final int PREPARE = 1, PREPARED = 2, GO = 3, RESULT = 4, CANCEL = 5;
    private static final String KEY_NONCE = "nonce", KEY_UID = "uid", KEY_REMOTE_IPV4 = "remoteIpv4";
    private static final String KEY_REMOTE_PORT = "remotePort", KEY_LOCAL_IPV4 = "localIpv4";
    private static final String KEY_LOCAL_PORT = "localPort", KEY_COUNT = "count", KEY_SENT_COUNT = "sentCount";
    private static final String KEY_ECHO_COUNT = "echoCount", KEY_OK = "ok", KEY_FAILURE = "failure";
    private static final long NETWORK_TIMEOUT_MS = 8_000L, ATTEMPT_TIMEOUT_MS = 15_000L;
    private static final Pattern NONCE = Pattern.compile("[0-9a-f]{32}");
}
