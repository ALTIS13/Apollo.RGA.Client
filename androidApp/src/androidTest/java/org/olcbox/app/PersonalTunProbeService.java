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
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.json.JSONObject;

/** Standalone independent-UID companion: Android/JDK only, no main-APK runtime dependencies. */
public final class PersonalTunProbeService extends Service {
    private HandlerThread thread;
    private Handler handler;
    private Messenger messenger;
    private final PersonalTunProbeSocketOwner sockets = new PersonalTunProbeSocketOwner();
    private Attempt active; // Handler thread only.

    @Override public void onCreate() {
        super.onCreate();
        diagnostic(Stage.CREATED);
        thread = new HandlerThread("personal-tun-probe");
        thread.start();
        handler = new Handler(thread.getLooper(), message -> {
            switch (message.what) {
                case PREPARE: diagnostic(Stage.MESSAGE_PREPARE); break;
                case GO: diagnostic(Stage.MESSAGE_GO); break;
                case CANCEL: diagnostic(Stage.MESSAGE_CANCEL); break;
                default: diagnostic(Stage.MESSAGE_UNKNOWN);
            }
            if (isPersonalCaller(message)) {
                switch (message.what) {
                    case PREPARE: prepare(message); break;
                    case GO: go(message); break;
                    case CANCEL: cancel(message); break;
                    default: diagnostic(Stage.MESSAGE_UNKNOWN);
                }
            }
            return true;
        });
        messenger = new Messenger(handler);
    }

    @Override public IBinder onBind(Intent intent) {
        if (intent != null && ACTION.equals(intent.getAction()) && intent.getComponent() != null &&
                getPackageName().equals(intent.getComponent().getPackageName()) &&
                getClass().getName().equals(intent.getComponent().getClassName())) {
            diagnostic(Stage.BOUND);
            sockets.bind();
            return messenger.getBinder();
        }
        diagnostic(Stage.BIND_REJECTED);
        return null;
    }

    @Override public boolean onUnbind(Intent intent) {
        diagnostic(Stage.UNBOUND);
        invalidateAndClose();
        return false;
    }

    @Override public void onDestroy() {
        diagnostic(Stage.DESTROYED);
        invalidateAndClose();
        thread.quitSafely();
        super.onDestroy();
    }

    private boolean isPersonalCaller(Message message) {
        try {
            diagnostic(Stage.CALLER_CHECK_BEGIN);
            if (message.sendingUid != getPackageManager().getPackageUid(MAIN_PACKAGE, 0)) {
                diagnostic(Stage.CALLER_UID_REJECTED);
                return false;
            }
            boolean signerMatches = getPackageManager().checkSignatures(MAIN_PACKAGE, getPackageName()) ==
                    PackageManager.SIGNATURE_MATCH;
            diagnostic(signerMatches ? Stage.CALLER_ACCEPTED : Stage.CALLER_SIGNER_REJECTED);
            return signerMatches;
        } catch (Exception ignored) {
            diagnostic(Stage.CALLER_LOOKUP_FAILED);
            return false;
        }
    }

