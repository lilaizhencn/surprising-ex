package com.surprising.aeron.client;

import com.surprising.aeron.protocol.RealtimeFrame;
import com.surprising.aeron.protocol.RealtimeFrameCodec;
import io.aeron.Aeron;
import io.aeron.FragmentAssembler;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/** Owns the subscription; fragment buffers are released when their source image disappears. */
public final class AeronRealtimeReceiver implements AutoCloseable {
    private final Thread worker;
    private volatile boolean running = true;
    private volatile boolean ready;
    private final LongAdder failures = new LongAdder();

    public AeronRealtimeReceiver(String directory, String channel, int streamId, Consumer<RealtimeFrame> handler) {
        worker = Thread.ofPlatform().name("realtime-aeron-receiver").unstarted(() -> {
            while (running) {
                try (var aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(directory).driverTimeoutMs(1000)
                        .errorHandler(failure -> { ready = false; failures.increment(); }))) {
                    var assembler = new FragmentAssembler((buffer, offset, length, header) -> {
                        try {
                            if (length > RealtimeFrameCodec.MAX_FRAME_BYTES) {
                                throw new IllegalArgumentException("oversized realtime message");
                            }
                            byte[] bytes = new byte[length];
                            buffer.getBytes(offset, bytes);
                            handler.accept(RealtimeFrameCodec.decode(bytes));
                        } catch (RuntimeException failure) {
                            failures.increment();
                        }
                    });
                    try (var subscription = aeron.addSubscription(channel, streamId, null,
                            unavailable -> { synchronized (assembler) { assembler.freeSessionBuffer(unavailable.sessionId()); } })) {
                        ready = true;
                        while (running && !subscription.isClosed() && !aeron.isClosed()) {
                            int fragments;
                            synchronized (assembler) { fragments = subscription.poll(assembler, 64); }
                            if (fragments == 0) LockSupport.parkNanos(100_000);
                        }
                    }
                } catch (RuntimeException failure) {
                    ready = false;
                    failures.increment();
                    if (running) LockSupport.parkNanos(1_000_000_000L);
                } finally {
                    ready = false;
                }
            }
        });
        worker.start();
    }

    public boolean ready() { return ready; }
    public long failures() { return failures.sum(); }

    @Override
    public void close() {
        running = false;
        worker.interrupt();
        try {
            worker.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
