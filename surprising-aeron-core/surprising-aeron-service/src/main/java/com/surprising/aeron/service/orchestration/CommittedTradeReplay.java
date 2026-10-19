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
    private CommittedDeliveryPage deliveryPage;
    private CommittedCommandResult commandResult;

    public CommittedTradeReplay(ProductLine product, byte[] snapshot) {
        state =
                snapshot == null
                        ? new TradingCoreRuntime(product)
                        : TradingCoreRuntime.fromSnapshot(product, snapshot);
        state.assertClusterCallbackComplete();
        capture = state.attachRealtime(outbox);
        capture.orderProjectionOnly(true);
        state.funding.observeReplayPayments(result -> fundingResult = result);
    }

    public List<RealtimeFrame> apply(byte[] bytes, long timestamp, long logPosition) {
        fundingPage = null;
        deliveryPage = null;
        commandResult = null;
        fundingResult = null;
        CoreMessage command = CoreMessageCodec.decode(bytes);
        if (command.header().productLine() != state.productLine())
            throw new IllegalArgumentException("cross-product replay message");
        if (command.header().kind() == WireMessageKind.QUERY) return List.of();
        var delivery = deliveryCommand(command);
        var deliveryBalances = delivery == null ? List.<BeforeDeliveryBalance>of() : deliveryBalances(delivery, timestamp, logPosition);
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
            if (command.header().userId() > 0) {
                // Matching may have returned MATCHING_PENDING before the replay drained its
                // account/ledger commits. Read the final retained result only after that drain.
                // This belongs to the separate export worker, never the live Core callback.
                // A conflicting retry is a rejected attempt, while the original receipt remains unchanged.
                var retained = response != null && response.resultCode() == CoreResultCode.IDEMPOTENCY_CONFLICT
                        ? null : state.resultLedger.get(command.header().commandId());
                if (retained == null && response == null)
                    throw new IllegalStateException("committed replay has no command result");
                var status = retained == null ? response.commandStatus() : retained.status();
                var code = retained == null ? response.resultCode() : retained.resultCode();
                long applied = retained == null ? response.appliedCommandCount() : retained.appliedCommandCount();
                if (code == CoreResultCode.MATCHING_PENDING)
                    throw new IllegalStateException("committed replay did not finish the trading command");
                String clientOrderId = null, instrumentId = null;
                if (command.header().messageType() == CoreMessageType.PLACE_ORDER && code != CoreResultCode.IDEMPOTENCY_CONFLICT) {
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
                List<CancelBatchItem> cancelResults = List.of();
                if (command.header().messageType() == CoreMessageType.CANCEL_ORDER_BATCH && code != CoreResultCode.IDEMPOTENCY_CONFLICT) {
                    List<CancelOrderCommand> requested;
                    try { requested = TradingOrderBatchCodec.decodeCancelOrderBatch(command.payloadUnsafe()).orders(); }
                    catch (IllegalArgumentException invalid) {
                        if (code != CoreResultCode.INVALID_COMMAND) throw invalid;
                        requested = List.of();
                    }
                    var items = new ArrayList<CancelBatchItem>(requested.size());
                    if (status == ResponseStatus.APPLIED && !requested.isEmpty()) {
                        var result = TradingOrderBatchCodec.decodeResult(retained == null ? response.data() : retained.responseData());
                        if (result.items().size() != requested.size()) throw new IllegalStateException("incomplete committed cancel batch");
                        for (var item : result.items()) {
                            long orderId = requested.get(item.index()).orderId();
                            if (item.orderId() != orderId) throw new IllegalStateException("committed cancel batch identity mismatch");
                            items.add(new CancelBatchItem(item.index(), orderId, item.status() == ResponseStatus.APPLIED, item.resultCode().name()));
                        }
                    } else {
                        for (int i = 0; i < requested.size(); i++)
                            items.add(new CancelBatchItem(i, requested.get(i).orderId(), false, code.name()));
                    }
                    cancelResults = List.copyOf(items);
                }
                commandResult = new CommittedCommandResult(state.productLine(), command.header().userId(),
                        command.header().commandId(), command.header().messageType(), clientOrderId, instrumentId,
                        status, code, applied, logPosition, timestamp, cancelResults);
            }
            if (fundingResult != null) {
                if (response.commandStatus() != ResponseStatus.APPLIED)
                    throw new IllegalStateException("funding payments without an applied committed command");
                fundingPage = new CommittedFundingPage(state.productLine(), logPosition, timestamp,
                        TradingCommandCodec.decodeApplyFunding(command.payloadUnsafe()), fundingResult.progress(),
                        response.resultCode().name(), fundingResult.payments());
                fundingResult = null;
            }
            var retainedDelivery = delivery == null ? null : state.resultLedger.get(command.header().commandId());
            var deliveryStatus = retainedDelivery == null ? response.commandStatus() : retainedDelivery.status();
            if (delivery != null && deliveryStatus == ResponseStatus.APPLIED) {
                var flows = new ArrayList<DeliveryCashFlow>();
                for (var before : deliveryBalances) {
                    long total = deliveryBalance(before.userId(), before.asset(), timestamp, logPosition);
                    long amount = Math.subtractExact(total, before.totalUnits());
                    if (amount != 0) flows.add(new DeliveryCashFlow(before.userId(), before.asset(), amount, total));
                }
                if (!flows.isEmpty()) deliveryPage = new CommittedDeliveryPage(state.productLine(), logPosition, timestamp, delivery, flows);
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
                        || frame.kind() == RealtimeFrame.Kind.EXECUTION || frame.kind() == RealtimeFrame.Kind.TRIGGER)
                    changes.add(frame);
            }
            return List.copyOf(changes);
        } finally {
            capture.abort();
        }
    }

    public CommittedFundingPage fundingPage() { return fundingPage; }
    public CommittedDeliveryPage deliveryPage() { return deliveryPage; }
    public CommittedCommandResult commandResult() { return commandResult; }

    /** Startup projection reconstruction from this worker's committed snapshot, never the live Core. */
    public List<CoreTriggerOrderStateView> retainedTriggersBefore(long before, int limit) {
        if (before < 0 || limit < 1 || limit > 512) throw new IllegalArgumentException("invalid retained trigger page");
        long now = System.currentTimeMillis();
        return CoreTriggerOrderCodec.decodeList(replayQuery(CoreMessageType.TRIGGER_ORDER_QUERY, 0,
                CoreTriggerOrderCodec.encodeQuery(new CoreTriggerOrderQuery(0, "", before, limit)), now, 1).data());
    }

    private SettleInstrumentCommand deliveryCommand(CoreMessage message) {
        if (!state.productLine().isDeliveryProduct() || message.header().messageType() != CoreMessageType.SETTLE_INSTRUMENT) return null;
        try { return TradingCommandCodec.decodeSettleInstrument(message.payloadUnsafe()); }
        catch (RuntimeException malformed) { return null; } // Authoritative replay still records the Core rejection below.
    }

    /** 与结算命令共用全局 user cursor；仅在独立导出进程复制本页受影响用户的三个标量。
     * 余额使用可用+冻结总额，释放委托/持仓保证金不会伪造现金收入；保险补偿已包含在真实已提交差额中。 */
    private List<BeforeDeliveryBalance> deliveryBalances(SettleInstrumentCommand command, long timestamp, long position) {
        var runtime = state.runtimeState();
        var instrument = runtime.instrument(command.instrumentId());
        if (instrument == null) return List.of();
        var balances = new ArrayList<BeforeDeliveryBalance>();
        long cursor = command.cursorUserId();
        while (balances.size() < command.maxUsers()) {
            var page = CoreMaintenanceCodec.decodePage(replayQuery(CoreMessageType.INSTRUMENT_MAINTENANCE_QUERY, 0,
                    CoreMaintenanceCodec.encodeQuery(new CoreMaintenanceCodec.Query(command.instrumentId(), cursor,
                            Math.min(32, command.maxUsers() - balances.size()))), timestamp, position).data());
            for (long user : page.userIds()) {
                if (user <= cursor) throw new IllegalStateException("nonadvancing committed delivery user page");
                balances.add(new BeforeDeliveryBalance(user, instrument.settleAsset(), deliveryBalance(user, instrument.settleAsset(), timestamp, position)));
                cursor = user;
            }
            if (!page.hasMore()) break;
            if (page.userIds().isEmpty()) throw new IllegalStateException("empty committed delivery user cursor");
        }
        return List.copyOf(balances);
    }

    private long deliveryBalance(long user, String asset, long timestamp, long position) {
        var view = CoreStateQueryCodec.decodeUserState(replayQuery(CoreMessageType.USER_STATE_QUERY, user, new byte[0], timestamp, position).data());
        if (view.userId() != user || view.productLine() != state.productLine())
            throw new IllegalStateException("committed delivery account identity mismatch");
        var balance = view.balances().stream().filter(value -> value.asset().equals(asset)).findFirst()
                .orElseThrow(() -> new IllegalStateException("delivery position has no settlement balance"));
        return Math.addExact(balance.availableUnits(), balance.lockedUnits());
    }

    /** 只查询本进程的独立重放状态，沿既有所有者派发与结果通道读取，绝不连接在线交易 Core。 */
    private CoreResponse replayQuery(CoreMessageType type, long user, byte[] payload, long timestamp, long position) {
        var request = new CoreMessage(CoreMessageHeader.query(type, UUID.randomUUID(), state.productLine(),
                CommandSource.OPERATIONS, 0, timestamp, user, timestamp, timestamp), payload);
        var response = state.apply(request, timestamp, position);
        if (response.resultCode() == CoreResultCode.MATCHING_PENDING) {
            long id = state.bookQueries.querySequence(request.header().commandId());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((response = state.bookQueries.takeQueryResult(id)) == null) {
                state.assertHealthy();
                if (System.nanoTime() >= deadline) throw new IllegalStateException("committed delivery read timeout; checkpoint must not advance");
                java.util.concurrent.locks.LockSupport.parkNanos(1_000L);
            }
        }
        if (response.status() != ResponseStatus.OK) throw new IllegalStateException("committed delivery read failed: " + response.resultCode());
        state.assertClusterCallbackComplete();
        return response;
    }

    private record BeforeDeliveryBalance(long userId, String asset, long totalUnits) {}

    /** Only settlement cash changes observed in this bounded replay page; no live Core state copy. */
    public record CommittedDeliveryPage(ProductLine product, long clusterPosition, long occurredAt,
                                         SettleInstrumentCommand command, List<DeliveryCashFlow> cashFlows) {
        public CommittedDeliveryPage {
            cashFlows = List.copyOf(cashFlows);
            if (product == null || !product.isDeliveryProduct() || clusterPosition <= 0 || occurredAt <= 0
                    || command == null || cashFlows.isEmpty() || cashFlows.size() > command.maxUsers())
                throw new IllegalArgumentException("invalid committed delivery cash page");
            var users = new HashSet<Long>();
            for (var flow : cashFlows) if (!users.add(flow.userId()))
                throw new IllegalArgumentException("duplicate delivery cash user");
        }
    }

    public record DeliveryCashFlow(long userId, String asset, long amountUnits, long balanceAfterUnits) {
        public DeliveryCashFlow {
            if (userId <= 0 || asset == null || asset.isBlank() || amountUnits == 0 || balanceAfterUnits < 0)
                throw new IllegalArgumentException("invalid delivery cash flow");
        }
    }

    public long exportSequence() {
        return state.realtimeExportSequence();
    }

    public com.surprising.aeron.service.state.CommittedTradingActivity currentTradingActivity(long now) {
        state.assertClusterCallbackComplete();
        return com.surprising.aeron.service.state.CommittedTradingActivity.capture(state.runtimeState, state.identities, now);
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

    /** Final outcome of one committed attempt. Conflicting retries are counted but never replace the original receipt. */
    public record CommittedCommandResult(ProductLine product, long userId, UUID commandId,
                                         CoreMessageType commandType, String clientOrderId, String instrumentId,
                                         ResponseStatus status, CoreResultCode code, long appliedCommandCount,
                                         long clusterPosition, long occurredAt, List<CancelBatchItem> cancelResults) {
        public CommittedCommandResult {
            cancelResults = List.copyOf(cancelResults);
            if (cancelResults.size() > 50 || (!cancelResults.isEmpty() && commandType != CoreMessageType.CANCEL_ORDER_BATCH))
                throw new IllegalArgumentException("invalid committed cancellation items");
            for (int i = 0; i < cancelResults.size(); i++)
                if (cancelResults.get(i).index() != i) throw new IllegalArgumentException("noncontiguous cancellation items");
            if (product == null || userId <= 0 || commandId == null || commandType == null
                    || commandType.kind() != WireMessageKind.COMMAND
                    || (status != ResponseStatus.APPLIED && status != ResponseStatus.REJECTED) || code == null
                    || code == CoreResultCode.MATCHING_PENDING || appliedCommandCount < 0
                    || clusterPosition <= 0 || occurredAt <= 0)
                throw new IllegalArgumentException("invalid committed command result");
        }
    }

    /** Bounded command outcome facts; never another order-state image. */
    public record CancelBatchItem(int index, long orderId, boolean success, String code) {
        public CancelBatchItem {
            if (index < 0 || orderId <= 0 || code == null || code.isBlank()
                    || CoreResultCode.valueOf(code) == CoreResultCode.MATCHING_PENDING
                    || success != (CoreResultCode.valueOf(code) == CoreResultCode.NONE))
                throw new IllegalArgumentException("invalid cancellation result item");
        }
    }
}
