package com.surprising.aeron.service.replay;

import com.surprising.aeron.service.realtime.TradingRealtimeBoundary;

import com.surprising.aeron.client.RealtimeOutbox;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.realtime.TradingRealtimeBoundary.StateCapture.LiquidationChange;
import com.surprising.product.api.ProductLine;
import java.util.*;
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import lombok.experimental.Accessors;

/** 独立导出工作进程使用的确定性交易回放副本。 */
public final class CommittedTradeReplay implements AutoCloseable {
    private final TradingCoreRuntime state;
    private final RealtimeOutbox outbox = new RealtimeOutbox(65536, 32 * 1024 * 1024);
    private final com.surprising.aeron.service.realtime.TradingRealtimeBoundary.StateCapture capture;
    private com.surprising.aeron.service.funding.FundingSettlementService.FundingResult fundingResult;
    @Accessors(fluent = true, chain = false)
    @Getter
    private CommittedFundingPage fundingPage;
    @Accessors(fluent = true, chain = false)
    @Getter
    private CommittedDeliveryPage deliveryPage;
    @Accessors(fluent = true, chain = false)
    @Getter
    private CommittedCommandResult commandResult;
    @Accessors(fluent = true, chain = false)
    @Getter
    private CommittedBalanceFlow balanceFlow;
    private final Map<Long, Long> algoByChild = new HashMap<>();
    @Accessors(fluent = true, chain = false)
    @Getter
    private List<CoreAlgoOrderView> algoChanges = List.of();
    private final ArrayList<LiquidationChange> liquidationChanges = new ArrayList<>();

    /** 当前 apply 已完成的强平变化。 */
    public List<LiquidationChange> liquidationChanges() { return List.copyOf(liquidationChanges); }

    /** 创建指定产品线空运行时或从快照恢复，挂载只采集订单投影的实时捕获器与资金费观察器，再遍历已有算法父单重建子单路由。 */
    public CommittedTradeReplay(ProductLine product, byte[] snapshot) {
        state =
                snapshot == null
                        ? new TradingCoreRuntime(product)
                        : TradingCoreRuntime.fromSnapshot(product, snapshot);
        state.assertClusterCallbackComplete();
        capture = state.attachRealtime(outbox);
        capture.orderProjectionOnly(true);
        capture.liquidationObserver(liquidationChanges::add);
        state.observeReplayFundingPayments(result -> fundingResult = result);
        forEachRetainedAlgo(ignored -> {});
    }

