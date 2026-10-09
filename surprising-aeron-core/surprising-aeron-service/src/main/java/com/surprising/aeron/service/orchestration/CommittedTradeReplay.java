package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.client.RealtimeOutbox;
import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;

import java.util.*;
import java.util.concurrent.TimeUnit;

/** Separate-process deterministic replay. Never used by the trading service's live callback. */
public final class CommittedTradeReplay implements AutoCloseable {
    private final TradingCoreRuntime state;
    private final RealtimeOutbox outbox = new RealtimeOutbox(65536, 32 * 1024 * 1024);
    private final com.surprising.aeron.service.state.realtime.RealtimeStateCapture capture;
    private com.surprising.aeron.service.state.RuntimePerpetualFundingProcessor.FundingResult fundingResult;
    private CommittedFundingPage fundingPage;

    public CommittedTradeReplay(ProductLine product, byte[] snapshot) {
        state =
                snapshot == null
                        ? new TradingCoreRuntime(product)
                        : TradingCoreRuntime.fromSnapshot(product, snapshot);
        state.assertClusterCallbackComplete();
        capture = state.attachRealtime(outbox);
        capture.ordersAndTradesOnly(true);
        state.funding.observeReplayPayments(result -> fundingResult = result);
    }

    public List<RealtimeFrame> apply(byte[] bytes, long timestamp, long logPosition) {
        fundingPage = null;
        fundingResult = null;
        CoreMessage command = CoreMessageCodec.decode(bytes);
        if (command.header().productLine() != state.productLine())
            throw new IllegalArgumentException("cross-product replay message");
        if (command.header().kind() == WireMessageKind.QUERY) return List.of();
        long dropped = outbox.droppedBatches(), failures = capture.failures();
        capture.begin(logPosition, timestamp, 0, state.realtimeExportSequence(),
                command.header().traceId().isEmpty() ? command.header().commandId().toString() : command.header().traceId());
        try {
            state.assertClusterCallbackComplete();
            CoreResponse response = state.apply(command, timestamp, logPosition);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            // Only the separate replay waits here. Live owner scheduling is unchanged.
            // No-progress retries are bounded; Matcher/Account Lane still own settlement.
            long idleNanos = 1_000L;
            while (state.firstPendingMatchingSequence() != 0) {
                if (Thread.currentThread().isInterrupted())
                    throw new IllegalStateException("committed replay interrupted; checkpoint must not advance");
                if (state.commits.commitReadyMatching(
                                64, timestamp, logPosition, false, (sequence, matchingResponse) -> {}) == 0) {
                    java.util.concurrent.locks.LockSupport.parkNanos(idleNanos);
                    idleNanos = Math.min(idleNanos * 2, 100_000L);
                } else idleNanos = 1_000L;
                if (System.nanoTime() > deadline)
                    throw new IllegalStateException("committed replay matching timed out");
            }
            state.assertClusterCallbackComplete();
            if (fundingResult != null) {
                if (response.commandStatus() != ResponseStatus.APPLIED)
                    throw new IllegalStateException("funding payments without an applied committed command");
                fundingPage = new CommittedFundingPage(state.productLine(), logPosition, timestamp,
                        TradingCommandCodec.decodeApplyFunding(command.payloadUnsafe()), fundingResult.progress(),
                        response.resultCode().name(), fundingResult.payments());
                fundingResult = null;
            }
            capture.commit(state.realtimeExportSequence());
            if (outbox.droppedBatches() != dropped || capture.failures() != failures)
                throw new IllegalStateException(
                        "reliable replay event capture incomplete; checkpoint must not advance");
            var changes = new ArrayList<RealtimeFrame>();
            byte[] encoded;
            while ((encoded = outbox.poll()) != null) {
                var frame = RealtimeFrameCodec.decode(encoded);
                if (frame.kind() == RealtimeFrame.Kind.TRADE || frame.kind() == RealtimeFrame.Kind.ORDER
                        || frame.kind() == RealtimeFrame.Kind.EXECUTION)
                    changes.add(frame);
            }
            return List.copyOf(changes);
        } finally {
            capture.abort();
        }
    }

    public CommittedFundingPage fundingPage() { return fundingPage; }

    public long exportSequence() {
        return state.realtimeExportSequence();
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