    private void prepare(Message message) {
        diagnostic(Stage.PREPARE_RECEIVED);
        final Messenger reply = message.replyTo;
        if (reply == null) { diagnostic(Stage.PREPARE_NO_REPLY); return; }
        String suppliedNonce = message.getData().getString(KEY_NONCE);
        final String nonce = suppliedNonce == null ? "" : suppliedNonce;
        if (!NONCE_PATTERN.matcher(nonce).matches()) { diagnostic(Stage.PREPARE_NONCE_REJECTED); return; }
        closeActive();
        final PersonalTunProbeSocketOwner.Token token = sockets.beginAttempt();
        if (token == null) { diagnostic(Stage.PREPARE_UNBOUND); return; }
        Socket raw = null;
        boolean registered = false;
        try {
            diagnostic(Stage.TARGET_CHECK_BEGIN);
            // Numeric metadata was selected on the main app's exact physical snapshot.
            // Only this independent UID's ordinary default-network TCP socket is proof traffic.
            Inet4Address remote = publicIpv4(message.getData().getString(KEY_REMOTE_IPV4));
            if (remote == null) throw new IllegalStateException("invalid_public_target");
            diagnostic(Stage.TARGET_PUBLIC_READY);
            if (!sockets.isCurrent(token)) {
                diagnostic(Stage.PREPARE_STALE); return;
            }
            raw = new Socket();
            diagnostic(Stage.SOCKET_BIND_BEGIN);
            raw.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[] {0, 0, 0, 0}), 0));
            diagnostic(Stage.SOCKET_BIND_READY);
            raw.setSoTimeout(IO_TIMEOUT_MS);
            if (!sockets.publish(token, raw)) {
                diagnostic(Stage.PREPARE_STALE);
                return;
            }
            registered = true;
            active = new Attempt(token, nonce, remote, raw, reply);
            Message prepared = Message.obtain(null, PREPARED);
            Bundle data = new Bundle();
            data.putString(KEY_NONCE, nonce);
            data.putInt(KEY_UID, Process.myUid());
            data.putString(KEY_REMOTE_IPV4, remote.getHostAddress());
            data.putInt(KEY_LOCAL_PORT, raw.getLocalPort());
            prepared.setData(data);
            diagnostic(Stage.PREPARED_SEND);
            reply.send(prepared);
            diagnostic(Stage.PREPARED_SENT);
            handler.postDelayed(() -> {
                if (sockets.isCurrent(token)) {
                    diagnostic(Stage.ATTEMPT_EXPIRED);
                    closeActive();
                }
            }, ATTEMPT_TIMEOUT_MS);
        } catch (Exception ignored) {
            diagnostic(Stage.PREPARE_EXCEPTION);
            active = null;
            // A teardown while bind/prepare was in flight must not publish a stale failure.
            if (sockets.isCurrent(token)) sendFailure(reply, "prepare");
            sockets.finish(token);
            if (!registered) sockets.closeLater(raw);
        }
    }

    private void go(Message message) {
        diagnostic(Stage.GO_RECEIVED);
        Attempt attempt = active;
        if (attempt == null) { diagnostic(Stage.GO_NO_ACTIVE); return; }
        if (!sockets.isCurrent(attempt.token) || !Objects.equals(message.getData().getString(KEY_NONCE), attempt.nonce)) {
            diagnostic(Stage.GO_STALE); return;
        }
        int status = 0;
        String sourceIp = "";
        String receivedNonce = "";
        boolean egressMatched = false;
        String failure = "network";
        try {
            diagnostic(Stage.TCP_CONNECT_BEGIN);
            attempt.socket.connect(new InetSocketAddress(attempt.remote, PORT), IO_TIMEOUT_MS);
            diagnostic(Stage.TCP_CONNECT_READY);
            String local = attempt.socket.getLocalAddress().getHostAddress();
            sourceIp = local == null ? "" : local;
            if (!sockets.isCurrent(attempt.token)) { diagnostic(Stage.GO_STALE); return; }
            SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(attempt.socket, HOST, PORT, true);
            if (!sockets.publish(attempt.token, tls)) { diagnostic(Stage.GO_STALE); return; }
            tls.setSoTimeout(IO_TIMEOUT_MS);
            SSLParameters parameters = tls.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            parameters.setServerNames(Collections.singletonList(new SNIHostName(HOST)));
            parameters.setApplicationProtocols(new String[] {"http/1.1"});
            tls.setSSLParameters(parameters);
            diagnostic(Stage.TLS_HANDSHAKE_BEGIN);
            tls.startHandshake();
            diagnostic(Stage.TLS_HANDSHAKE_READY);
            if (!sockets.isCurrent(attempt.token)) { diagnostic(Stage.GO_STALE); return; }
            String request = "GET /v1/personal/health-proof?nonce=" + attempt.nonce + " HTTP/1.1\r\n" +
                    "Host: " + HOST + "\r\nAccept: application/json\r\n" +
                    "Cache-Control: no-store\r\nConnection: keep-alive\r\n\r\n";
            diagnostic(Stage.HTTP_SEND);
            tls.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            tls.getOutputStream().flush();
            diagnostic(Stage.HTTP_SENT);
            Response response = readResponse(tls.getInputStream());
            diagnostic(Stage.HTTP_RESPONSE_RECEIVED);
            status = response.status;
            if (status == 200) {
                JSONObject json = new JSONObject(response.body);
                Set<String> keys = new HashSet<>();
                Iterator<String> iterator = json.keys();
                while (iterator.hasNext()) keys.add(iterator.next());
                if (!keys.equals(new HashSet<>(Arrays.asList("nonce", "egressMatched")))) {
                    throw new IllegalStateException("invalid_receipt");
                }
                Object returnedNonce = json.opt("nonce");
                Object returnedMatch = json.opt("egressMatched");
                receivedNonce = returnedNonce instanceof String ? (String) returnedNonce : "";
                egressMatched = returnedMatch instanceof Boolean && (Boolean) returnedMatch;
                failure = NONCE_PATTERN.matcher(receivedNonce).matches() ? null : "receipt";
                diagnostic(failure == null ? Stage.RECEIPT_PARSED : Stage.RECEIPT_REJECTED);
            } else {
                diagnostic(Stage.HTTP_REJECTED);
                failure = "http";
            }
        } catch (Exception error) {
            diagnostic(Stage.GO_EXCEPTION);
            if (getPackageName().equals("tech.gatealtas.rga.personal.test")) {
                Log.d("RgaPersonalProofHelper", "GO_EXCEPTION_CLASS=" + error.getClass().getSimpleName());
            }
            failure = "network";
        }
        if (!sockets.isCurrent(attempt.token)) { diagnostic(Stage.GO_STALE); return; }
        Message result = Message.obtain(null, RESULT);
        Bundle data = new Bundle();
        data.putInt(KEY_UID, Process.myUid());
        data.putString(KEY_NONCE, receivedNonce);
        data.putInt(KEY_HTTP_STATUS, status);
        data.putBoolean(KEY_EGRESS_MATCHED, egressMatched);
        data.putString(KEY_LOCAL_IPV4, sourceIp);
        data.putString(KEY_REMOTE_IPV4, attempt.remote.getHostAddress());
        data.putInt(KEY_LOCAL_PORT, attempt.socket.getLocalPort());
        data.putString(KEY_FAILURE, failure);
        result.setData(data);
        try {
            diagnostic(Stage.RESULT_SEND);
            attempt.reply.send(result);
            diagnostic(Stage.RESULT_SENT);
        } catch (Exception ignored) {
            diagnostic(Stage.RESULT_SEND_FAILED);
            closeActive();
        }
        // Keep the TCP tuple until the caller checks its owner and sends CANCEL.
    }

    private void cancel(Message message) {
        diagnostic(Stage.CANCEL_RECEIVED);
        if (Objects.equals(message.getData().getString(KEY_NONCE), active == null ? null : active.nonce)) {
            closeActive();
        }
    }

    private void sendFailure(Messenger reply, String failure) {
        diagnostic(Stage.FAILURE_SEND);
        try {
            Message result = Message.obtain(null, RESULT);
            Bundle data = new Bundle();
            data.putInt(KEY_UID, Process.myUid());
            data.putString(KEY_FAILURE, failure);
            result.setData(data);
            reply.send(result);
        } catch (Exception ignored) { diagnostic(Stage.FAILURE_SEND_FAILED); }
    }

    private void diagnostic(Stage stage) {
        if ("tech.gatealtas.rga.personal.test".equals(getPackageName())) {
            Log.d("RgaPersonalProofHelper", stage.name());
        }
    }

    private void invalidateAndClose() {
        // Main-thread lifecycle: retire all callback authority and detach the exact old socket
        // synchronously. Its TLS close_notify/close runs on the independent close worker.
        sockets.invalidate();
    }

    private void closeActive() {
        if (active != null) sockets.finish(active.token);
        active = null;
    }

    private static Inet4Address publicIpv4(String value) throws IOException {
        if (value == null) return null;
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return null;
        int[] octets = new int[4];
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            if (!parts[i].matches("0|[1-9][0-9]{0,2}")) return null;
            octets[i] = Integer.parseInt(parts[i]);
            if (octets[i] > 255) return null;
            bytes[i] = (byte) octets[i];
        }
        int a = octets[0], b = octets[1], c = octets[2];
        if (a == 0 || a == 10 || a == 127 || a >= 224 ||
                (a == 100 && b >= 64 && b <= 127) || (a == 169 && b == 254) ||
                (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168) ||
                (a == 192 && b == 0 && (c == 0 || c == 2)) ||
                (a == 198 && (b == 18 || b == 19)) || (a == 198 && b == 51 && c == 100) ||
                (a == 203 && b == 0 && c == 113)) return null;
        return (Inet4Address) InetAddress.getByAddress(bytes);
    }

    private static Response readResponse(InputStream input) throws IOException {
        String statusLine = readLine(input);
        Matcher match = STATUS_PATTERN.matcher(statusLine);
        if (!match.matches()) throw new IllegalStateException("status");
        Integer contentLength = null;
        int headerBytes = statusLine.length() + 2;
        while (true) {
            String line = readLine(input);
            headerBytes += line.length() + 2;
            if (headerBytes > MAX_HEADERS) throw new IllegalStateException("headers");
            if (line.isEmpty()) break;
            if (line.regionMatches(true, 0, "Content-Length:", 0, "Content-Length:".length())) {
                if (contentLength != null) throw new IllegalStateException("duplicate_length");
                try { contentLength = Integer.valueOf(line.substring(line.indexOf(':') + 1).trim()); }
                catch (NumberFormatException ignored) { throw new IllegalStateException("length"); }
            }
        }
        if (contentLength == null) throw new IllegalStateException("missing_length");
        int length = contentLength;
        if (length < 0 || length > MAX_BODY) throw new IllegalStateException("oversized");
        byte[] body = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(body, offset, length - offset);
            if (count < 0) throw new IllegalStateException("truncated");
            offset += count;
        }
        return new Response(Integer.parseInt(match.group(1)), new String(body, StandardCharsets.UTF_8));
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (bytes.size() < MAX_LINE) {
            int next = input.read();
            if (next < 0) throw new IllegalStateException("eof");
            if (next == '\n') {
                byte[] line = bytes.toByteArray();
                if (line.length == 0 || line[line.length - 1] != '\r') throw new IllegalStateException("line_end");
                return new String(line, 0, line.length - 1, StandardCharsets.US_ASCII);
            }
            bytes.write(next);
        }
        throw new IllegalStateException("line_size");
    }

    private static final class Attempt {
        final PersonalTunProbeSocketOwner.Token token;
        final String nonce;
        final Inet4Address remote;
        final Socket socket;
        final Messenger reply;
        Attempt(PersonalTunProbeSocketOwner.Token token, String nonce, Inet4Address remote, Socket socket, Messenger reply) {
            this.token = token; this.nonce = nonce; this.remote = remote; this.socket = socket; this.reply = reply;
        }
    }

    private static final class Response {
        final int status;
        final String body;
        Response(int status, String body) { this.status = status; this.body = body; }
    }

    private enum Stage {
        CREATED, BOUND, BIND_REJECTED, UNBOUND, DESTROYED,
        CALLER_CHECK_BEGIN, CALLER_ACCEPTED, CALLER_UID_REJECTED, CALLER_SIGNER_REJECTED, CALLER_LOOKUP_FAILED,
        MESSAGE_PREPARE, MESSAGE_GO, MESSAGE_CANCEL, MESSAGE_UNKNOWN,
        PREPARE_RECEIVED, PREPARE_UNBOUND, PREPARE_NO_REPLY, PREPARE_NONCE_REJECTED, PREPARE_STALE,
        TARGET_CHECK_BEGIN, TARGET_PUBLIC_READY, SOCKET_BIND_BEGIN, SOCKET_BIND_READY, PREPARED_SEND, PREPARED_SENT,
        ATTEMPT_EXPIRED, PREPARE_EXCEPTION, GO_RECEIVED, GO_NO_ACTIVE, GO_STALE,
        TCP_CONNECT_BEGIN, TCP_CONNECT_READY, TLS_HANDSHAKE_BEGIN, TLS_HANDSHAKE_READY,
        HTTP_SEND, HTTP_SENT, HTTP_RESPONSE_RECEIVED, HTTP_REJECTED, RECEIPT_PARSED, RECEIPT_REJECTED,
        GO_EXCEPTION, RESULT_SEND, RESULT_SENT, RESULT_SEND_FAILED, CANCEL_RECEIVED, FAILURE_SEND, FAILURE_SEND_FAILED
    }

    private static final String MAIN_PACKAGE = "tech.gatealtas.rga.personal";
    private static final String HOST = "rga.apollot.ru";
    private static final int PORT = 443;
    private static final String ACTION = "tech.gatealtas.rga.personal.action.TUN_PROBE";
    private static final int PREPARE = 1, PREPARED = 2, GO = 3, RESULT = 4, CANCEL = 5;
    private static final String KEY_NONCE = "nonce", KEY_UID = "uid", KEY_REMOTE_IPV4 = "remoteIpv4";
    private static final String KEY_LOCAL_PORT = "localPort", KEY_HTTP_STATUS = "httpStatus";
    private static final String KEY_EGRESS_MATCHED = "egressMatched", KEY_LOCAL_IPV4 = "localIpv4", KEY_FAILURE = "failure";
    private static final int IO_TIMEOUT_MS = 5_000;
    private static final long ATTEMPT_TIMEOUT_MS = 25_000L;
    private static final int MAX_LINE = 1024, MAX_HEADERS = 4096, MAX_BODY = 1024;
    private static final Pattern NONCE_PATTERN = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern STATUS_PATTERN = Pattern.compile("HTTP/1\\.[01] ([0-9]{3})(?: .*)?");
}
