package org.olcbox.app;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.CookieHandler;
import java.net.Proxy;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;

/** QA-only independent-UID RU route observation; not an admission or Connected gate. */
public class PersonalRuParityProbeService extends Service {
    private HandlerThread worker;
    private Handler handler;
    private final Handler deadlineHandler = new Handler(Looper.getMainLooper());
    private Messenger messenger;
    private final AtomicInteger generation = new AtomicInteger();
    private final AtomicInteger bindingEpoch = new AtomicInteger();
    private volatile boolean bound;
    private volatile Request active;
    private volatile HttpsURLConnection connection;

    @Override public void onCreate() {
        super.onCreate();
        worker = new HandlerThread("personal-ru-parity");
        worker.start();
        handler = new Handler(worker.getLooper(), message -> {
            if (message.what == 100 && personalCaller(message)) probe(message);
            return true;
        });
        messenger = new Messenger(handler);
    }

    @Override public synchronized IBinder onBind(Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction()) || intent.getComponent() == null ||
                !HELPER_PACKAGE.equals(getPackageName()) ||
                !HELPER_PACKAGE.equals(intent.getComponent().getPackageName()) ||
                !getClass().getName().equals(intent.getComponent().getClassName())) return null;
        bindingEpoch.incrementAndGet();
        bound = true;
        return messenger.getBinder();
    }

    @Override public boolean onUnbind(Intent intent) { invalidate(); return false; }
    @Override public void onDestroy() {
        invalidate();
        worker.quitSafely();
        super.onDestroy();
    }

    private boolean personalCaller(Message message) {
        try {
            return bound && message.sendingUid == getPackageManager().getPackageUid(MAIN_PACKAGE, 0) &&
                    getPackageManager().checkSignatures(MAIN_PACKAGE, getPackageName()) ==
                            PackageManager.SIGNATURE_MATCH;
        } catch (Exception ignored) { return false; }
    }

    private void probe(Message message) {
        String nonce = message.getData().getString("nonce");
        if (message.replyTo == null || nonce == null || !NONCE.matcher(nonce).matches() || !bound) return;
        Object mode = message.getData().get("vpnExpected");
        Object physicalHandle = message.getData().get("physicalCellularHandle");
        ConnectivityManager connectivity = getSystemService(ConnectivityManager.class);
        Network initial = null;
        try { if (connectivity != null) initial = connectivity.getActiveNetwork(); }
        catch (Exception ignored) { /* Missing network observation fails closed below. */ }
        boolean parametersValid = mode instanceof Boolean && physicalHandle instanceof Long &&
                (Long) physicalHandle > 0;
        Request request = new Request(generation.incrementAndGet(), bindingEpoch.get(), nonce, message.replyTo,
                parametersValid && (Boolean) mode, parametersValid ? (Long) physicalHandle : 0,
                initial == null ? 0 : initial.getNetworkHandle(), connectivity);
        active = request;
        request.timeout = () -> {
            if (liveRequest(request)) {
                // Publish the bounded negative result before disconnect, which may itself
                // wait for the platform's in-progress IO cancellation.
                complete(request, null);
                HttpsURLConnection pending = connection;
                if (pending != null) pending.disconnect();
            }
        };
        deadlineHandler.postDelayed(request.timeout, WHOLE_TIMEOUT_MS);
        HttpsURLConnection opened = null;
        String result = null;
        try {
            if (!current(request) || !parametersValid || !networkMatched(request)) {
                diagnostic("NETWORK_REJECTED");
                return;
            }
            diagnostic("NETWORK_READY");
            request.networkCallback = new ConnectivityManager.NetworkCallback() {
                private void validate() {
                    if (liveRequest(request) && !networkMatched(request)) rejectNetwork(request);
                }
                @Override public void onAvailable(Network network) {
                    if (network.getNetworkHandle() != request.defaultHandle) rejectNetwork(request);
                    else validate();
                }
                @Override public void onLost(Network network) { rejectNetwork(request); }
                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                    if (!defaultMatches(request, network, capabilities)) rejectNetwork(request);
                    else validate();
                }
            };
            if (!registerNetworkCallback(request)) return;
            if (!current(request) || !networkMatched(request)) return;
            // A non-null process CookieHandler could add ambient cookies despite request headers.
            // Refuse it, rather than mutate any global process cookie policy.
            if (CookieHandler.getDefault() != null || !current(request)) return;
            opened = (HttpsURLConnection) new URL("https://yandex.ru/internet/").openConnection(Proxy.NO_PROXY);
            connection = opened;
            if (!current(request) || !networkMatched(request)) return;
            opened.setInstanceFollowRedirects(false);
            opened.setUseCaches(false);
            opened.setConnectTimeout(IO_TIMEOUT_MS);
            opened.setReadTimeout(IO_TIMEOUT_MS);
            opened.setRequestProperty("Accept", "text/html");
            opened.setRequestProperty("Accept-Encoding", "identity");
            opened.setRequestProperty("Cache-Control", "no-store");
            opened.setRequestProperty("Pragma", "no-cache");
            opened.setRequestProperty("Cookie", "");
            // Default platform trust manager and hostname verifier are deliberately untouched.
            if (opened.getResponseCode() != 200 || !current(request) || !networkMatched(request)) return;
            diagnostic("HTTP_OK");
            String contentType = opened.getContentType();
            if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("text/html")) return;
            if (opened.getContentLengthLong() > MAX_BYTES) return;
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream input = opened.getInputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (!current(request) || !networkMatched(request) || body.size() + count > MAX_BYTES) return;
                    body.write(buffer, 0, count);
                }
            }
            String html = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body.toByteArray())).toString();
            if (current(request) && networkMatched(request)) {
                result = PersonalRuIpv4Parser.parse(html);
                diagnostic(result == null ? "SCHEMA_REJECTED" : "SCHEMA_ACCEPTED");
            }
        } catch (Exception ignored) { diagnostic("IO_REJECTED"); /* No exception text. */ }
        finally {
            if (opened != null) opened.disconnect();
            if (connection == opened) connection = null;
            complete(request, result);
            unregisterNetworkCallback(request);
            deadlineHandler.removeCallbacks(request.timeout);
        }
    }

    private boolean current(Request request) {
        return liveRequest(request) &&
                SystemClock.elapsedRealtime() - request.started <= WHOLE_TIMEOUT_MS;
    }

    private boolean liveRequest(Request request) {
        return bound && active == request && generation.get() == request.generation &&
                bindingEpoch.get() == request.binding && !request.completed.get() &&
                request.timeout != null;
    }

    private synchronized void complete(Request request, String address) {
        if (!bound || active != request || generation.get() != request.generation ||
                bindingEpoch.get() != request.binding || !request.completed.compareAndSet(false, true)) return;
        boolean matched = networkMatched(request);
        boolean valid = matched && address != null &&
                SystemClock.elapsedRealtime() - request.started < WHOLE_TIMEOUT_MS;
        Message reply = Message.obtain(null, 101);
        Bundle data = new Bundle();
        data.putString("nonce", request.nonce);
        data.putInt("uid", Process.myUid());
        data.putString("ipv4", valid ? address : "");
        data.putBoolean("ok", valid);
        data.putBoolean("networkMatched", matched);
        reply.setData(data);
        try { request.reply.send(reply); } catch (Exception ignored) { }
        finally { unregisterNetworkCallback(request); }
    }

    private boolean networkMatched(Request request) {
        try {
            if (request.connectivity == null || request.physicalHandle <= 0 || request.defaultHandle <= 0 ||
                    request.networkRejected.get()) return false;
            Network defaultNetwork = request.connectivity.getActiveNetwork();
            if (defaultNetwork == null) return false;
            NetworkCapabilities defaults = request.connectivity.getNetworkCapabilities(defaultNetwork);
            if (!defaultMatches(request, defaultNetwork, defaults)) return false;
            boolean physicalFound = false;
            for (Network network : request.connectivity.getAllNetworks()) {
                NetworkCapabilities caps = request.connectivity.getNetworkCapabilities(network);
                if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false;
                if (network.getNetworkHandle() == request.physicalHandle) {
                    physicalFound = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && internetNonCaptive(caps);
                }
            }
            return physicalFound;
        } catch (Exception ignored) { return false; }
    }

    private static boolean internetNonCaptive(NetworkCapabilities caps) {
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);
    }

    private static boolean defaultMatches(Request request, Network network, NetworkCapabilities caps) {
        return network != null && network.getNetworkHandle() == request.defaultHandle &&
                internetNonCaptive(caps) &&
                request.vpnExpected == caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (request.vpnExpected || (network.getNetworkHandle() == request.physicalHandle &&
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)));
    }

    private void rejectNetwork(Request request) {
        if (!liveRequest(request)) return;
        diagnostic("NETWORK_CHANGED");
        request.networkRejected.set(true);
        complete(request, null);
        HttpsURLConnection pending = connection;
        if (pending != null) pending.disconnect();
    }

    private synchronized boolean registerNetworkCallback(Request request) {
        // onUnbind/timeout cannot unregister before registration has actually completed.
        // Callback delivery uses the separate Main handler and observes the same fences.
        if (!current(request) || !networkMatched(request)) return false;
        request.connectivity.registerDefaultNetworkCallback(request.networkCallback, deadlineHandler);
        request.callbackRegistered.set(true);
        if (!current(request) || !networkMatched(request)) {
            unregisterNetworkCallback(request);
            return false;
        }
        return true;
    }

    private synchronized void unregisterNetworkCallback(Request request) {
        if (request.networkCallback != null && request.callbackRegistered.compareAndSet(true, false)) {
            try { request.connectivity.unregisterNetworkCallback(request.networkCallback); }
            catch (Exception ignored) { }
        }
    }

    private void diagnostic(String stage) {
        if (HELPER_PACKAGE.equals(getPackageName())) Log.d("RgaRuParityProbe", stage);
    }

    private synchronized void invalidate() {
        bound = false;
        bindingEpoch.incrementAndGet();
        generation.incrementAndGet();
        Request request = active;
        if (request != null) {
            request.completed.set(true);
            unregisterNetworkCallback(request);
            if (request.timeout != null) deadlineHandler.removeCallbacks(request.timeout);
        }
        HttpsURLConnection pending = connection;
        if (pending != null) pending.disconnect();
        connection = null;
        active = null;
    }

    private static final class Request {
        final int generation, binding;
        final String nonce;
        final Messenger reply;
        final long started = SystemClock.elapsedRealtime();
        final AtomicBoolean completed = new AtomicBoolean();
        final AtomicBoolean networkRejected = new AtomicBoolean();
        final AtomicBoolean callbackRegistered = new AtomicBoolean();
        final boolean vpnExpected;
        final long physicalHandle, defaultHandle;
        final ConnectivityManager connectivity;
        ConnectivityManager.NetworkCallback networkCallback;
        Runnable timeout;
        Request(int generation, int binding, String nonce, Messenger reply, boolean vpnExpected,
                long physicalHandle, long defaultHandle, ConnectivityManager connectivity) {
            this.generation = generation; this.binding = binding; this.nonce = nonce; this.reply = reply;
            this.vpnExpected = vpnExpected; this.physicalHandle = physicalHandle;
            this.defaultHandle = defaultHandle; this.connectivity = connectivity;
        }
    }

    private static final String MAIN_PACKAGE = "tech.gatealtas.rga.personal";
    private static final String HELPER_PACKAGE = "tech.gatealtas.rga.personal.test";
    private static final String ACTION = "tech.gatealtas.rga.personal.action.RU_PARITY_PROBE";
    private static final Pattern NONCE = Pattern.compile("[0-9a-f]{32}");
    private static final int IO_TIMEOUT_MS = 5_000, MAX_BYTES = 1_048_576;
    private static final long WHOLE_TIMEOUT_MS = 15_000L;
}
