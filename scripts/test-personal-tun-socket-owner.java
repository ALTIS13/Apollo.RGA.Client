package org.olcbox.app;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Standalone JDK regression against the companion's production socket owner. */
class PersonalTunProbeSocketOwnerTest {
    private static int cases;

    public static void main(String[] args) throws Exception {
        invalidationFencesBeforeOffThreadClose();
        delayedOldCloseCannotCloseReboundSocket();
        lateTlsPublicationIsRejectedAndClosed();
        closeFailureDoesNotPreventNextRelease();
        finishCannotRetireANewerAttempt();
        idleCloserThreadExpires();
        tlsRetirementAlsoReleasesUnderlyingRawSocket();
        System.out.println("PASS cases=" + cases);
    }

    private static void invalidationFencesBeforeOffThreadClose() throws Exception {
        cases++;
        PersonalTunProbeSocketOwner owner = new PersonalTunProbeSocketOwner();
        owner.bind();
        PersonalTunProbeSocketOwner.Token token = owner.beginAttempt();
        GuardedSocket socket = new GuardedSocket(Thread.currentThread(), true, false);
        check(owner.publish(token, socket), "initial_publish");
        owner.invalidate();
        check(!owner.isCurrent(token), "stale_fence_must_be_synchronous");
        check(owner.beginAttempt() == null, "unbound_cannot_begin");
        socket.awaitStarted();
        check(socket.closed.get() == 0, "invalidate_must_not_wait_for_close");
        socket.release();
        socket.awaitFinished();
        socket.assertOffCallerThread();
    }

    private static void delayedOldCloseCannotCloseReboundSocket() throws Exception {
        cases++;
        PersonalTunProbeSocketOwner owner = new PersonalTunProbeSocketOwner();
        owner.bind();
        PersonalTunProbeSocketOwner.Token old = owner.beginAttempt();
        GuardedSocket oldSocket = new GuardedSocket(Thread.currentThread(), true, false);
        check(owner.publish(old, oldSocket), "old_publish");
        owner.invalidate();
        oldSocket.awaitStarted();
        owner.bind();
        PersonalTunProbeSocketOwner.Token current = owner.beginAttempt();
        GuardedSocket currentSocket = new GuardedSocket(Thread.currentThread(), false, false);
        check(owner.publish(current, currentSocket), "rebound_publish");
        oldSocket.release();
        oldSocket.awaitFinished();
        check(owner.isCurrent(current), "old_close_cannot_invalidate_rebound");
        check(currentSocket.closed.get() == 0, "old_close_cannot_read_new_socket");
        owner.invalidate();
        currentSocket.awaitFinished();
        currentSocket.assertOffCallerThread();
    }

    private static void lateTlsPublicationIsRejectedAndClosed() throws Exception {
        cases++;
        PersonalTunProbeSocketOwner owner = new PersonalTunProbeSocketOwner();
        owner.bind();
        PersonalTunProbeSocketOwner.Token old = owner.beginAttempt();
        owner.invalidate();
        owner.bind();
        PersonalTunProbeSocketOwner.Token current = owner.beginAttempt();
        GuardedSocket currentSocket = new GuardedSocket(Thread.currentThread(), false, false);
        check(owner.publish(current, currentSocket), "current_publish");
        GuardedSocket lateTls = new GuardedSocket(Thread.currentThread(), false, false);
        check(!owner.publish(old, lateTls), "stale_tls_must_not_publish");
        lateTls.awaitFinished();
        lateTls.assertOffCallerThread();
        check(currentSocket.closed.get() == 0, "late_tls_cannot_replace_current");
        owner.invalidate();
        currentSocket.awaitFinished();
    }

    private static void closeFailureDoesNotPreventNextRelease() throws Exception {
        cases++;
        PersonalTunProbeSocketOwner owner = new PersonalTunProbeSocketOwner();
        owner.bind();
        PersonalTunProbeSocketOwner.Token old = owner.beginAttempt();
        GuardedSocket failing = new GuardedSocket(Thread.currentThread(), false, true);
        check(owner.publish(old, failing), "failing_publish");
        owner.invalidate();
        failing.awaitFinished();
        owner.bind();
        PersonalTunProbeSocketOwner.Token current = owner.beginAttempt();
        GuardedSocket next = new GuardedSocket(Thread.currentThread(), false, false);
        check(owner.publish(current, next), "next_publish");
        owner.invalidate();
        next.awaitFinished();
        next.assertOffCallerThread();
    }

