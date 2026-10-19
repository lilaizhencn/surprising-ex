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
    private CommittedCommandResult commandResult;

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
        commandResult = null;
        fundingResult = null;
        CoreMessage command = CoreMessageCodec.decode(bytes);
        if (command.header().productLine() != state.productLine())
            throw new IllegalArgumentException("cross-product replay message");
        if (command.header().kind() == WireMessageKind.QUERY) return List.of();
        long dropped = outbox.droppedBatches(), failures = capture.failures();
        capture.begin(logPosition, timestamp, 0, state.realtimeExportSequence());
        try {
            CoreResponse response = state.apply(command, timestamp, logPosition);
            long directDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (response == null && state.directCommand.active() && System.nanoTime() < directDeadline) {
                response = state.directCommand.poll(state);
                if (response == null) Thread.onSpinWait();
            }
            if (response == null && state.directCommand.active()) {
                throw new AssertionError("Direct command timed out in CommittedTradeReplay: " + command.header().messageType());
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            // Only the separate replay waits here. Live owner scheduling is unchanged.
            // No-progress retries are bounded; Matcher/Account Lane still own settlement.
            long idleNanos = 1_000L;
            while (state.pendingMatching.firstSequence() != 0) {
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
            if (command.header().userId() > 0
                    && (response == null || response.resultCode() != CoreResultCode.IDEMPOTENCY_CONFLICT)) {
                // Matching may have returned MATCHING_PENDING before the replay drained its
                // account/ledger commits. Read the final retained result only after that drain.
                // This belongs to the separate export worker, never the live Core callback.
                var retained = state.resultLedger.get(command.header().commandId());
                if (retained == null && response == null)
                    throw new IllegalStateException("committed replay has no command result");
                var status = retained == null ? response.commandStatus() : retained.status();
                var code = retained == null ? response.resultCode() : retained.resultCode();
                long applied = retained == null ? response.appliedCommandCount() : retained.appliedCommandCount();
                if (code == CoreResultCode.MATCHING_PENDING)
                    throw new IllegalStateException("committed replay did not finish the trading command");
                String clientOrderId = null, instrumentId = null;
                if (command.header().messageType() == CoreMessageType.PLACE_ORDER) {
                    try {
                        var order = TradingCommandCodec.decodePlaceOrder(command.payloadUnsafe());
                        clientOrderId = order.clientOrderId();
                        instrumentId = order.instrumentId();
                    } catch (IllegalArgumentException invalidCommand) {
                        // Invalid wire commands still have a definitive committed rejection,
                        // but cannot supply an order identity for a client-ID lookup.
                        if (code != CoreResultCode.INVALID_COMMAND) throw invalidCommand;
                    }
                }
                commandResult = new CommittedCommandResult(state.productLine(), command.header().userId(),
                        command.header().commandId(), command.header().messageType(), clientOrderId, instrumentId,
                        status, code, applied, logPosition, timestamp);
            }
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
    public CommittedCommandResult commandResult() { return commandResult; }

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

    public record CommittedFundingPage(ProductLine product, long clusterPosition, long occurredAt,
                                       ApplyFundingCommand command, CoreFundingProgressView progress,
                                       String resultCode, List<CoreFundingPaymentView> payments) {
        public CommittedFundingPage {
            if (product == null || !product.isFundingProduct() || clusterPosition <= 0 || occurredAt <= 0
                    || command == null || progress == null || progress.settlementId() != command.settlementId()
                    || resultCode == null || payments == null) throw new IllegalArgumentException("invalid committed funding page");
            payments = List.copyOf(payments);
            for (var payment : payments) {
                if (payment.settlementId() != command.settlementId()
                        || !payment.instrumentId().equals(command.instrumentId())
                        || payment.fundingRatePpm() != command.fundingRatePpm())
                    throw new IllegalArgumentException("funding payment does not belong to committed command");
            }
        }
    }

    /** A processing receipt, not another order/account state. Owned by one replay step. */
    public record CommittedCommandResult(ProductLine product, long userId, UUID commandId,
                                         CoreMessageType commandType, String clientOrderId, String instrumentId,
                                         ResponseStatus status, CoreResultCode code, long appliedCommandCount,
                                         long clusterPosition, long occurredAt) {
        public CommittedCommandResult {
            if (product == null || userId <= 0 || commandId == null || commandType == null
                    || commandType.kind() != WireMessageKind.COMMAND
                    || (status != ResponseStatus.APPLIED && status != ResponseStatus.REJECTED) || code == null
                    || code == CoreResultCode.MATCHING_PENDING || appliedCommandCount < 0
                    || clusterPosition <= 0 || occurredAt <= 0)
                throw new IllegalArgumentException("invalid committed command result");
        }
    }
}
