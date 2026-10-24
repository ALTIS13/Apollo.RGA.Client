package org.olcbox.app;

import java.net.DatagramSocket;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Android-free owner for one ordinary default-network UDP socket. */
final class PersonalManagedUdpProbeSocketOwner {
    static final class Token {
        private Token() { }
    }

    private boolean bound;
    private boolean destroyed;
    private Token current;
    private DatagramSocket socket;
    private ScheduledFuture<?> deadline;
    private final ScheduledThreadPoolExecutor watchdog = new ScheduledThreadPoolExecutor(1, runnable -> {
        Thread thread = new Thread(runnable, "personal-managed-udp-deadline");
        thread.setDaemon(true);
        return thread;
    });

    PersonalManagedUdpProbeSocketOwner() {
        watchdog.setRemoveOnCancelPolicy(true);
    }

    synchronized void bind() {
        if (destroyed) return;
        closeSocket();
        current = null;
        bound = true;
    }

    synchronized Token beginAttempt() {
        if (!bound) return null;
        closeSocket();
        current = new Token();
        return current;
    }

    synchronized boolean isCurrent(Token token) {
        return bound && token != null && current == token;
    }

    synchronized boolean owns(Token token, DatagramSocket candidate) {
        return isCurrent(token) && candidate != null && socket == candidate && !candidate.isClosed();
    }

    synchronized boolean publish(Token token, DatagramSocket candidate) {
        if (!isCurrent(token) || socket != null || candidate == null || candidate.isClosed()) {
            if (candidate != null && candidate != socket) candidate.close();
            return false;
        }
        socket = candidate;
        return true;
    }

    /** Serializes result publication with invalidate(), including the callback itself. */
    synchronized boolean runIfCurrent(Token token, DatagramSocket candidate, Runnable action) {
        if (!owns(token, candidate)) return false;
        action.run();
        return true;
    }

    synchronized void finish(Token token) {
        if (!isCurrent(token)) return;
        current = null;
        closeSocket();
    }

    synchronized void invalidate() {
        bound = false;
        current = null;
        closeSocket();
    }

    synchronized boolean armNetworkDeadline(Token token, DatagramSocket candidate, long delayMs) {
        if (!owns(token, candidate)) return false;
        if (delayMs <= 0) { finish(token); return false; }
        cancelDeadline();
        try {
            deadline = watchdog.schedule(() -> {
                synchronized (PersonalManagedUdpProbeSocketOwner.this) {
                    if (owns(token, candidate)) finish(token);
                }
            }, delayMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (RejectedExecutionException ignored) {
            finish(token);
            return false;
        }
    }

    synchronized void shutdown() {
        destroyed = true;
        invalidate();
        watchdog.shutdownNow();
    }

    private void closeSocket() {
        cancelDeadline();
        if (socket != null) socket.close();
        socket = null;
    }

    private void cancelDeadline() {
        if (deadline != null) deadline.cancel(false);
        deadline = null;
    }
}