    /** 清上次导出结果后解码，拒绝跨产品帧、跳过外部查询。捕获丢失或未完成即报错，调用方不得前移检查点。finally 只中止捕获，不承诺回滚已执行副本。 */
    public List<RealtimeFrame> apply(byte[] bytes, long timestamp, long logPosition) {
        algoChanges = List.of();
        liquidationChanges.clear();
        fundingPage = null;
        deliveryPage = null;
        commandResult = null;
        balanceFlow = null;
        fundingResult = null;
        CoreMessage command = CoreMessageCodec.decode(bytes);
        if (command.header().productLine() != state.productLine())
            throw new IllegalArgumentException("cross-product replay message");
        if (command.header().kind() == WireMessageKind.QUERY) return List.of();
        var balanceTarget = balanceFlowTarget(command);
        var beforeBalance = balanceTarget == null ? null
                : balanceUnits(balanceTarget.userId(), balanceTarget.asset());
        var delivery = deliveryCommand(command);
        var deliveryBalances = delivery == null ? List.<BeforeDeliveryBalance>of() : deliveryBalances(delivery, timestamp, logPosition);
        long dropped = outbox.droppedBatches(), failures = capture.failures();
        capture.begin(logPosition, timestamp, 0, state.realtimeExportSequence());
        try {
            CoreResponse response = state.apply(command, timestamp, logPosition);
            long directDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (response == null && state.directCommand.active() && System.nanoTime() < directDeadline) {
                response = state.directCommand.poll();
                if (response == null) Thread.onSpinWait();
            }
            if (response == null && state.directCommand.active()) {
                throw new AssertionError("Direct command timed out in CommittedTradeReplay: " + command.header().messageType());
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            long idleNanos = 1_000L;
            while (state.pendingMatching().firstSequence() != 0) {
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
                var retained = response != null && response.resultCode() == CoreResultCode.IDEMPOTENCY_CONFLICT
                        ? null : state.resultLedger().get(command.header().commandId());
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
                        // 畸形帧仍有明确的已提交拒绝结果，但不能提供可信的订单客户号或币对查询身份。
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
            if (balanceTarget != null && commandResult != null
                    && commandResult.status() == ResponseStatus.APPLIED) {
                var afterBalance = balanceUnits(balanceTarget.userId(), balanceTarget.asset());
                var delta = afterBalance.subtract(beforeBalance);
                if (delta.signum() != 0 || command.header().messageType() == CoreMessageType.ADJUST_BALANCE
                        && response != null && response.status() == ResponseStatus.APPLIED)
                    balanceFlow = new CommittedBalanceFlow(state.productLine(), balanceTarget.userId(),
                            command.header().commandId(), balanceTarget.asset(), delta.toString(), afterBalance.toString(),
                            logPosition, timestamp, balanceTarget.transfer());
            }
            if (fundingResult != null) {
                if (response.commandStatus() != ResponseStatus.APPLIED)
                    throw new IllegalStateException("funding payments without an applied committed command");
                fundingPage = new CommittedFundingPage(state.productLine(), logPosition, timestamp,
                        TradingCommandCodec.decodeApplyFunding(command.payloadUnsafe()), fundingResult.progress(),
                        response.resultCode().name(), fundingResult.payments());
                fundingResult = null;
            }
            var retainedDelivery = delivery == null ? null : state.resultLedger().get(command.header().commandId());
            var deliveryStatus = retainedDelivery == null ? response.commandStatus() : retainedDelivery.status();
            if (delivery != null && deliveryStatus == ResponseStatus.APPLIED) {
                var flows = new ArrayList<DeliveryCashFlow>();
                for (var before : deliveryBalances) {
                    long total = deliveryBalance(before.userId(), before.asset());
                    long amount = Math.subtractExact(total, before.totalUnits());
                    if (amount != 0) flows.add(new DeliveryCashFlow(before.userId(), before.asset(), amount, total));
                }
                if (!flows.isEmpty()) deliveryPage = new CommittedDeliveryPage(state.productLine(), timestamp, delivery, flows);
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
            collectAlgoChanges(command, changes);
            return List.copyOf(changes);
        } finally {
            capture.abort();
        }
    }

    /** 读取回放副本当前记录的复制业务时间。 */
    public long clusterTimestamp() { return state.currentClusterTimestamp(); }

    /** Baseline existing retained orders once at exporter startup, before following new commands. */
    public List<CoreTradeFeeObservation> retainedTradeFeeBaselines() {
        var runtime = state.runtimeState();
        boolean acquired = false;
        try {
            if (runtime.accountLanesStarted() && !runtime.ownerLaneAccess()) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!runtime.tryAcquireOwnerLaneAccess()) {
                    if (System.nanoTime() >= deadline) throw new IllegalStateException("fee baseline read timed out");
                    java.util.concurrent.locks.LockSupport.parkNanos(1000L);
                }
                acquired = true;
            }
            var result = new ArrayList<CoreTradeFeeObservation>();
            for (var order : runtime.ordersForSnapshot().values()) {
                result.add(new CoreTradeFeeObservation(order.orderId(), order.userId(), order.instrument().instrumentId(),
                        state.productLine() == ProductLine.SPOT ? order.instrument().quoteAsset() : order.instrument().settleAsset(),
                        order.cumulativeFeeUnits(), order.clusterPosition(), order.updatedAtEpochMillis()));
            }
            return List.copyOf(result);
        } finally {
            if (acquired) runtime.releaseOwnerLaneAccess();
        }
    }

    /** Export worker only: observes authoritative cumulative fees after the original settlement. */
    public List<CoreTradeFeeObservation> tradeFeeObservations(List<RealtimeFrame> changes) {
        var latest = new LinkedHashMap<Long, CoreOrderStateView>();
        for (var frame : changes) {
            if (frame.kind() != RealtimeFrame.Kind.ORDER) continue;
            var order = CoreStateQueryCodec.decodeOrderState(frame.payload());
            if (order.executedQuantitySteps() == 0) continue;
            var prior = latest.get(order.orderId());
            if (prior == null || prior.revision() < order.revision()) latest.put(order.orderId(), order);
        }
        var result = new ArrayList<CoreTradeFeeObservation>();
        for (var order : latest.values()) {
            var instrument = state.runtimeState().instrument(order.instrumentId());
            if (instrument == null) throw new IllegalStateException("committed fee instrument missing");
            result.add(new CoreTradeFeeObservation(order.orderId(), order.userId(), order.instrumentId(),
                    (state.productLine() == ProductLine.SPOT ? instrument.quoteAsset() : instrument.settleAsset()), order.cumulativeFeeUnits(), order.clusterPosition(), order.updatedAtEpochMillis()));
        }
        return List.copyOf(result);
    }

    private BalanceFlowTarget balanceFlowTarget(CoreMessage command) {
        long user = command.header().userId();
        if (user <= 0) return null;
        try {
            return switch (command.header().messageType()) {
                case ADJUST_BALANCE -> new BalanceFlowTarget(user,
                        com.surprising.aeron.model.AssetBalance.normalizeAsset(
                                TradingCommandCodec.decodeBalanceAdjustment(command.payloadUnsafe()).asset()), null);
                case TRANSFER_OUT, TRANSFER_IN -> {
                    var transfer = TradingCommandCodec.decodeTransferFunds(command.payloadUnsafe());
                    long owner = command.header().messageType() == CoreMessageType.TRANSFER_OUT
                            ? transfer.sourceUserId() : transfer.targetUserId();
                    yield owner == user ? new BalanceFlowTarget(user, transfer.asset(), transfer) : null;
                }
                default -> null;
            };
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    /** 在既有 Owner 账户访问边界读单资产可用加冻结总额，以 BigInteger 求和。 */
    private java.math.BigInteger balanceUnits(long user, String asset) {
        Integer id = state.identities().findAssetId(asset);
        if (id == null) return java.math.BigInteger.ZERO;
        var runtime = state.runtimeState();
        boolean needsAccess = runtime.accountLanesStarted() && !runtime.ownerLaneAccess();
        boolean acquired = false;
        try {
            if (needsAccess) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!runtime.tryAcquireOwnerLaneAccess()) {
                    if (System.nanoTime() >= deadline)
                        throw new IllegalStateException("committed balance read timeout; checkpoint must not advance");
                    java.util.concurrent.locks.LockSupport.parkNanos(1_000L);
                }
                acquired = true;
            }
            var balance = runtime.balance(user, id);
            return balance == null ? java.math.BigInteger.ZERO
                    : java.math.BigInteger.valueOf(balance.availableUnits())
                            .add(java.math.BigInteger.valueOf(balance.lockedUnits()));
        } finally {
            if (acquired) runtime.releaseOwnerLaneAccess();
        }
    }

    /** 本次余额或转账命令要比较的用户、资产与可空转账元数据，由辅助解析构造，不另存账户状态。 */
    private record BalanceFlowTarget(long userId, String asset, TransferFundsCommand transfer) {}

    /** 本次已提交尝试的现金净差额及原始事后总额，使用十进制整数字符串。 */
    public record CommittedBalanceFlow(ProductLine product, long userId, UUID commandId, String asset,
                                       String amountUnits, String balanceAfterUnits, long clusterPosition, long occurredAt,
                                       TransferFundsCommand transfer) {}

    /** 显式冷查询本回放副本的组合财务状态，先收集待完成转账双方用户供快照判断。 */
    public CommittedPortfolioSnapshot portfolioSnapshot(long asOf, long maximumMarkAgeMillis) {
        var pendingUsers = new HashSet<Long>();
        state.pendingTransfers().values().forEach(transfer -> {
            pendingUsers.add(transfer.sourceUserId());
            pendingUsers.add(transfer.targetUserId());
        });
        return CommittedPortfolioSnapshot.capture(state.tradingState(), state.currentClusterTimestamp(),
                asOf, maximumMarkAgeMillis, pendingUsers);
    }

    /** 遍历本副本保留的算法路由，查询实际父单并更新子单索引，再交给消费者。 */
    public void forEachRetainedAlgo(java.util.function.Consumer<CoreAlgoOrderView> consumer) {
        state.algoOrderIndex().forEachRoute(route -> {
            var value = readAlgo(route.userId(), route.algoOrderId());
            if (value == null) throw new IllegalStateException("retained algo route has no state");
            routeAlgoChildren(value);
            consumer.accept(value);
        });
    }

    private CoreAlgoOrderView readAlgo(long userId, long algoId) {
        var response = replayQuery(CoreMessageType.ALGO_ORDER_QUERY, userId,
                CoreAlgoOrderCodec.encodeQuery(userId, algoId, "", 0, 1), state.currentClusterTimestamp(), 1);
        if (response.status() != ResponseStatus.OK) throw new IllegalStateException("committed algo query failed");
        return CoreAlgoOrderCodec.decodeList(response.data()).stream().findFirst().orElse(null);
    }

    private void routeAlgoChildren(CoreAlgoOrderView value) {
        for (long child : value.childOrderIds()) {
            if (value.statusCode() <= 2) algoByChild.put(child, value.algoOrderId());
            else algoByChild.remove(child);
        }
    }

    /** 从成功父单更新、首个立即成交子单客户号及普通订单变化收集脏父单，按首次发现顺序查询最终父视图并更新路由。 */
    private void collectAlgoChanges(CoreMessage command, List<RealtimeFrame> changes) {
        var dirty = new LinkedHashMap<Long, Long>();
        if (command.header().messageType() == CoreMessageType.UPSERT_ALGO_ORDER
                && commandResult != null && commandResult.status() == ResponseStatus.APPLIED) {
            var submitted = CoreAlgoOrderCodec.decodeCommand(command.payloadUnsafe());
            dirty.put(submitted.algoOrderId(), command.header().userId());
        }
        String client = commandResult == null ? null : commandResult.clientOrderId();
        if (client != null && client.startsWith("algo-") && commandResult.status() == ResponseStatus.APPLIED
                && changes.stream().anyMatch(frame -> {
                    if (frame.kind() != RealtimeFrame.Kind.ORDER || frame.userId() != commandResult.userId()) return false;
                    var child = CoreStateQueryCodec.decodeOrderState(frame.payload());
                    return client.equals(child.clientOrderId()) && !"OPEN".equals(child.status());
                })) {
            int separator = client.lastIndexOf('-');
            if (separator > 5) try {
                long id = Long.parseLong(client.substring(5, separator));
                var route = state.algoOrderIndex().value(id);
                if (route != null && route.userId() == commandResult.userId()) dirty.put(id, route.userId());
            } catch (NumberFormatException malformed) { }
        }
        for (var frame : changes) {
            if (frame.kind() != RealtimeFrame.Kind.ORDER) continue;
            long orderId = Long.parseLong(frame.entityId());
            Long parent = algoByChild.get(orderId);
            if (parent != null) dirty.put(parent, frame.userId());
        }
        if (dirty.isEmpty()) return;
        var values = new ArrayList<CoreAlgoOrderView>(dirty.size());
        dirty.forEach((id, user) -> {
            var value = readAlgo(user, id);
            if (value != null) {
                routeAlgoChildren(value);
                values.add(value);
            }
        });
        algoChanges = List.copyOf(values);
    }

    /** 启动重建时分页查询本副本保留的触发记录，校验游标和一至五百一十二的页大小。 */
    public List<CoreTriggerOrderStateView> retainedTriggersBefore(long before, int limit) {
        if (before < 0 || limit < 1 || limit > 512) throw new IllegalArgumentException("invalid retained trigger page");
        long now = System.currentTimeMillis();
        return CoreTriggerOrderCodec.decodeList(replayQuery(CoreMessageType.TRIGGER_ORDER_QUERY, 0,
                CoreTriggerOrderCodec.encodeQuery(new CoreTriggerOrderQuery(0, "", before, limit)), now, 1).data());
    }

    private SettleInstrumentCommand deliveryCommand(CoreMessage message) {
        if (!state.productLine().isDeliveryProduct() || message.header().messageType() != CoreMessageType.SETTLE_INSTRUMENT) return null;
        try { return TradingCommandCodec.decodeSettleInstrument(message.payloadUnsafe()); }
        catch (RuntimeException malformed) { return null; }
    }

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
                balances.add(new BeforeDeliveryBalance(user, instrument.settleAsset(), deliveryBalance(user, instrument.settleAsset())));
                cursor = user;
            }
            if (!page.hasMore()) break;
            if (page.userIds().isEmpty()) throw new IllegalStateException("empty committed delivery user cursor");
        }
        return List.copyOf(balances);
    }

    /** 在本副本 Owner 读取边界直接读取结算资产，余额缺失或总额溢出时拒绝推进交割检查点。 */
    private long deliveryBalance(long user, String asset) {
        var runtime = state.runtimeState();
        Integer assetId = state.identities().findAssetId(asset);
        boolean acquired = false;
        try {
            if (runtime.accountLanesStarted() && !runtime.ownerLaneAccess()) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!runtime.tryAcquireOwnerLaneAccess()) {
                    if (System.nanoTime() >= deadline)
                        throw new IllegalStateException("committed delivery balance read timeout; checkpoint must not advance");
                    java.util.concurrent.locks.LockSupport.parkNanos(1_000L);
                }
                acquired = true;
            }
            if (runtime.productLine() != state.productLine() || runtime.user(user) == null)
                throw new IllegalStateException("committed delivery account identity mismatch");
            var balance = assetId == null ? null : runtime.balance(user, assetId);
            if (balance == null) throw new IllegalStateException("delivery position has no settlement balance");
            return Math.addExact(balance.availableUnits(), balance.lockedUnits());
        } finally {
            if (acquired) runtime.releaseOwnerLaneAccess();
        }
    }

    /** 内部盘口异步结果最多等五秒并检查健康，最终必须 OK 且回调边界干净，不访问在线集群。 */
    private CoreResponse replayQuery(CoreMessageType type, long user, byte[] payload, long timestamp, long position) {
        var request = new CoreMessage(CoreMessageHeader.query(type, UUID.randomUUID(), state.productLine(),
                CommandSource.OPERATIONS, 0, timestamp, user, timestamp, timestamp), payload);
        var response = state.apply(request, timestamp, position);
        if (response.resultCode() == CoreResultCode.MATCHING_PENDING) {
            var id = request.header().commandId();
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

    /** 本页交割执行前保存的用户、结算资产与总额三个标量，供同页完成后计算差额。 */
    private record BeforeDeliveryBalance(long userId, String asset, long totalUnits) {}

    /** 独立回放中当前有界交割页观察到的现金变化，列表复制后对外提供，不是在线账户状态副本。 */
    public record CommittedDeliveryPage(ProductLine product, long occurredAt,
                                         SettleInstrumentCommand command, List<DeliveryCashFlow> cashFlows) {
        public CommittedDeliveryPage {
            cashFlows = List.copyOf(cashFlows);
            if (product == null || !product.isDeliveryProduct() || occurredAt <= 0
                    || command == null || cashFlows.isEmpty() || cashFlows.size() > command.maxUsers())
                throw new IllegalArgumentException("invalid committed delivery cash page");
            var users = new HashSet<Long>();
            for (var flow : cashFlows) if (!users.add(flow.userId()))
                throw new IllegalArgumentException("duplicate delivery cash user");
        }
    }

    /** 一个交割用户在本页实际发生的非零结算现金差额及结算后总额。 */
    public record DeliveryCashFlow(long userId, String asset, long amountUnits, long balanceAfterUnits) {
        public DeliveryCashFlow {
            if (userId <= 0 || asset == null || asset.isBlank() || amountUnits == 0 || balanceAfterUnits < 0)
                throw new IllegalArgumentException("invalid delivery cash flow");
        }
    }

    /** 读取回放副本当前实时导出序号。 */
    public long exportSequence() {
        return state.realtimeExportSequence();
    }

    /** 要求副本回调工作已结束，再生成当前交易活动摘要。 */
    public com.surprising.aeron.service.query.CommittedTradingActivity currentTradingActivity(long now) {
        state.assertClusterCallbackComplete();
        return com.surprising.aeron.service.query.CommittedTradingActivity.capture(state.runtimeState(), state.identities(), now);
    }

    /** 要求本副本回调工作已结束，再交真实快照入口编码恢复状态。 */
    public byte[] snapshot() {
        state.assertClusterCallbackComplete();
        return state.snapshot();
    }

    /** 读取本副本用于快照配对的业务哈希。 */
    public long businessHash() {
        return state.stateHash();
    }

    /** 关闭独立回放运行时及其资源，不关闭或操纵在线交易 Core。 */
    @Override
    public void close() {
        state.close();
    }

    /** 当前已提交资金费命令对应的进度和支付事实，列表属于本页结果。 */
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

    /** 一次已提交命令尝试的最终导出收据，含可空下单身份及有界批撤逐项结果。 */
    public record CommittedCommandResult(ProductLine product, long userId, UUID commandId,
                                         CoreMessageType commandType, String clientOrderId, String instrumentId,
                                         ResponseStatus status, CoreResultCode code, long appliedCommandCount,
                                         long clusterPosition, long occurredAt, List<CancelBatchItem> cancelResults) {
        /** 命令必须为 COMMAND，收据只能是已应用或拒绝，不能仍为 MATCHING_PENDING，身份及日志元数据必须有效。 */
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

    /** 单个批撤请求的确定性结果，只有请求下标、订单身份、成功标记与结果码，不复制完整订单。 */
    public record CancelBatchItem(int index, long orderId, boolean success, String code) {
        /** 成功当且仅当码为 NONE，不允许保留 MATCHING_PENDING。 */
        public CancelBatchItem {
            if (index < 0 || orderId <= 0 || code == null || code.isBlank()
                    || CoreResultCode.valueOf(code) == CoreResultCode.MATCHING_PENDING
                    || success != (CoreResultCode.valueOf(code) == CoreResultCode.NONE))
                throw new IllegalArgumentException("invalid cancellation result item");
        }
    }
}
