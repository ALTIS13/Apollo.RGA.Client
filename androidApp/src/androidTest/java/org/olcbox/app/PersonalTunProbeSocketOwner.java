package org.olcbox.app;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Owns the current independent-UID probe socket and its binding/attempt fences. */
final class PersonalTunProbeSocketOwner {
    private boolean bound;
    private int bindingEpoch;
    private int generation;
    private Socket raw;
    private Socket socket;
    // Independent of the probe Handler, which may be blocked inside connect/handshake/read.
    // At most one close thread per service; it disappears one second after the queue drains.
    // No shutdown rejection race: a late TLS wrapper after onDestroy still must be closed.
    private final ThreadPoolExecutor closer = new ThreadPoolExecutor(
            0, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "personal-tun-close");
                thread.setDaemon(true);
                return thread;
            });

    synchronized void bind() { bindingEpoch++; bound = true; }

    synchronized Token beginAttempt() {
        if (!bound) return null;
        generation++;
        retireSocket();
        return new Token(bindingEpoch, generation);
    }

    synchronized boolean isCurrent(Token token) {
        return token != null && bound && token.bindingEpoch == bindingEpoch && token.generation == generation;
    }

    synchronized boolean publish(Token token, Socket candidate) {
        if (!isCurrent(token)) { closeLater(candidate); return false; }
        if (raw == null) raw = candidate;
        socket = candidate;
        return true;
    }

    synchronized void finish(Token token) {
        if (!isCurrent(token)) return;
        generation++;
        retireSocket();
    }

    synchronized void invalidate() {
        bound = false;
        bindingEpoch++;
        generation++;
        retireSocket();
    }

    void closeLater(Socket candidate) {
        if (candidate == null) return;
        closeLater(candidate, null);
    }

    private void closeLater(Socket first, Socket wrapper) {
        if (first == null && wrapper == null) return;
        closer.execute(() -> {
            // Release the underlying TCP socket first, interrupting probe IO even if TLS teardown fails.
            closeNow(first);
            if (wrapper != first) closeNow(wrapper);
        });
    }

    private static void closeNow(Socket candidate) {
        if (candidate == null) return;
        try { candidate.close(); }
        catch (IOException | RuntimeException ignored) { /* Retired resource: no receipt or lifecycle failure. */ }
    }

    private void retireSocket() {
        Socket previousRaw = raw;
        Socket previousSocket = socket;
        raw = null;
        socket = null;
        closeLater(previousRaw, previousSocket);
    }

    static final class Token {
        private final int bindingEpoch;
        private final int generation;
        private Token(int bindingEpoch, int generation) {
            this.bindingEpoch = bindingEpoch;
            this.generation = generation;
        }
    }
}
