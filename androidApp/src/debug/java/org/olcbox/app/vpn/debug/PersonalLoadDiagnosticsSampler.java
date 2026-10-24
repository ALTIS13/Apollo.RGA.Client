package org.olcbox.app.vpn.debug;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Debug-only, owner-local pull sampler. No tunnel work runs here. */
// ai-generated: finite owner-local sampler, never holds a lifecycle lock during read/log.
final class PersonalLoadDiagnosticsSampler {
    private final AtomicReference<Owner> current = new AtomicReference<>();

    void start(boolean enabled, BooleanSupplier gate, Supplier<String> read, Consumer<String> sink) {
        if (!enabled) { stop(); return; }
        Owner owner = new Owner(gate, read, sink);
        Owner previous = current.getAndSet(owner);
        if (previous != null) previous.close();
        owner.schedule();
    }

    void stop() {
        Owner owner = current.getAndSet(null);
        if (owner != null) owner.close();
    }

    boolean isSampling() { return current.get() != null; }

    private final class Owner {
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final BooleanSupplier gate;
        private final Supplier<String> read;
        private final Consumer<String> sink;
        private final ScheduledExecutorService executor;
        private volatile ScheduledFuture<?> future;

        Owner(BooleanSupplier gate, Supplier<String> read, Consumer<String> sink) {
            this.gate = gate; this.read = read; this.sink = sink;
            executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "RgaPersonalLoad");
                thread.setDaemon(true);
                return thread;
            });
        }

        void schedule() {
            try {
                future = executor.scheduleWithFixedDelay(this::tick, 0, 1, TimeUnit.SECONDS);
                if (!active.get()) future.cancel(true);
            } catch (RejectedExecutionException stopped) {
                retire();
            }
        }

        void tick() {
            if (!active.get()) return;
            try {
                if (!gate.getAsBoolean()) { retire(); return; }
                String snapshot = read.get();
                if (!active.get()) return;
                if (snapshot == null || snapshot.isEmpty()) { retire(); return; }
                for (String row : snapshot.split("\n")) {
                    if (!active.get()) return;
                    sink.accept(row);
                }
            } catch (RuntimeException unavailable) {
                retire(); // No arbitrary exception text or object is logged.
            }
        }

        void retire() {
            current.compareAndSet(this, null); // Late old unwind never detaches a new owner.
            close();
        }

        void close() {
            active.set(false);
            ScheduledFuture<?> scheduled = future;
            if (scheduled != null) scheduled.cancel(true);
            executor.shutdownNow(); // Never await a potentially blocked external sink.
        }
    }
}
