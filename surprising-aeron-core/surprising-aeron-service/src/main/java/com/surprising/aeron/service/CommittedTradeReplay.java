package com.surprising.aeron.service;

import com.surprising.aeron.client.RealtimeOutbox;
import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;

import java.util.*;
import java.util.concurrent.TimeUnit;

/** Separate-process deterministic replay. Never used by the trading service's live callback. */
public final class CommittedTradeReplay implements AutoCloseable {
    private final CoreProbeState state;
    private final RealtimeOutbox outbox = new RealtimeOutbox(65536, 32 * 1024 * 1024);
    private final com.surprising.aeron.service.state.RealtimeStateCapture capture;

    public CommittedTradeReplay(ProductLine product, byte[] snapshot) {
        state =
                snapshot == null
                        ? new CoreProbeState(product)
                        : CoreProbeState.fromSnapshot(product, snapshot);
        state.assertClusterCallbackComplete();
        capture = state.attachRealtime(outbox);
        capture.tradesOnly(true);
    }

    public List<RealtimeFrame> apply(byte[] bytes, long timestamp, long logPosition) {
        CoreMessage command = CoreMessageCodec.decode(bytes);
        if (command.header().productLine() != state.productLine())
            throw new IllegalArgumentException("cross-product replay message");
        if (command.header().kind() == WireMessageKind.QUERY) return List.of();
        long dropped = outbox.droppedBatches(), failures = capture.failures();
        capture.begin(logPosition, timestamp, 0, state.realtimeExportSequence());
        try {
            state.assertClusterCallbackComplete();
            state.apply(command, timestamp, logPosition);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (state.firstPendingMatchingSequence() != 0) {
                if (state.commitReadyMatching(
                                64, timestamp, logPosition, false, (sequence, response) -> {})
                        == 0) Thread.onSpinWait();
                if (System.nanoTime() > deadline)
                    throw new IllegalStateException("committed replay matching timed out");
            }
            state.assertClusterCallbackComplete();
            capture.commit(state.realtimeExportSequence());
            if (outbox.droppedBatches() != dropped || capture.failures() != failures)
                throw new IllegalStateException(
                        "reliable replay event capture incomplete; checkpoint must not advance");
            var trades = new ArrayList<RealtimeFrame>();
            byte[] encoded;
            while ((encoded = outbox.poll()) != null) {
                var frame = RealtimeFrameCodec.decode(encoded);
                if (frame.kind() == RealtimeFrame.Kind.TRADE) trades.add(frame);
            }
            return List.copyOf(trades);
        } finally {
            capture.abort();
        }
    }

    public byte[] snapshot() {
        state.assertClusterCallbackComplete();
        return state.snapshot();
    }

    public long businessHash() {
        return state.snapshotBusinessStateHash();
    }

    @Override
    public void close() {
        state.close();
    }
}