    private static void finishCannotRetireANewerAttempt() throws Exception {
        cases++;
        PersonalTunProbeSocketOwner owner = new PersonalTunProbeSocketOwner();
        owner.bind();
        PersonalTunProbeSocketOwner.Token old = owner.beginAttempt();
        owner.finish(old);
        PersonalTunProbeSocketOwner.Token current = owner.beginAttempt();
        GuardedSocket socket = new GuardedSocket(Thread.currentThread(), false, false);
        check(owner.publish(current, socket), "new_attempt_publish");
        owner.finish(old);
        check(owner.isCurrent(current), "stale_finish_cannot_retire_current");
        check(socket.closed.get() == 0, "stale_finish_cannot_close_current");
        owner.finish(current);
        check(!owner.isCurrent(current), "finish_fence_synchronous");
        socket.awaitFinished();
    }

    private static void idleCloserThreadExpires() throws Exception {
        cases++;
        PersonalTunProbeSocketOwner owner = new PersonalTunProbeSocketOwner();
        owner.bind();
        PersonalTunProbeSocketOwner.Token token = owner.beginAttempt();
        GuardedSocket socket = new GuardedSocket(Thread.currentThread(), false, false);
        check(owner.publish(token, socket), "idle_test_publish");
        owner.invalidate();
        socket.awaitFinished();
        socket.closeThread.join(3_000);
        check(!socket.closeThread.isAlive(), "idle_closer_thread_must_expire");
    }

    private static void tlsRetirementAlsoReleasesUnderlyingRawSocket() throws Exception {
        cases++;
        PersonalTunProbeSocketOwner owner = new PersonalTunProbeSocketOwner();
        owner.bind();
        PersonalTunProbeSocketOwner.Token token = owner.beginAttempt();
        GuardedSocket raw = new GuardedSocket(Thread.currentThread(), false, false);
        GuardedSocket tls = new GuardedSocket(Thread.currentThread(), false, false);
        check(owner.publish(token, raw), "raw_publish");
        check(owner.publish(token, tls), "tls_publish");
        check(raw.closed.get() == 0, "tls_publication_must_keep_live_tuple");
        owner.invalidate();
        raw.awaitFinished();
        tls.awaitFinished();
        raw.assertOffCallerThread();
        tls.assertOffCallerThread();
        check(raw.closed.get() == 1 && tls.closed.get() == 1, "retire_both_handles_once");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final class GuardedSocket extends Socket {
        private final Thread forbiddenThread;
        private final boolean fail;
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch mayFinish;
        private final CountDownLatch finished = new CountDownLatch(1);
        private final AtomicInteger closed = new AtomicInteger();
        private volatile Thread closeThread;

        GuardedSocket(Thread forbiddenThread, boolean block, boolean fail) {
            this.forbiddenThread = forbiddenThread;
            this.fail = fail;
            mayFinish = new CountDownLatch(block ? 1 : 0);
        }

        @Override public void close() throws IOException {
            closeThread = Thread.currentThread();
            started.countDown();
            if (closeThread == forbiddenThread) {
                finished.countDown();
                throw new AssertionError("socket_close_on_lifecycle_caller");
            }
            try {
                if (!mayFinish.await(5, TimeUnit.SECONDS)) throw new IOException("close_test_timeout");
                closed.incrementAndGet();
                if (fail) throw new IOException("expected_close_failure");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            } finally {
                finished.countDown();
            }
        }

        void release() { mayFinish.countDown(); }
        void awaitStarted() throws Exception { check(started.await(5, TimeUnit.SECONDS), "close_not_started"); }
        void awaitFinished() throws Exception { check(finished.await(5, TimeUnit.SECONDS), "close_not_finished"); }
        void assertOffCallerThread() { check(closeThread != forbiddenThread, "close_thread_identity"); }
    }
}
