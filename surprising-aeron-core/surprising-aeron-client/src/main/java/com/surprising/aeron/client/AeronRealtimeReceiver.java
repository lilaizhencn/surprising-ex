package com.surprising.aeron.client;

import com.surprising.aeron.protocol.RealtimeFrame;
import com.surprising.aeron.protocol.RealtimeFrameCodec;
import io.aeron.Aeron;
import io.aeron.FragmentAssembler;
import org.agrona.concurrent.BackoffIdleStrategy;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/** Owns its subscription and worker; continuity failures require the consumer's baseline recovery. */
public final class AeronRealtimeReceiver implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(AeronRealtimeReceiver.class.getName());
    public enum FailureKind { DECODE, HANDLER, TRANSPORT }
    /** Metadata only: no account payload or token is retained in diagnostics. */
    public record Failure(FailureKind kind, String product, String frameKind, long sequence, int ordinal) {}

    private final Thread worker;
    private final Consumer<RealtimeFrame> handler;
    private final Consumer<Failure> failureHandler;
    private volatile boolean running = true;
    private volatile boolean transportReady;
    private volatile boolean recoveryRequired;
    private final Object recoveryLock = new Object();
    private volatile long recoveryVersion;
    private volatile Aeron activeAeron;
    private final LongAdder decodeFailures = new LongAdder();
    private final LongAdder handlerFailures = new LongAdder();
    private final LongAdder transportFailures = new LongAdder();
    private final AtomicLong nextDiagnosticNanos = new AtomicLong();

    public AeronRealtimeReceiver(String directory, String channel, int streamId, Consumer<RealtimeFrame> handler) {
        this(directory, channel, streamId, handler, null);
    }

    public AeronRealtimeReceiver(String directory, String channel, int streamId,
                                 Consumer<RealtimeFrame> handler, Consumer<Failure> failureHandler) {
        this.handler = Objects.requireNonNull(handler, "handler");
        this.failureHandler = failureHandler;
        worker = Thread.ofPlatform().name("realtime-aeron-receiver").unstarted(() -> receive(directory, channel, streamId));
        worker.start();
    }

    private void receive(String directory, String channel, int streamId) {
        try {
            while (running) {
                try (var aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(directory)
                        .errorHandler(this::transportFailed))) {
                    activeAeron = aeron;
                    if (!running) break;
                    var assembler = new FragmentAssembler((buffer, offset, length, header) -> {
                        if (!running || recoveryRequired) return;
                        RealtimeFrame frame;
                        try {
                            if (length > RealtimeFrameCodec.MAX_FRAME_BYTES)
                                throw new IllegalArgumentException("oversized realtime message");
                            ByteBuffer bytes = buffer.byteBuffer() != null
                                    ? buffer.byteBuffer().duplicate() : ByteBuffer.wrap(buffer.byteArray());
                            int start = Math.addExact(buffer.wrapAdjustment(), offset);
                            bytes.position(start).limit(Math.addExact(start, length));
                            frame = RealtimeFrameCodec.decode(bytes);
                        } catch (RuntimeException failure) {
                            failed(FailureKind.DECODE, null, failure);
                            return;
                        }
                        try {
                            handler.accept(frame);
                        } catch (RuntimeException failure) {
                            failed(FailureKind.HANDLER, frame, failure);
                        }
                    });
                    try (var subscription = aeron.addSubscription(channel, streamId, null,
                            unavailable -> {
                                synchronized (assembler) { assembler.freeSessionBuffer(unavailable.sessionId()); }
                                if (running) transportFailed(new IllegalStateException("realtime source image unavailable"));
                            })) {
                        var idle = new BackoffIdleStrategy(0, 0, 100_000L, 250_000L);
                        transportReady = true;
                        while (running && transportReady && !subscription.isClosed() && !aeron.isClosed()) {
                            if (recoveryRequired) { idle.idle(0); continue; }
                            int fragments;
                            synchronized (assembler) { fragments = subscription.poll(assembler, 64); }
                            idle.idle(fragments);
                        }
                    }
                } catch (RuntimeException failure) {
                    if (running) {
                        failed(FailureKind.TRANSPORT, null, failure);
                        LockSupport.parkNanos(1_000_000_000L);
                    }
                } finally {
                    activeAeron = null;
                    transportReady = false;
                }
            }
        } finally {
            transportReady = false;
        }
    }

    public boolean ready() { return running && transportReady && !recoveryRequired; }
    public boolean recoveryRequired() { return recoveryRequired; }
    /** Called only after downstream subscriptions and baselines have been invalidated. */
    public long recoveryVersion() { return recoveryVersion; }
    public boolean resumeAfterRecovery(long recoveredVersion) {
        synchronized (recoveryLock) {
            if (recoveryVersion != recoveredVersion) return false;
            recoveryRequired = false;
            return true;
        }
    }
    public long failures() { return decodeFailures() + handlerFailures() + transportFailures(); }
    public long decodeFailures() { return decodeFailures.sum(); }
    public long handlerFailures() { return handlerFailures.sum(); }
    public long transportFailures() { return transportFailures.sum(); }
    public boolean terminated() { return !worker.isAlive(); }

    void transportFailed(Throwable failure) {
        transportReady = false;
        if (running) failed(FailureKind.TRANSPORT, null, failure);
    }

    private void failed(FailureKind kind, RealtimeFrame frame, Throwable failure) {
        switch (kind) {
            case DECODE -> decodeFailures.increment();
            case HANDLER -> handlerFailures.increment();
            case TRANSPORT -> transportFailures.increment();
        }
        var context = new Failure(kind, frame == null ? "unknown" : frame.productLine().name(),
                frame == null ? "unknown" : frame.kind().name(), frame == null ? -1 : frame.sequence(),
                frame == null ? -1 : frame.ordinal());
        if (failureHandler != null) {
            synchronized (recoveryLock) {
                recoveryRequired = true;
                recoveryVersion++;
            }
            try { failureHandler.accept(context); }
            catch (RuntimeException notificationFailure) {
                if (notificationFailure != failure) failure.addSuppressed(notificationFailure);
            }
        }
        long now = System.nanoTime();
        long next = nextDiagnosticNanos.get();
        if ((next == 0 || now >= next) && nextDiagnosticNanos.compareAndSet(next, now + 5_000_000_000L)) {
            String stack = java.util.Arrays.stream(failure.getStackTrace()).limit(8)
                    .map(StackTraceElement::toString).collect(java.util.stream.Collectors.joining(" <- "));
            LOG.log(System.Logger.Level.WARNING, "Realtime failure " + context + "; cause="
                    + failure.getClass().getSimpleName() + "; at=" + stack);
        }
    }

    @Override
    public void close() {
        running = false;
        transportReady = false;
        worker.interrupt();
        Aeron aeron = activeAeron;
        RuntimeException closeFailure = null;
        if (aeron != null) {
            try { aeron.close(); }
            catch (RuntimeException failure) { closeFailure = failure; }
        }
        if (Thread.currentThread() == worker)
            throw new IllegalStateException("receiver cannot join its own worker");
        boolean interrupted = false;
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (worker.isAlive() && System.nanoTime() < deadline) {
            try { worker.join(100); }
            catch (InterruptedException failure) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (worker.isAlive()) throw new IllegalStateException("realtime receiver did not terminate within 5 seconds", closeFailure);
        if (closeFailure != null) throw closeFailure;
    }
}
