package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter.CancellationOrder;

import com.surprising.aeron.service.command.instrument.InstrumentConfigurationCommands;
import lombok.extern.slf4j.Slf4j;
import com.surprising.aeron.service.state.account.TransferRuntime;

import com.surprising.aeron.service.orchestration.metrics.CoreLaneMetrics;
import com.surprising.aeron.service.orchestration.snapshot.CoreSnapshotManifest;
import com.surprising.aeron.service.orchestration.snapshot.SectionedCoreSnapshotCodec;


import com.surprising.aeron.service.command.order.DecodedMatchingCommand;
import com.surprising.aeron.service.command.order.ResolvedMatchingAdmission;
import com.surprising.aeron.service.command.ImmutableLongArrayList;
import com.surprising.aeron.service.command.DirectCommandDispatcher;
import com.surprising.aeron.service.command.balance.BalanceTransferCommands;
import com.surprising.aeron.service.command.position.PositionCommands;
import com.surprising.aeron.service.command.leverage.LeverageCommands;
import com.surprising.aeron.service.command.risk.RiskCommands;
import com.surprising.aeron.service.command.liquidation.LiquidationCommands;
import com.surprising.aeron.service.command.adl.AdlCommands;
import com.surprising.aeron.service.command.insurance.InsuranceFundCommands;
import com.surprising.aeron.service.command.funding.PerpetualFundingCommands;
import com.surprising.aeron.service.command.settlement.InstrumentSettlementCommands;
import com.surprising.aeron.service.command.fee.FeePolicyCommands;
import com.surprising.aeron.service.command.trigger.TriggerOrderCommands;
import com.surprising.aeron.service.exception.FatalMatchingDivergenceException;
import com.surprising.aeron.service.matcher.MatcherPipelineGroup;
import com.surprising.aeron.service.orchestration.CommandResultLedger.StoredResult;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreProtocol;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.WireMessageKind;
import com.surprising.aeron.protocol.CoreFundingProgressView;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.CoreSettlementProgressView;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.OpenInterestIndex;
import com.surprising.aeron.service.state.index.AlgoOrderIndex;
import com.surprising.aeron.service.state.index.LiquidationIndex;
import com.surprising.aeron.service.state.index.CancelAllAfterIndex;
import com.surprising.aeron.service.state.index.ActiveOrderIndex;
import com.surprising.aeron.service.state.AdlPositionIndex;
import com.surprising.aeron.service.state.PositionUserIndex;
import com.surprising.aeron.service.state.risk.RiskScanRuntime;
import com.surprising.aeron.service.state.LiquidationRuntime;
import com.surprising.aeron.service.state.market.MarkPriceRuntime;
import com.surprising.aeron.service.state.PositionRuntime;
import com.surprising.aeron.service.state.index.TriggerOrderIndex;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.RuntimeOrderStateTransitions;
import com.surprising.aeron.service.state.RuntimeOrderCommitStateTransitions;
import com.surprising.aeron.service.state.OrderRuntime;
import com.surprising.aeron.service.state.admission.CoreOrderDecisionResolver;
import com.surprising.aeron.service.state.ResolvedPlaceOrder;
import com.surprising.aeron.service.state.RuntimeTreasuryDelta;
import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.model.CoreOrderState;
import com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import com.surprising.aeron.service.matching.MatcherSnapshot;
import com.surprising.product.api.ProductLine;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 唯一交易执行入口，按集群日志顺序组织准入、撮合和有序提交。
 * 产品命令、批量执行、结果账本、查询及快照由所属组件管理；不持有旧探测运行时副本。
 */
public final class TradingCoreRuntime implements AutoCloseable {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TradingCoreRuntime.class);

    final CommandFingerprint.Calculator fingerprints = new CommandFingerprint.Calculator();


    /** 交割与期权等币对到期结算：沿用产品结算处理器，并登记受影响账户。 */
    final InstrumentSettlementCommands instrumentSettlement = new InstrumentSettlementCommands(this);


    /** 撮合命令准入：校验订单与生命周期依赖，登记延后命令，不提交未完成结算。 */
    final MatchingCommandAdmission admissions = new MatchingCommandAdmission(this);

    /** 有序提交阶段：验证撮合证据、收集 Lane 结算并发布已提交变化；不重新执行资金业务。 */
    final OrderedCommitCoordinator commits = new OrderedCommitCoordinator(this);

    /** 余额调整与资金转入转出命令；维护转账幂等状态。 */
    final BalanceTransferCommands balances = new BalanceTransferCommands(this);

    /** 币对配置与维护状态命令；六产品线按所属运行时隔离。 */
    final InstrumentConfigurationCommands instruments =
            new InstrumentConfigurationCommands(this);

    /** 永续合约资金费命令；沿用 U 本位和币本位各自结算规则。 */
    final PerpetualFundingCommands funding = new PerpetualFundingCommands(this);

    /** 衍生品标记价、风险续扫和风险扫描控制命令。 */
    final RiskCommands risk = new RiskCommands(this);

    /** 衍生品持仓模式和保证金命令；不处理杠杆变更。 */
    final PositionCommands positions = new PositionCommands(this);

    /** 衍生品杠杆命令；不处理持仓模式和保证金。 */
    final LeverageCommands leverage = new LeverageCommands(this);

    /** 衍生品强平命令；负责强平撤单推进和强平结果确认。 */
    final LiquidationCommands liquidations = new LiquidationCommands(this);

    /** 自动减仓命令。 */
    final AdlCommands adl = new AdlCommands(this);

    /** 保险基金调整命令。 */
    final InsuranceFundCommands insurance = new InsuranceFundCommands(this);

    /** 触发单/算法单命令；只负责触发生命周期和触发子单。 */
    final TriggerOrderCommands triggers = new TriggerOrderCommands(this);

    /** 直接业务命令路由；匹配和控制流水线由本编排器继续处理。 */
    final DirectCommandDispatcher directCommands = new DirectCommandDispatcher(
            balances, instruments, funding, risk, liquidations, adl, insurance,
            positions, leverage, instrumentSettlement, new FeePolicyCommands(this),
            triggers);
    /** 集群日志入口的状态与暂存字段。 */
    private boolean clusterPipelineAdmission;
    private CoreMessage decodedIngressMessage;
    private DecodedMatchingCommand decodedIngressCommand;
    private CommandFingerprint preparedIngressFingerprint;
    private long preparedRouteUserLaneBit;
    private int preparedRouteMatcherShard = -1;
    /** 撮合在途命令、Matcher 提交及 Lane 结算事件的完整流程。 */
    public final CoreMatchingFlow matchingFlow = new CoreMatchingFlow(this);
    /** 状态物化只读缓存。 */
    private TradingCoreState cachedTradingState;
    private long cachedRevision = Long.MIN_VALUE;
    private long cachedMarketRevision = Long.MIN_VALUE;
    private long cachedSequence = Long.MIN_VALUE;

    /** Common matching responses use bounded stable slabs; ledger eviction returns their slots. */
    final ResponseArena responseArena = new ResponseArena();

    /** 当前命令的结果与变更 ID；owner 串行复用，在命令完成边界编码。 */
    public final CommandResultBuilder resultBuilder = new CommandResultBuilder(this);

    /** 批量命令准备、结算派发与终态收集；仅交易 owner 推进。 */
    final OrderBatchExecutor batches = new OrderBatchExecutor(this);
    /** 跨 matcher 清算撤单的非阻塞推进器。 */
    final CrossShardCancellationCoordinator crossShardCancellations = new CrossShardCancellationCoordinator(this);

    /** 快照屏障与异步编码生命周期；不持有第二份交易状态。 */
    public final CoreSnapshotLifecycle snapshots = new CoreSnapshotLifecycle(this);

    /** 盘口查询与分页会话；仅在查询边界物化视图。 */
    final OrderBookQueryService bookQueries = new OrderBookQueryService(this);
    /** 撮合在途推进状态；拥有 Lane/Matcher 通知排空和队首唤醒规则。 */
    final MatchingPipelineProgress matchingProgress = new MatchingPipelineProgress(this);

    /** 实时事件编码出口；只能读取已提交或有快照屏障保护的值。 */
    com.surprising.aeron.service.state.realtime.RealtimeStateCapture realtimeCapture;
    /** 绑定实时事件出口，并创建实时快照/盘口读取协调器。 */
    public com.surprising.aeron.service.state.realtime.RealtimeStateCapture attachRealtime(
            com.surprising.aeron.client.RealtimeOutbox outbox) {
        realtimeCapture = new com.surprising.aeron.service.state.realtime.RealtimeStateCapture(
                outbox, productLine, identities);
        runtimeState.realtimeCapture(realtimeCapture);
        return realtimeCapture;
    }

    private java.util.concurrent.CompletableFuture<com.surprising.aeron.service.state.RealtimeUserSnapshot> pendingRealtimeSnapshot;
    private long realtimeSnapshotUser, realtimeSnapshotId, realtimeSnapshotPosition, realtimeSnapshotTimestamp, realtimeSnapshotExportSequence;
    private java.util.concurrent.CompletableFuture<java.util.List<com.surprising.aeron.protocol.CoreBookLevelView>> realtimeBook;
    private String realtimeBookSymbol;
    private long realtimeBookPosition, realtimeBookTimestamp;

    /** 返回已经提交并可对外发布的实时事件水位。 */
    public long realtimeExportSequence() { return commits.publication.publishedSequence(); }
    /** 返回是否存在尚未完成的用户实时快照读取。 */
    public boolean realtimeSnapshotPending() { return pendingRealtimeSnapshot != null; }
    /** 返回是否存在尚未完成的盘口读取。 */
    public boolean realtimeBookPending() { return realtimeBook != null; }

    /** 推进用户实时快照读取，并返回本轮完成的工作数。 */
    public int pollRealtimeSnapshot() {
        if (pendingRealtimeSnapshot == null || !pendingRealtimeSnapshot.isDone()) return 0;
        var future = pendingRealtimeSnapshot;
        pendingRealtimeSnapshot = null;
        try {
            var snapshot = future.join();
            realtimeCapture.begin(realtimeSnapshotPosition, realtimeSnapshotTimestamp, realtimeSnapshotId);
            realtimeCapture.snapshot(realtimeSnapshotUser, snapshot, realtimeSnapshotExportSequence);
            realtimeCapture.commit();
        } catch (RuntimeException failure) {
            realtimeCapture.failed();
            realtimeCapture.begin(realtimeSnapshotPosition, realtimeSnapshotTimestamp, realtimeSnapshotId);
            realtimeCapture.emit(com.surprising.aeron.protocol.RealtimeFrame.Kind.SNAPSHOT_UNAVAILABLE,
                    realtimeSnapshotUser, "", "", new byte[0]);
            realtimeCapture.commit();
        }
        return 1;
    }

    /** 推进盘口读取，并返回本轮完成的工作数。 */
    public int pollRealtimeBook() {
        if (realtimeBook == null || !realtimeBook.isDone()) return 0;
        var future = realtimeBook;
        realtimeBook = null;
        try {
            var levels = future.join();
            realtimeCapture.begin(realtimeBookPosition, realtimeBookTimestamp, 0);
            realtimeCapture.emit(com.surprising.aeron.protocol.RealtimeFrame.Kind.BOOK, 0,
                    realtimeBookSymbol, realtimeBookSymbol,
                    com.surprising.aeron.protocol.CoreStateQueryCodec.encodeOrderBookView(
                            new com.surprising.aeron.protocol.CoreOrderBookView(realtimeBookPosition, levels)));
            realtimeCapture.commit();
        } catch (RuntimeException failure) {
            realtimeCapture.failed();
        }
        return 1;
    }

    /** 在已提交位置上登记一个用户实时快照请求。 */
    public void captureRealtimeSnapshot(long userId, long snapshotId, long position, long timestamp) {
        if (realtimeCapture == null || pendingRealtimeSnapshot != null) return;
        assertClusterCallbackComplete();
        realtimeSnapshotExportSequence = realtimeExportSequence();
        realtimeSnapshotUser = userId;
        realtimeSnapshotId = snapshotId;
        realtimeSnapshotPosition = position;
        realtimeSnapshotTimestamp = timestamp;
        try { pendingRealtimeSnapshot = runtimeState.realtimeSnapshot(userId); }
        catch (RuntimeException failure) { realtimeCapture.failed(); }
    }

    /** 在已提交位置上登记一个盘口读取请求。 */
    public void captureRealtimeBook(String instrumentId, long position, long timestamp) {
        if (realtimeCapture == null || realtimeBook != null) return;
        assertClusterCallbackComplete();
        realtimeBookSymbol = instrumentId;
        realtimeBookPosition = position;
        realtimeBookTimestamp = timestamp;
        try {
            realtimeBook = matcherPipeline.readAtSubmissionFence(matchingAdapter.matcherShardId(instrumentId),
                    () -> matchingAdapter.orderBookLevelsAsync(instrumentId, 50).join());
        } catch (RuntimeException failure) {
            realtimeCapture.failed();
        }
    }

    /** 查询协议路由：读取已有路由索引并向实际账户、撮合器或账本所有者派发查询。 */
    CoreResponse routeQuery(CoreMessage message, long clusterTimestamp) {
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && OrderBookQueryService.accountQuery(message.header().messageType())) {
            try { return bookQueries.beginAccountQuery(message); }
            catch (com.surprising.aeron.service.state.query.RuntimeOperationalQueryService.QueryTooLargeException tooLarge) {
                return rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
            } catch (IllegalArgumentException invalid) { return rejected(CoreResultCode.INVALID_COMMAND); }
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && (message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.STATE_HASH_QUERY
                || message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.BUSINESS_STATE_HASH_QUERY)) {
            return new CoreResponse(com.surprising.aeron.protocol.ResponseStatus.OK, appliedCommandCount,
                    com.surprising.aeron.protocol.CoreStateQueryCodec.encodeStateHash(currentBusinessStateHash()));
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.LANE_METRICS_QUERY) {
            if (message.payloadUnsafe().length != 0) return rejected(CoreResultCode.INVALID_COMMAND);
            return new CoreResponse(com.surprising.aeron.protocol.ResponseStatus.OK, appliedCommandCount, encodeLaneMetrics());
        }
        if (message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.INSTRUMENT_MAINTENANCE_QUERY) {
            try {
                var query = com.surprising.aeron.protocol.CoreMaintenanceCodec.decodeQuery(message.payloadUnsafe());
                var instrument = runtimeState.instrument(query.instrumentId());
                if (instrument == null) return rejected(CoreResultCode.ENTITY_NOT_FOUND);
                return bookQueries.beginPositionIndexQuery(message, query, instrument.maintenance());
            } catch (IllegalArgumentException | java.nio.BufferUnderflowException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.COMMAND_RESULT_QUERY) {
            try {
                java.util.UUID commandId = com.surprising.aeron.protocol.CoreStateQueryCodec.decodeCommandResultQuery(message.payloadUnsafe());
                CommandResultLedger.StoredResult result = resultLedger.get(commandId);
                if (result == null) {
                    return new CoreResponse(com.surprising.aeron.protocol.ResponseStatus.REJECTED, com.surprising.aeron.protocol.ResponseStatus.REJECTED,
                            CoreResultCode.RESULT_UNKNOWN_OUTSIDE_RETENTION, appliedCommandCount,
                            EMPTY_RESPONSE_DATA);
                }
                responseArena.retain(result.responseDataUnsafe());
                return CoreResponse.owned(com.surprising.aeron.protocol.ResponseStatus.OK, result.status(), result.resultCode(),
                        result.appliedCommandCount(),
                        result.responseDataUnsafe(), result.responseDataOffsetUnsafe(), result.responseDataLength());
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && (message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.TRIGGER_ORDER_QUERY
                || message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.USER_OPEN_TRIGGER_ORDERS_QUERY)) {
            try { return bookQueries.beginTriggerQuery(message);
            } catch (com.surprising.aeron.service.state.query.RuntimeOperationalQueryService.QueryTooLargeException exception) {
                return rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.BOOK_STATE_QUERY) {
            try {
                return bookQueries.beginBookQuery(message);
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.ORDER_BOOK_BOOTSTRAP_QUERY) {
            try {
                return bookQueries.beginBookBootstrapQuery(message);
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.TREASURY_STATE_QUERY) {
            try {
                if (runtimeState.systemLedgerStarted()) return bookQueries.beginTreasuryQuery(message);
                var views = com.surprising.aeron.service.state.query.RuntimeOperationalQueryService.treasuryAssets(
                        runtimeState, identities);
                return new CoreResponse(com.surprising.aeron.protocol.ResponseStatus.OK, appliedCommandCount,
                        com.surprising.aeron.protocol.CoreStateQueryCodec.encodeTreasuryState(views));
            } catch (com.surprising.aeron.service.state.query.RuntimeOperationalQueryService.QueryTooLargeException exception) {
                return rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
            }
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.FUNDING_PROGRESS_QUERY) {
            try {
                String instrumentId = com.surprising.aeron.protocol.CoreStateQueryCodec.decodeFundingProgressQuery(message.payloadUnsafe());
                var view = com.surprising.aeron.service.state.query.RuntimeOperationalQueryService
                        .fundingProgress(runtimeState, identities, instrumentId);
                return new CoreResponse(com.surprising.aeron.protocol.ResponseStatus.OK, appliedCommandCount,
                        com.surprising.aeron.protocol.CoreFundingProgressCodec.encode(view));
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.SETTLEMENT_PROGRESS_QUERY) {
            try {
                String instrumentId = com.surprising.aeron.protocol.CoreStateQueryCodec.decodeSettlementProgressQuery(message.payloadUnsafe());
                var view = com.surprising.aeron.service.state.query.RuntimeOperationalQueryService
                        .settlementProgress(runtimeState, identities, instrumentId);
                return new CoreResponse(com.surprising.aeron.protocol.ResponseStatus.OK, appliedCommandCount,
                        com.surprising.aeron.protocol.CoreSettlementProgressCodec.encode(view));
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && OrderBookQueryService.riskAccountQuery(message.header().messageType())) {
            try { return bookQueries.beginRiskAccountQuery(message); }
            catch (com.surprising.aeron.service.state.query.RuntimeOperationalQueryService.QueryTooLargeException failure) {
                return rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
            } catch (IllegalArgumentException failure) { return rejected(CoreResultCode.INVALID_COMMAND); }
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.RISK_SCAN_CONTROL_QUERY) {
            if (message.payloadUnsafe().length != 0) return rejected(CoreResultCode.INVALID_COMMAND);
            return new CoreResponse(com.surprising.aeron.protocol.ResponseStatus.OK, appliedCommandCount,
                    com.surprising.aeron.protocol.CoreRiskScanControlCodec.encodeView(runtimeState.riskScanControl()));
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.OPEN_INTEREST_QUERY) {
            return bookQueries.beginPositionIndexQuery(message, null, null);
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.CANCEL_ALL_AFTER_QUERY) {
            try {
                var query = com.surprising.aeron.protocol.CoreCancelAllAfterCodec.decodeQuery(message.payloadUnsafe());
                var keys = cancelAllAfterIndex.query(query.userId(), query.symbolScope(), query.dueAtEpochMillis(),
                        query.limit(), runtimeState::cancelAllAfterTimer);
                var values = com.surprising.aeron.service.state.query.RuntimeOperationalQueryService.cancelAllAfter(
                        runtimeState, keys);
                return new CoreResponse(com.surprising.aeron.protocol.ResponseStatus.OK, appliedCommandCount,
                        com.surprising.aeron.protocol.CoreCancelAllAfterCodec.encodeList(values));
            } catch (com.surprising.aeron.service.state.query.RuntimeOperationalQueryService.QueryTooLargeException exception) {
                return rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.LIQUIDATION_WORK_QUERY) {
            try {
                var query = com.surprising.aeron.protocol.CoreLiquidationWorkCodec.decodeQuery(message.payloadUnsafe());
                if (query.productLine() != productLine) {
                    return rejected(CoreResultCode.PRODUCT_LINE_MISMATCH);
                }
                java.util.NavigableSet<Long> candidates = liquidationIndex.activeIds()
                        .tailSet(query.afterLiquidationId(), false);
                if (runtimeState.systemLedgerStarted()) {
                    return bookQueries.beginLiquidationWorkQuery(message, query);
                }
                var work = com.surprising.aeron.service.state.query.RuntimeLiquidationQueryService.work(
                        runtimeState, identities, productLine, query, candidates,
                        liquidationIndex.activeIds());
                return new CoreResponse(com.surprising.aeron.protocol.ResponseStatus.OK, appliedCommandCount,
                        com.surprising.aeron.protocol.CoreLiquidationWorkCodec.encodeWork(work));
            } catch (com.surprising.aeron.service.state.query.RuntimeOperationalQueryService.QueryTooLargeException exception) {
                return rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.ORDER_PREFLIGHT_QUERY) {
            try {
                return bookQueries.beginPreflightQuery(message, clusterTimestamp);
            } catch (com.surprising.aeron.service.exception.CoreStateRejectedException exception) {
                return rejected(CoreResultCode.fromRejectionCode(exception.code()));
            } catch (ArithmeticException exception) {
                return rejected(CoreResultCode.ARITHMETIC_OVERFLOW);
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.PENDING_TRANSFER_QUERY) {
            try {
                int limit = com.surprising.aeron.protocol.CorePendingTransferCodec.decodeQuery(
                        message.payloadUnsafe());
                if (runtimeState.asynchronousCommands())
                    return bookQueries.beginPendingTransferQuery(message, limit);
                var transfers = runtimeState.pendingTransfers(limit).stream()
                        .map(value -> new com.surprising.aeron.protocol.CorePendingTransferView(
                                value.userId(), value.command()))
                        .toList();
                return new CoreResponse(com.surprising.aeron.protocol.ResponseStatus.OK, appliedCommandCount,
                        com.surprising.aeron.protocol.CorePendingTransferCodec.encode(transfers));
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        return null;
    }

    /** 幂等结果账本允许保留的最大命令数。 */
    static final int MAX_IDEMPOTENCY_RESULTS = 128;
    /** 幂等响应账本的总字节预算。 */
    static final long MAX_RESULT_LEDGER_BYTES = 32L * 1024 * 1024;
    /** 单条可保留响应的字节上限，不得超过账本总预算。 */
    static final int MAX_STORED_RESPONSE_BYTES = Math.toIntExact(MAX_RESULT_LEDGER_BYTES);
    /** 来源序号去重表的容量上限。 */
    static final int MAX_SOURCE_SEQUENCES = 65_536;
    /** 单次盘口响应允许返回的最大档位数。 */
    static final int MAX_BOOK_RESPONSE_LEVELS = 10_000;
    /** 单次盘口响应的编码字节上限。 */
    static final int MAX_BOOK_RESPONSE_BYTES = 1024 * 1024;
    /** 同步完成边界等待撮合的超时时间，单位为纳秒。 */
    static final long MATCHING_AWAIT_TIMEOUT_NANOS =
            java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
    /** 同时保留的盘口分页快照会话上限。 */
    static final int MAX_BOOK_BOOTSTRAP_SNAPSHOTS = 4;
    /** 未显式指定时，每轮触发扫描处理的条数。 */
    static final int DEFAULT_TRIGGER_SCAN_BATCH_SIZE = 2;
    /** 无订单结果共用的空数组，不得写入。 */
    static final long[] EMPTY_ORDER_IDS = new long[0];
    /** 独立快照调用等待完成的超时时间，单位为秒。 */
    static final int STANDALONE_SNAPSHOT_TIMEOUT_SECONDS = Integer.getInteger(
            "surprising.aeron.standalone-snapshot-timeout-seconds", 300);
    /** 确定性状态摘要的 FNV 初始值。 */
    static final long HASH_OFFSET_BASIS = 0xcbf29ce484222325L;
    /** 确定性状态摘要的 FNV 混合乘数。 */
    static final long HASH_PRIME = 0x100000001b3L;
    /** 无需响应正文时共用的空字节数组。 */
    static final byte[] EMPTY_RESPONSE_DATA = new byte[0];
    /** 协议中的撮合处理中结果码，保留现有线协议取值。 */
    static final int MATCHING_PENDING_WIRE_CODE = 66;
    /** 撮合在途命令上限，用于入口背压和有界容量。 */
    static final int MAX_PENDING_MATCHING = Integer.getInteger(
            "surprising.aeron.max-pending-matching", 4_096);
    /** 所属产品线；所有命令、账户与币对均在此边界隔离。 */
    final ProductLine productLine;
    /** 拥有当前执行上下文的唯一 owner；不得在其他线程直接修改其状态。 */
    Thread owner;
    /** 已提交业务变化的索引更新器；不复制完整交易状态。 */
    final com.surprising.aeron.service.state.RuntimeFactIndexes factIndexes;
    /** 有界命令结果账本；保存幂等响应而非业务状态副本。 */
    final CommandResultLedger resultLedger;

    /** 各命令来源已应用序号，供来源去重及恢复使用。 */
    final SourceSequenceIndex lastSourceSequences;
    /** 在途命令环；统一保存日志顺序、准入、撮合、Lane 上下文与结算关联。 */
    final PendingMatchingRing pendingMatching;
    /** 撮合派发和完成通知通道；只在完成边界交接数据。 */
    final MatcherPipelineGroup matcherPipeline;
    /** 真实撮合器适配器；命令经 matcher 队列推进。 */
    final DeterministicExchangeCoreAdapter matchingAdapter;

    /** Ledger 单写者的持仓用户索引；Owner 仅转交查询描述和就绪回执。 */
    final PositionUserIndex positionUserIndex;
    /** Ledger 单写者的全局持仓量；准入只消费命令槽中的 primitive 输入。 */
    final OpenInterestIndex openInterestIndex;
    /** triggerOrderIndex 业务查询索引；由提交变化维护，不作为独立权威状态。 */
    final TriggerOrderIndex triggerOrderIndex;
    /** algoOrderIndex 业务查询索引；由提交变化维护，不作为独立权威状态。 */
    final AlgoOrderIndex algoOrderIndex;
    /** liquidationIndex 业务查询索引；由提交变化维护，不作为独立权威状态。 */
    final LiquidationIndex liquidationIndex;
    /** cancelAllAfterIndex 业务查询索引；由提交变化维护，不作为独立权威状态。 */
    final CancelAllAfterIndex cancelAllAfterIndex;
    /** activeOrderIndex 业务查询索引；由提交变化维护，不作为独立权威状态。 */
    final ActiveOrderIndex activeOrderIndex;
    /** Ledger 单写者的 ADL 持仓索引；可变索引单元不交给 Owner 或 Account Lane。 */
    final AdlPositionIndex adlPositionIndex;

    /** 已提交成交数量；不能与订单业务操作数混用。 */
    long terminalTradeCount;
    /** 已终结订单及触发身份的有界保留区，防止短期重复执行。 */
    final TerminalStateRetention terminalRetention;
    /** 提交日志与快照版本边界；与交易日志共同约束恢复。 */

    /** Owner 当前是否持有命令的变更缓冲；挂起时交还序号槽。 */
    boolean factContextActive;
    /** 已接收并应用的命令序号上界；可能高于已完成结算水位。 */
    long appliedCommandCount;
    /** 连续完成结算的全局命令水位；唯一 owner 推进。 */
    long committedCoreSequence;
    /** 确定性业务变更后发布失败；要求从快照和日志恢复。 */
    RuntimeException commitPublicationFailure;
    /** 协议探测命令的持久化值；不参与订单或资金计算。 */
    long probeValue;
    /**
     * Owner-only materialized read view.  The live runtime is authoritative; callers that ask
     * for a TradingCoreState read view must not rebuild the complete immutable graph repeatedly
     * while the committed state is unchanged (which used to recreate TreeMap entries, user
     * records and order snapshots on every query/verification call).
     */
    /** 字符串与 primitive 标识的唯一字典；生命周期覆盖该运行时。 */
    final RuntimeIdentityRegistry identities;
    /** 唯一权威运行时状态；账户可变数据由所属 Lane 维护。 */
    final TradingRuntimeState runtimeState;

    /** 撮合或确定性执行的不可恢复错误，阻止继续交易。 */
    FatalMatchingDivergenceException fatalFailure;

    /** 收集各 Lane 国库变化的复用缓冲；提交完清空。 */
    final RuntimeTreasuryDelta mergedLaneTreasuryDelta =
            new RuntimeTreasuryDelta(RuntimeTreasuryDelta.ORDER_BATCH_CAPACITY);
    /** 当前激活命令在复制日志中的位置。 */
    long currentClusterPosition;
    /** 当前激活命令的集群时间，保证确定性业务时间。 */
    long currentClusterTimestamp;
    /** 准入临时切换前的日志位置，退出准入时恢复。 */
    long admissionPreviousClusterPosition;
    /** 准入临时切换前的集群时间，退出准入时恢复。 */
    long admissionPreviousClusterTimestamp;
    /** 运行时是否已绑定 owner 并启动消费者。 */
    boolean activated;
    /** 资源是否已释放，避免重复关闭。 */
    boolean closed;

    public TradingRuntimeState runtimeState() { return runtimeState; }
    public RuntimeIdentityRegistry identities() { return identities; }
    public void requestCommitPublication() { commits.requestCommitPublication(); }
    public boolean asynchronousCommands() { return runtimeState.asynchronousCommands(); }
    public PositionUserIndex positionUserIndex() { return positionUserIndex; }
    public TriggerOrderIndex triggerOrderIndex() { return triggerOrderIndex; }
    public ActiveOrderIndex activeOrderIndex() { return activeOrderIndex; }
    public Iterable<Long> activeLiquidationIds() { return liquidationIndex.activeIds(); }
    public void initializeTriggerScan(com.surprising.aeron.protocol.ApplyMarkPriceCommand command) {
        triggers.initializeTriggerScan(command);
    }
    public java.util.function.BooleanSupplier pendingTriggerScan(String instrumentId, int maxWork) {
        return triggers.pendingTriggerScan(instrumentId, maxWork);
    }
    public void evaluatePendingTriggerScan(String instrumentId, int maxWork) {
        triggers.evaluatePendingTriggerScan(instrumentId, maxWork);
    }
    public int queuedMatchingCount() { return admissions.queuedMatching.size(); }
    public int defaultTriggerScanBatchSize() { return DEFAULT_TRIGGER_SCAN_BATCH_SIZE; }
    public long currentClusterTimestamp() { return currentClusterTimestamp; }
    public boolean terminalAlgoRetained(long algoOrderId, long userId, String clientAlgoOrderId) {
        return terminalRetention.containsAlgo(algoOrderId, userId, clientAlgoOrderId);
    }
    public boolean terminalTriggerRetained(long triggerOrderId, long userId, String clientTriggerOrderId) {
        return terminalRetention.containsTrigger(triggerOrderId, userId, clientTriggerOrderId);
    }
    public com.surprising.aeron.service.state.TreasuryRuntime.LifecycleProgressRuntime
            lifecycleProgress(String instrumentId) {
        return runtimeLifecycleProgress(instrumentId);
    }

    public TradingCoreRuntime(ProductLine productLine) {
        this(productLine, 0, 0, new LinkedHashMap<>(), new LinkedHashMap<>(),
                TradingCoreState.empty(productLine), new TerminalStateRetention(), null,
                0, Map.of(), Map.of(), null, null);
    }

    TradingCoreRuntime(ProductLine productLine, MatcherSnapshotCapture matcherSnapshotCapture) {
        this(productLine, 0, 0, new LinkedHashMap<>(), new LinkedHashMap<>(),
                TradingCoreState.empty(productLine), new TerminalStateRetention(), null,
                0, Map.of(), Map.of(), matcherSnapshotCapture, null);
    }

    TradingCoreRuntime(ProductLine productLine, MatcherSnapshotCapture matcherSnapshotCapture,
                   SnapshotEncoder snapshotEncoder) {
        this(productLine, 0, 0, new LinkedHashMap<>(), new LinkedHashMap<>(),
                TradingCoreState.empty(productLine), new TerminalStateRetention(), null,
                0, Map.of(), Map.of(), matcherSnapshotCapture, snapshotEncoder);
    }

    TradingCoreRuntime(
            ProductLine productLine,
            long appliedCommandCount,
            long probeValue,
            LinkedHashMap<UUID, StoredResult> commandResults,
            LinkedHashMap<SourceKey, Long> lastSourceSequences,
            TradingCoreState snapshotState,
            TerminalStateRetention terminalRetention,
            MatcherSnapshot matcherSnapshot,
            long projectionSequence,
            Map<Long, com.surprising.aeron.service.state.model.CoreFeePolicyState> restoredFeePolicies,
            Map<Long, com.surprising.aeron.service.state.account.TransferRuntime> restoredPendingTransfers,
            MatcherSnapshotCapture matcherSnapshotCapture,
            SnapshotEncoder snapshotEncoder) {
        this.productLine = productLine;
        this.appliedCommandCount = appliedCommandCount;
        this.committedCoreSequence = appliedCommandCount;
        this.probeValue = probeValue;
        this.resultLedger = new CommandResultLedger(commandResults, responseArena);
        this.lastSourceSequences = new SourceSequenceIndex(lastSourceSequences);
        admissions.pendingLifecycleScopes = new org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<>();
        snapshots.lastSnapshotId = matcherSnapshot == null ? 0 : matcherSnapshot.snapshotId();
        this.terminalRetention = terminalRetention;
        long restoredBusinessStateHash = canonicalBusinessStateHash(
                snapshotState.businessStateHash(), restoredFeePolicies, restoredPendingTransfers);
        var topology = matcherSnapshot == null
                ? com.surprising.aeron.service.state.LaneTopology.configured(
                        Boolean.getBoolean("surprising.aeron.p10-characterization"))
                : matcherSnapshot.topology();
        this.identities = new RuntimeIdentityRegistry();
        this.runtimeState = com.surprising.aeron.service.state.RuntimeStateProjector.project(
                snapshotState, identities, topology);
        this.activeOrderIndex = new ActiveOrderIndex(snapshotState, identities);
        this.matchingAdapter = matcherSnapshot == null
                ? new DeterministicExchangeCoreAdapter(false)
                : new DeterministicExchangeCoreAdapter(snapshotState, snapshotState.orders().values().stream().filter(order -> order.status() == com.surprising.aeron.service.state.model.CoreOrderStatus.OPEN).toList(),
                        appliedCommandCount, matcherSnapshot, false, restoredBusinessStateHash);
        this.pendingMatching = new PendingMatchingRing(
                Math.min(MAX_PENDING_MATCHING, matchingAdapter.topology().matcherWindowSize()),
                matchingAdapter.topology().matchingEngineCount(),
                matchingAdapter.topology().accountLaneCount());
        commits.appliedMatcherSequences = new long[matchingAdapter.topology().matchingEngineCount() + 1];
        commits.initializeMatcherProgress(matcherSnapshot);
        this.matcherPipeline = new MatcherPipelineGroup(
                matchingAdapter.topology().matchingEngineCount(),
                Math.min(matchingAdapter.topology().matcherWindowSize(),
                        matchingAdapter.topology().matchingCompletionCapacity()), false, pendingMatching.contexts());
        snapshots.matcherSnapshotCapture = matcherSnapshotCapture == null
                ? snapshots::captureMatcherSnapshot : matcherSnapshotCapture;
        if (snapshotEncoder == null) {
            snapshots.snapshotEncoder = image -> CompletableFuture.completedFuture(
                    SectionedCoreSnapshotWriter.encode(image));
        } else {
            snapshots.snapshotEncoder = snapshotEncoder;
        }
        this.positionUserIndex = new PositionUserIndex(snapshotState, identities, topology);
        this.openInterestIndex = new OpenInterestIndex(snapshotState, identities);
        this.triggerOrderIndex = new TriggerOrderIndex(snapshotState);
        this.algoOrderIndex = new AlgoOrderIndex(snapshotState);
        this.liquidationIndex = new LiquidationIndex(snapshotState);
        this.cancelAllAfterIndex = new CancelAllAfterIndex(snapshotState);
        this.adlPositionIndex = new AdlPositionIndex(snapshotState, identities);
        this.factIndexes = new com.surprising.aeron.service.state.RuntimeFactIndexes(
                positionUserIndex, openInterestIndex, triggerOrderIndex, algoOrderIndex,
                liquidationIndex, cancelAllAfterIndex, activeOrderIndex, adlPositionIndex);
        runtimeState.bindFactIndexes(factIndexes, identities);
        this.runtimeState.restoreFeePolicies(restoredFeePolicies);
        this.runtimeState.restorePendingTransfers(restoredPendingTransfers);
        commits.initializeCommitPublication(snapshotState.revision(), projectionSequence);
        this.factIndexes.rebuild(snapshotState, identities);
        runtimeState.releaseOwnerForHandoff();
        identities.releaseOwnerForHandoff();
    }

    static TradingCoreRuntime prepareRestore(
            ProductLine productLine,
            long appliedCommandCount,
            long probeValue,
            Map<UUID, StoredResult> commandResults,
            Map<SourceKey, Long> lastSourceSequences,
            TradingCoreState snapshotState,
            TerminalStateRetention terminalRetention,
            MatcherSnapshot matcherSnapshot,
            long projectionSequence,
            Map<Long, com.surprising.aeron.service.state.model.CoreFeePolicyState> feePolicies,
            Map<Long, com.surprising.aeron.service.state.account.TransferRuntime> pendingTransfers) {
        if (projectionSequence < 0 || appliedCommandCount < 0 || commandResults == null
                || commandResults.size() > MAX_IDEMPOTENCY_RESULTS || lastSourceSequences == null
                || lastSourceSequences.size() > MAX_SOURCE_SEQUENCES
                || matcherSnapshot == null || snapshotState == null
                || snapshotState.productLine() != productLine
                || terminalRetention == null || feePolicies == null || pendingTransfers == null) {
            throw new IllegalArgumentException("invalid passive restored probe state");
        }
        CommandResultLedger.validateResultLedger(commandResults);
        return new TradingCoreRuntime(productLine, appliedCommandCount, probeValue,
                new LinkedHashMap<>(commandResults), new LinkedHashMap<>(lastSourceSequences),
                snapshotState, terminalRetention, matcherSnapshot, projectionSequence,
                feePolicies, pendingTransfers, null, null);
    }

    public CoreResponse apply(CoreMessage message) {
        return apply(message, message.header().submittedAtEpochMillis(), Math.addExact(appliedCommandCount, 1));
    }

    boolean clusterPipelineAdmission() {
        return clusterPipelineAdmission;
    }

    long preparedRouteUserLaneBit() {
        return preparedRouteUserLaneBit;
    }

    int preparedRouteMatcherShard() {
        return preparedRouteMatcherShard;
    }

    public CoreResponse apply(CoreMessage message, long clusterTimestamp, long clusterPosition) {
        if (!activated()) activate();
        assertOwner();
        if (directCommand.active()) {
            throw new IllegalStateException("asynchronous control command is still active");
        }
        admissionPreviousClusterTimestamp = currentClusterTimestamp;
        admissionPreviousClusterPosition = currentClusterPosition;
        currentClusterTimestamp = clusterTimestamp;
        currentClusterPosition = clusterPosition;
        assertHealthy();
        if (snapshots.snapshotFence != null
                && snapshots.snapshotFence.encodedSnapshot == null) {
            throw new IllegalStateException("snapshot fence is active");
        }
        if (!pendingMatching.isEmpty()
                && !isCommitCursorSafeWhileMatching(message)) {
            long userId = message.header().userId();
            if (userId <= 0 || pendingMatching.hasUser(userId)) {
                throw new IllegalStateException("command or query crossed its account-lane matching cursor");
            }
        }
        if (message.header().productLine() != productLine) {
            return rejected(CoreResultCode.PRODUCT_LINE_MISMATCH);
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && requiresOwnerLaneAccessForPreparation(message)
                && accountLaneReadQuery(message.header().messageType())) {
            if (singleUserLaneQuery(message.header().messageType())
                    && message.header().userId() > 0) {
                runtimeState.readFence(message.header().userId(), committedCoreSequence);
            } else {
                runtimeState.readFenceAll(committedCoreSequence);
            }
        }
        CoreResponse queryResponse = routeQuery(message, clusterTimestamp);
        if (queryResponse != null) return queryResponse;
        if (message.header().kind() != WireMessageKind.COMMAND) {
            return rejected(CoreResultCode.INVALID_MESSAGE);
        }
        return applyCommandIngress(message, clusterTimestamp, clusterPosition);
    }

    /** Queries can borrow Lane state for a read fence; commands hand mutations to their Lane. */
    boolean requiresOwnerLaneAccessForPreparation(CoreMessage message) {
        if (message.header().kind() != WireMessageKind.QUERY) return false;
        var type = message.header().messageType();
        if (OrderBookQueryService.accountQuery(type) || OrderBookQueryService.riskAccountQuery(type)) return false;
        if (type == CoreMessageType.ORDER_PREFLIGHT_QUERY || type == CoreMessageType.LANE_METRICS_QUERY) return false;
        if (type == CoreMessageType.TRIGGER_ORDER_QUERY || type == CoreMessageType.USER_OPEN_TRIGGER_ORDERS_QUERY) return false;
        if (type == CoreMessageType.TREASURY_STATE_QUERY || type == CoreMessageType.FUNDING_PROGRESS_QUERY
                || type == CoreMessageType.SETTLEMENT_PROGRESS_QUERY) return false;
        if (type == CoreMessageType.LIQUIDATION_WORK_QUERY || type == CoreMessageType.PENDING_TRANSFER_QUERY) return false;
        if (type == CoreMessageType.OPEN_INTEREST_QUERY || type == CoreMessageType.INSTRUMENT_MAINTENANCE_QUERY) return false;
        return true;
    }

    CoreResponse applyClusterCommand(CoreMessage message, long timestamp, long position) {
        return applyClusterCommand(message, timestamp, position, null);
    }

    CoreResponse applyClusterCommand(CoreMessage message, long timestamp, long position,
                                     DecodedMatchingCommand decoded) {
        boolean entered = !runtimeState.asynchronousCommands();
        if (entered) runtimeState.enterAsynchronousCommandScope();
        try {
            return applyDecodedCommand(message, timestamp, position, decoded, true);
        } finally {
            if (entered) runtimeState.exitAsynchronousCommandScope();
        }
    }

    CoreResponse applyDecodedCommand(CoreMessage message, long timestamp, long position,
                                     DecodedMatchingCommand decoded, boolean independent) {
        return applyDecodedCommand(message, timestamp, position, decoded, independent, null);
    }

    CoreResponse applyDecodedCommand(CoreMessage message, long timestamp, long position,
                                     DecodedMatchingCommand decoded, boolean independent,
                                     CommandFingerprint fingerprint) {
        CommandFingerprint previousFingerprint = preparedIngressFingerprint;
        preparedIngressFingerprint = fingerprint;
        boolean previousAdmission = clusterPipelineAdmission;
        CoreMessage previousMessage = decodedIngressMessage;
        DecodedMatchingCommand previousCommand = decodedIngressCommand;
        clusterPipelineAdmission = independent;
        decodedIngressMessage = message;
        decodedIngressCommand = decoded;
        try {
            return apply(message, timestamp, position);
        } finally {
            preparedIngressFingerprint = previousFingerprint;
            clusterPipelineAdmission = previousAdmission;
            decodedIngressMessage = previousMessage;
            decodedIngressCommand = previousCommand;
        }
    }

    DecodedMatchingCommand decodeMatchingCommand(CoreMessage message) {
        return message == decodedIngressMessage && decodedIngressCommand != null
                ? decodedIngressCommand : DecodedMatchingCommand.decode(message);
    }

    boolean prepareClusterPipelineScope(CoreMessage message, ClusterCommandWindow window) {
        if (!activated()) activate();
        assertOwner();
        long user = message.header().userId();
        if (message.header().productLine() != productLine || user <= 0) {
            return rememberPipelineRoute(window, false);
        }
        window.resetCandidate(user);
        try {
            DecodedMatchingCommand decoded = window.decoded(message);
            switch (message.header().messageType()) {
                case PLACE_ORDER -> {
                    var command = decoded.placeOrder();
                    var instrument = runtimeState.instrument(command.instrumentId());
                    if (instrument != null && instrument.usesOpenInterestForUserLimit() && !command.reduceOnly())
                        return rememberPipelineRoute(window, false);
                    window.route(decoded.matcherShard(matchingAdapter, command.instrumentId()),
                            runtimeState.topology().accountLaneMask(user));
                    return rememberPipelineRoute(window, true);
                }
                case CANCEL_ORDER -> {
                    long orderId = decoded.cancelOrder().orderId();
                    var route = activeOrderIndex.activeOrderRoute(orderId);
                    if (route == null || route.userId() != user || route.instrumentId() == null
                            || route.instrumentId().isBlank()) return rememberPipelineRoute(window, false);
                    window.route(matchingAdapter.matcherShardId(route.instrumentId()),
                            runtimeState.topology().accountLaneMask(user));
                    return rememberPipelineRoute(window, true);
                }
                case PLACE_ORDER_BATCH -> {
                    int shard = -1;
                    for (var order : decoded.placeOrderBatch().orders()) {
                        var instrument = runtimeState.instrument(order.instrumentId());
                        if (instrument != null && instrument.usesOpenInterestForUserLimit() && !order.reduceOnly())
                            return rememberPipelineRoute(window, false);
                        int current = decoded.matcherShard(matchingAdapter, order.instrumentId());
                        if (shard >= 0 && current != shard) return rememberPipelineRoute(window, false);
                        if (shard < 0) {
                            shard = current;
                            window.route(shard, runtimeState.topology().accountLaneMask(user));
                        }
                    }
                    return rememberPipelineRoute(window, shard >= 0);
                }
                case CANCEL_ORDER_BATCH -> {
                    int shard = -1;
                    for (var order : decoded.cancelOrderBatch().orders()) {
                        var route = activeOrderIndex.activeOrderRoute(order.orderId());
                        if (route == null || route.userId() != user || route.instrumentId() == null
                                || route.instrumentId().isBlank()) return rememberPipelineRoute(window, false);
                        int current = matchingAdapter.matcherShardId(route.instrumentId());
                        if (shard >= 0 && current != shard) return rememberPipelineRoute(window, false);
                        shard = current;
                    }
                    window.route(shard, runtimeState.topology().accountLaneMask(user));
                    return rememberPipelineRoute(window, true);
                }
                default -> { return rememberPipelineRoute(window, false); }
            }
        } catch (IllegalArgumentException | java.nio.BufferUnderflowException invalid) {
            return rememberPipelineRoute(window, false);
        }
    }

    private boolean rememberPipelineRoute(ClusterCommandWindow window, boolean eligible) {
        preparedRouteUserLaneBit = eligible ? window.routeUserLaneBit() : 0;
        preparedRouteMatcherShard = eligible ? window.routeMatcherShard() : -1;
        return eligible;
    }

    private CoreResponse applyCommandIngress(CoreMessage message, long clusterTimestamp, long clusterPosition) {
        CommandFingerprint fingerprint = message == decodedIngressMessage && preparedIngressFingerprint != null
                ? preparedIngressFingerprint : fingerprints.of(message);
        CoreResponse replayResponse = checkCommandReplay(message, fingerprint);
        if (replayResponse != null) return replayResponse;

        long lastSourceSequence = lastSourceSequences.lookupOrDefault(
                message.header().source(), message.header().sourceId(), -1);
        SourceKey sourceKey = lastSourceSequences.lastLookupKey();
        if (sourceKey == null) {
            sourceKey = new SourceKey(message.header().source(), message.header().sourceId());
        }
        if (isFundsIdempotencyCommand(message.header().messageType())) {
            directCommand.beginFundsCheck(message, fingerprint, sourceKey, clusterTimestamp,
                    clusterPosition, lastSourceSequence, terminalRetention);
            return null;
        }
        CoreResponse sourceSequenceResponse = checkSourceSequence(message, lastSourceSequence);
        if (sourceSequenceResponse != null) return sourceSequenceResponse;

        if (isMatchingCommand(message.header().messageType())) {
            if (pendingMatching.size() >= pendingMatching.capacity()) {
                throw new IllegalStateException("matcher dispatch window is exhausted after Cluster Log append");
            }
            if (isOrderBatchCommand(message.header().messageType())) {
                return batches.beginOrderBatchMatching(message, clusterTimestamp, clusterPosition,
                        sourceKey, fingerprint);
            }
            return admissions.beginMatching(message, clusterTimestamp, clusterPosition,
                    sourceKey, fingerprint);
        }
        return applyDirectCommand(message, clusterTimestamp, clusterPosition, sourceKey, fingerprint);
    }

    CoreResponse applyDirectCommand(CoreMessage message, long clusterTimestamp, long clusterPosition,
                                    SourceKey sourceKey, CommandFingerprint fingerprint) {
        ResponseStatus status;
        CoreResultCode resultCode = CoreResultCode.NONE;
        activateFactContext(message, fingerprint);
        long beforeRuntimeRevision = runtimeState.revision();
        long runtimeCommandCheckpoint = runtimeState.commandRevisionCheckpoint();
        long positionIdentityCheckpoint = identities.positionCheckpoint();
        directCommand.initialize(message, fingerprint, sourceKey, clusterTimestamp, clusterPosition,
                beforeRuntimeRevision, runtimeCommandCheckpoint, positionIdentityCheckpoint);
        resultBuilder.beginCommand();
        admissions.clearQueuedMatching();
        commits.beginCommitPublicationBatch();
        try {
            if (directCommands.dispatch(message, clusterTimestamp)) {
                status = ResponseStatus.APPLIED;
            } else {
                switch (message.header().messageType()) {
                    case PROBE_INCREMENT -> {
                        probeValue = Math.addExact(
                                probeValue, CoreProtocol.decodeProbeDelta(message.payloadUnsafe()));
                        status = ResponseStatus.APPLIED;
                    }
                    case VERIFY_STATE_HASH -> status = ResponseStatus.APPLIED;
                    case PLACE_ORDER, CANCEL_ORDER, REPLACE_ORDER, AMEND_ORDER,
                            PLACE_ORDER_BATCH, CANCEL_ORDER_BATCH, AMEND_ORDER_BATCH,
                            EXECUTE_LIQUIDATION, SETTLE_INSTRUMENT ->
                            throw new IllegalStateException("matching command must use async continuation");
                    default -> status = null;
                }
            }
        } catch (CoreStateRejectedException exception) {
            status = ResponseStatus.REJECTED;
            resultCode = CoreResultCode.fromRejectionCode(exception.code());
        } catch (ArithmeticException exception) {
            status = ResponseStatus.REJECTED;
            resultCode = CoreResultCode.ARITHMETIC_OVERFLOW;
        } catch (IllegalArgumentException exception) {
            status = ResponseStatus.REJECTED;
            resultCode = CoreResultCode.INVALID_COMMAND;
        }
        if (directCommand.hasControlWork()) return null;
        return finishDirectCommand(message, clusterTimestamp, clusterPosition, sourceKey, fingerprint,
                beforeRuntimeRevision, runtimeCommandCheckpoint,
                positionIdentityCheckpoint, status, resultCode);
    }

    private CoreResponse checkCommandReplay(CoreMessage message, CommandFingerprint fingerprint) {
        CommandResultLedger.StoredResult terminalDuplicate = resultLedger.get(message.header().commandId());
        if (terminalDuplicate != null) {
            return resultLedger.duplicateResponse(terminalDuplicate, message, fingerprint, appliedCommandCount);
        }
        CommandSlot pendingDuplicate = pendingMatching.findByCommandId(message.header().commandId());
        if (pendingDuplicate != null) {
            if (!pendingDuplicate.fingerprint().equals(fingerprint)) {
                return new CoreResponse(com.surprising.aeron.protocol.ResponseStatus.REJECTED,
                        com.surprising.aeron.protocol.ResponseStatus.REJECTED,
                        CoreResultCode.IDEMPOTENCY_CONFLICT, appliedCommandCount,
                        EMPTY_RESPONSE_DATA);
            }
            return new CoreResponse(com.surprising.aeron.protocol.ResponseStatus.DUPLICATE,
                    com.surprising.aeron.protocol.ResponseStatus.OK, matchingPendingCode(),
                    pendingDuplicate.sequence(),
                    EMPTY_RESPONSE_DATA);
        }
        return null;
    }

    CoreResponse fundsReplayResponse(CoreMessage message, CommandFingerprint fingerprint, CommandFingerprint retained) {
        if (retained == null) return null;
        if (!retained.matchesCommittedCommand(message, fingerprint)) {
            return new CoreResponse(com.surprising.aeron.protocol.ResponseStatus.REJECTED,
                    com.surprising.aeron.protocol.ResponseStatus.REJECTED,
                    CoreResultCode.IDEMPOTENCY_CONFLICT, appliedCommandCount,
                    EMPTY_RESPONSE_DATA);
        }
        return new CoreResponse(com.surprising.aeron.protocol.ResponseStatus.DUPLICATE,
                    com.surprising.aeron.protocol.ResponseStatus.APPLIED, CoreResultCode.NONE,
                    appliedCommandCount, EMPTY_RESPONSE_DATA);
    }

    CoreResponse checkSourceSequence(CoreMessage message, long lastSourceSequence) {
        if (lastSourceSequence >= 0 && message.header().sourceSequence() <= lastSourceSequence) {
            return new CoreResponse(com.surprising.aeron.protocol.ResponseStatus.DUPLICATE,
                    com.surprising.aeron.protocol.ResponseStatus.DUPLICATE,
                    CoreResultCode.STALE_SOURCE_SEQUENCE, appliedCommandCount);
        }
        if (lastSourceSequence < 0
                && lastSourceSequences.size() >= MAX_SOURCE_SEQUENCES) {
            return rejected(CoreResultCode.SOURCE_SEQUENCE_TRACKING_FULL);
        }
        return null;
    }

    CoreResponse finishDirectCommand(CoreMessage message, long clusterTimestamp, long clusterPosition,
            SourceKey sourceKey, CommandFingerprint fingerprint,
            long beforeRuntimeRevision, long runtimeCommandCheckpoint, long positionIdentityCheckpoint,
            ResponseStatus status, CoreResultCode resultCode) {
        long nextAppliedCommandCount = Math.incrementExact(appliedCommandCount);
        if (directCommand.publicationComplete()) {
            return finishResponseAfterFundsRetention(message, clusterTimestamp, clusterPosition,
                    sourceKey, fingerprint, status, resultCode, nextAppliedCommandCount);
        }
        if (!directCommand.finalizationPrepared()) {
            if (status != ResponseStatus.APPLIED
                    && (commits.commitPublicationDirty()
                    || runtimeState.revision() != beforeRuntimeRevision
                    || runtimeState.hasUncommittedCommandChanges())) {
                rollbackCommandState(runtimeCommandCheckpoint, positionIdentityCheckpoint,
                        Math.incrementExact(appliedCommandCount));
            }
            if (status == null) {
                commits.abortCommitPublicationBatch();
                return finishDirectCommandContext(rejected(CoreResultCode.INVALID_MESSAGE));
            }
            if (status == ResponseStatus.APPLIED) {
                if (runtimeState.asynchronousCommands()) {
                    try {
                        if (!triggers.collectClosingTriggerIds().isEmpty()) {
                            commits.requestCommitPublication();
                        }
                    } catch (ArithmeticException failure) {
                        if (deferFinalizationRejection(CoreResultCode.ARITHMETIC_OVERFLOW)) return null;
                        throw failure;
                    }
                } else {
                    triggers.cancelTriggersForClosedPositions();
                }
                if (pendingMatching.size() + admissions.queuedMatching.size()
                        > pendingMatching.capacity()) {
                    admissions.clearQueuedMatching();
                    if (deferFinalizationRejection(CoreResultCode.MATCHING_BACKPRESSURE)) return null;
                    rollbackCommandState(runtimeCommandCheckpoint, positionIdentityCheckpoint,
                            Math.incrementExact(appliedCommandCount));
                    return finishDirectCommandContext(rejected(CoreResultCode.MATCHING_BACKPRESSURE));
                }
            }
            if (status == ResponseStatus.APPLIED) {
                java.util.List<Long> changedOrderIds = resultBuilder.commandChangedOrderIds == null
                        ? java.util.List.of() : resultBuilder.commandChangedOrderIds;
                try {
                    if (runtimeState.asynchronousCommands()) {
                        com.surprising.aeron.service.state.RuntimeOrderCommitStateTransitions.validateStampInputs(
                                clusterTimestamp, clusterPosition, changedOrderIds);
                    } else {
                        stampOrderChangesRuntime(clusterTimestamp, clusterPosition, changedOrderIds);
                    }
                } catch (IllegalStateException exception) {
                    if (deferFinalizationRejection(CoreResultCode.INVALID_COMMAND)) return null;
                    rollbackCommandState(runtimeCommandCheckpoint, positionIdentityCheckpoint,
                            Math.incrementExact(appliedCommandCount));
                    return finishDirectCommandContext(rejected(CoreResultCode.INVALID_COMMAND));
                }
            }
            resultBuilder.materializeDirectChangeAccumulators();
            if (status == ResponseStatus.APPLIED && !resultBuilder.commandChangedUserIds.isEmpty()) {
                if (runtimeState.asynchronousCommands()) {
                    runtimeState.releaseCompletedSequentialLaneStage();
                    directCommand.commitEvent(runtimeState.dispatchLaneMutation(
                            nextAppliedCommandCount, resultBuilder.commandChangedUserIds,
                            resultBuilder.commandChangedOrderIds, triggers.closingTriggerIds(),
                            clusterTimestamp, clusterPosition));
                } else {
                    runtimeState.stageLaneMutation(nextAppliedCommandCount,
                            resultBuilder.commandChangedUserIds);
                }
            }
            directCommand.markFinalizationPrepared();
        }
        if (directCommand.commitEvent() != null) {
            if (!runtimeState.laneCommitComplete(directCommand.commitEvent())) {
                directCommand.result(status, resultCode);
                return null;
            }
            runtimeState.releaseLaneCommit(directCommand.commitEvent());
            directCommand.clearCommitEvent();
        }
        if (status == ResponseStatus.APPLIED && !runtimeState.pollControlFundsValidation(
                message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.ADJUST_BALANCE
                || message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.TRANSFER_OUT
                || message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.TRANSFER_IN
                || message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.ADJUST_INSURANCE_FUND)) {
            directCommand.result(status, resultCode);
            return null;
        }
        directCommand.clearFinalizationPrepared();
        commits.completeCommitPublicationBatch();
        if (status == ResponseStatus.APPLIED) validateFundsConservation(message);
        directCommand.markPublicationComplete();
        return finishResponseAfterFundsRetention(message, clusterTimestamp, clusterPosition,
                sourceKey, fingerprint, status, resultCode, nextAppliedCommandCount);
    }

    private CoreResponse finishResponseAfterFundsRetention(CoreMessage message, long clusterTimestamp,
            long clusterPosition, SourceKey sourceKey, CommandFingerprint fingerprint,
            ResponseStatus status, CoreResultCode resultCode, long nextAppliedCommandCount) {
        if (isFundsIdempotencyCommand(message.header().messageType())
                && status == ResponseStatus.APPLIED) {
            if (!directCommand.retainFundsOnLedger(this)) {
                directCommand.result(status, resultCode);
                return null;
            }
        }
        recordAppliedSequence(nextAppliedCommandCount, sourceKey, message.header().sourceSequence());
        admissions.appendQueuedMatching(clusterTimestamp, clusterPosition);
        byte[] responseData = resultBuilder.commandResultData();
        int responseOffset = resultBuilder.responseDataOffset();
        int responseLength = resultBuilder.responseDataLength();
        resultLedger.storeOwnedResult(message.header().commandId(), fingerprint, status, resultCode,
                appliedCommandCount, responseData,
                responseOffset, responseLength);
        CoreResponse response = message.ownedResponse(status, status, resultCode, appliedCommandCount,
                responseData, responseOffset, responseLength);
        resultBuilder.transferResponseOwnership();
        return finishDirectCommandContext(response);
    }

    private CoreResponse finishDirectCommandContext(CoreResponse response) {
        directCommand.clear();
        return finishFactContext(response);
    }

    private boolean deferFinalizationRejection(CoreResultCode code) {
        if (!runtimeState.asynchronousCommands()) return false;
        if (!directCommand.active()) {
            throw new IllegalStateException("asynchronous finalization has no command context");
        }
        directCommand.result(ResponseStatus.REJECTED, code);
        return true;
    }
    /** 唯一直接控制命令的续步槽；与订单撮合序号槽分离。 */
    public final DirectCommandSlot directCommand = new DirectCommandSlot();

    boolean hasPendingDirectCommand() { return directCommand.active(); }

    CoreResponse pollDirectCommand() { return directCommand.poll(this); }

    /** Dispatch once from either preparation or ordered commit; collection stays at commit. */

    void recordSourceSequence(SourceKey sourceKey, long sourceSequence) {
        lastSourceSequences.put(sourceKey, sourceSequence);
    }

    static boolean isMatchingCommand(CoreMessageType type) {
        return type == CoreMessageType.PLACE_ORDER || type == CoreMessageType.CANCEL_ORDER
                || type == CoreMessageType.REPLACE_ORDER || type == CoreMessageType.AMEND_ORDER
                || isOrderBatchCommand(type)
                || type == CoreMessageType.EXECUTE_LIQUIDATION
                || type == CoreMessageType.EXECUTE_LIQUIDATION_BATCH
                || type == CoreMessageType.SETTLE_INSTRUMENT;
    }

    static boolean isOrderBatchCommand(CoreMessageType type) {
        return type == CoreMessageType.PLACE_ORDER_BATCH || type == CoreMessageType.CANCEL_ORDER_BATCH
                || type == CoreMessageType.AMEND_ORDER_BATCH;
    }

    CoreResponse finishFactContext(CoreResponse response) {
        clearFactContext();
        return response;
    }

    /** Releases the transport-side reference after the response has been encoded or copied. */
    void releaseResponse(CoreResponse response) {
        if (response != null) responseArena.release(response.dataUnsafe());
    }

    void activateFactContext(CoreMessage command, CommandFingerprint fingerprint) {
        if (command == null || fingerprint == null) {
            throw new IllegalArgumentException("complete commit context is required before mutation");
        }
        commits.publication.assertHealthy();
        factContextActive = true;
    }

    void clearFactContext() {
        factContextActive = false;
    }

    CoreResponse admissionRejected(CoreResultCode resultCode) {
        currentClusterTimestamp = admissionPreviousClusterTimestamp;
        currentClusterPosition = admissionPreviousClusterPosition;
        return rejected(resultCode);
    }

    public record LifecycleOrderChunk(List<CancellationOrder> orders, long nextCursorOrderId) {
        public boolean more() { return nextCursorOrderId != 0; }
    }

    public com.surprising.aeron.service.matching.MatchingResult takeMatchingResult(long sequence) {
        return matchingFlow.takeMatchingResult(sequence);
    }
    static boolean isCommitCursorSafeWhileMatching(CoreMessage message) {
        return isNonFencingQuery(message) || (message.header().kind() == WireMessageKind.COMMAND
                && isMatchingCommand(message.header().messageType()));
    }

    // Operational counters, not a coherent account/order snapshot. Business queries retain
    // the ingress fence, including command-result queries which must not overtake writes.
    static boolean isNonFencingQuery(CoreMessage message) {
        return message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.LANE_METRICS_QUERY;
    }

    static boolean accountLaneReadQuery(CoreMessageType type) {
        return switch (type) {
            case STATE_HASH_QUERY, BUSINESS_STATE_HASH_QUERY,
                    USER_STATE_HASH_QUERY, ORDER_STATE_HASH_QUERY, USER_STATE_QUERY, ORDER_STATE_QUERY,
                    CLIENT_ORDER_STATE_QUERY, USER_OPEN_ORDERS_QUERY, TRIGGER_ORDER_QUERY,
                    USER_OPEN_TRIGGER_ORDERS_QUERY, FUNDING_PROGRESS_QUERY, SETTLEMENT_PROGRESS_QUERY,
                    ADL_CANDIDATE_QUERY, RISK_STATE_QUERY, ALGO_ORDER_QUERY, LIQUIDATION_WORK_QUERY,
                    ORDER_PREFLIGHT_QUERY -> true;
            default -> false;
        };
    }

    static boolean singleUserLaneQuery(CoreMessageType type) {
        return switch (type) {
            case USER_STATE_HASH_QUERY, USER_STATE_QUERY, CLIENT_ORDER_STATE_QUERY,
                    USER_OPEN_ORDERS_QUERY, USER_OPEN_TRIGGER_ORDERS_QUERY, RISK_STATE_QUERY,
                    ORDER_PREFLIGHT_QUERY -> true;
            default -> false;
        };
    }

    boolean isMatchingPending(long msb, long lsb) {
        return pendingMatching.findByCommandId(msb, lsb) != null;
    }

    boolean isMatchingPending(UUID commandId) {
        return commandId != null && isMatchingPending(commandId.getMostSignificantBits(), commandId.getLeastSignificantBits());
    }

    long matchingSequence(long msb, long lsb) {
        CommandSlot pending = pendingMatching.findByCommandId(msb, lsb);
        return pending == null ? 0 : pending.sequence();
    }

    long matchingSequence(UUID commandId) {
        return commandId == null ? 0 : matchingSequence(commandId.getMostSignificantBits(), commandId.getLeastSignificantBits());
    }

    /** Fast Owner-side gate for the matching completion pump. */
    boolean hasMatchingDrainWork() { return matchingProgress.hasDrainWork(); }

    /**
     * Reads the existing Lane/financial completion held by the ordered Owner head. Completion
     * bits remain authoritative: the Ledger's release completion has no Matcher queue cursor,
     * so both wake-up and polling gates must also observe the settlement event itself.
     */
    boolean hasCompletedLaneCommit() {
        var directCommit = directCommand.commitEvent();
        if (directCommit != null && directCommit.complete()) return true;

        CommandSlot pending = pendingMatching.head();
        if (pending == null) return false;
        var settlement = pending.settlementEvent();
        if (settlement != null && settlement.complete()) return true;
        if (pending.commitEvent != null && pending.commitEvent.complete()) return true;
        OrderBatchPending batch = pending.orderBatch;
        if (batch == null) return false;
        // Batch facts are retained on the batch, not on the ordinary command slot. Ledger can
        // finish after every Matcher/Lane notification was consumed, so read these same facts.
        if (batch.itemSettlementEvent != null && batch.itemSettlementEvent.complete()) return true;
        if (batch.settlementEvent != null && batch.settlementEvent.complete()) return true;
        return batch.laneCommitEvent != null && batch.laneCommitEvent.complete();
    }

    /** Consumes Lane/Matcher notifications and advances the ordered matching pipeline. */
    void drainMatchingCompletions() { matchingProgress.drainMatchingCompletions(); }

    long matchingProgressSequence() {
        return pendingMatching.dispatchRevision();
    }

    boolean hasLocalMatchingWork() { return matchingProgress.hasLocalMatchingWork(); }

    /** Only external completion cursors; the caller must first exhaust owner-local progress. */
    boolean hasMatchingNotifications() { return matchingProgress.hasNotifications(); }

    boolean hasMatchingNotifications(long now) { return matchingProgress.hasNotifications(now); }

    int matchingCompletionHighWaterMark() {
        return matcherPipeline.completionHighWaterMark();
    }

    int dispatchedSettlementHighWaterMark() {
        return commits.dispatchedSettlementHighWaterMark;
    }

    int matchingCompletionCapacity() {
        return matcherPipeline.capacity();
    }

    @FunctionalInterface
    interface MatchingCommitHandler {
        void onCommitted(long sequence, CoreResponse response);
    }

    public int pendingMatchingCount() {
        return pendingMatching.size();
    }

    public long terminalTradeCount() {
        assertOwner();
        return terminalTradeCount;
    }

    public CoreLaneMetrics laneMetrics() {
        var metrics = com.surprising.aeron.protocol.CoreLaneMetricsCodec.decode(encodeLaneMetrics());
        return new CoreLaneMetrics(
                metrics.matchingEngineCount(), metrics.accountLaneCount(),
                metrics.matcherDispatchDepth(), metrics.matcherDispatchCapacity(),
                metrics.matcherDispatchHighWaterMark(), metrics.matchingCompletionDepth(),
                metrics.matchingCompletionCapacity(), metrics.matchingCompletionHighWaterMark(),
                metrics.commandContextDepth(), metrics.commandContextCapacity(),
                metrics.commandContextHighWaterMark(), metrics.committedCoreSequence(),
                metrics.accountLaneRevisions(), metrics.accountLaneAppliedSequences(),
                metrics.accountLaneCommittedSequences(), metrics.accountLaneQueueDepths(),
                metrics.accountLaneQueueCapacities(), metrics.accountLaneQueueHighWaterMarks(),
                metrics.accountLaneRejectedSubmissions(), metrics.accountLaneOldestPendingSequences(),
                metrics.accountLaneCompletedOperations(), metrics.accountLaneLatencySamples(),
                metrics.accountLaneTotalLatencyNanos(),
                metrics.accountLaneMaxLatencyNanos());
    }

    byte[] encodeLaneMetrics() {
        assertOwner();
        int count = matchingAdapter.topology().accountLaneCount();
        var encoder = new com.surprising.aeron.protocol.CoreLaneMetricsCodec.Encoder(
                matchingAdapter.topology().matchingEngineCount(), count,
                matcherPipeline.submissionDepth(), matcherPipeline.capacity(),
                matcherPipeline.submissionHighWaterMark(), matcherPipeline.completionDepth(),
                matcherPipeline.capacity(), matcherPipeline.completionHighWaterMark(),
                pendingMatching.inFlight(), pendingMatching.capacity(),
                pendingMatching.highWaterMark(), committedCoreSequence);
        for (int laneId = 0; laneId < count; laneId++) {
            runtimeState.writeAccountLaneMetrics(laneId, encoder);
        }
        return encoder.finish();
    }

    public long firstPendingMatchingSequence() {
        return pendingMatching.firstSequence();
    }

    boolean hasPendingMatchingForUser(long userId) {
        return pendingMatching.hasUser(userId);
    }

    Map<Long, CommandSlot> pendingMatching() {
        return pendingMatching.snapshot();
    }

    com.surprising.aeron.service.state.LaneTopology laneTopology() {
        return matchingAdapter.topology();
    }

    List<com.surprising.aeron.service.state.AccountLaneSnapshot> accountLaneSnapshots(
            long fenceSequence, TradingCoreState globalState) {
        return runtimeState.accountLaneSnapshots(fenceSequence, globalState);
    }

    void restoreAccountLaneSnapshots(
            List<com.surprising.aeron.service.state.AccountLaneSnapshot> snapshots,
            long fenceSequence) {
        if (activated) throw new IllegalStateException("account lanes must restore before activation");
        runtimeState.restoreAccountLaneSnapshots(snapshots, fenceSequence, snapshotTradingState());
    }

    void activate() {
        if (activated) return;
        if (closed) throw new IllegalStateException("cannot activate closed core state");
        try {
            matcherPipeline.start(matchingAdapter::activateShard);
            bindOwner();
            runtimeState.startAccountLanes();
            runtimeState.startSystemLedgerLane();
            matchingAdapter.activate();
            commits.publication.activate();
            activated = true;
        } catch (RuntimeException failure) {
            try {
                close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    boolean activated() { return activated; }

    CommandSlot pendingMatching(long sequence) {
        return pendingMatching.get(sequence);
    }

    int terminalRetentionCandidateCount() {
        return terminalRetention.candidateCount();
    }

    int terminalRetentionTombstoneCount() {
        return terminalRetention.tombstoneCount();
    }

    int runtimeClientIdentityCount() {
        return identities.clientIdentityCount();
    }

    TerminalStateRetention terminalRetention() {
        return terminalRetention;
    }

    public long stateHash() {
        return currentBusinessStateHash();
    }

    byte[] captureSnapshot(long clusterTimestamp, long clusterPosition, long nowNanos) {
        byte[] snapshot = snapshots.pollSnapshot(clusterTimestamp, clusterPosition, nowNanos);
        if (snapshot != null) return snapshot;
        if (snapshots.snapshotFence != null && snapshots.snapshotFence.encodedSnapshot == null) snapshots.releaseSnapshotFence();
        throw new SnapshotNotReadyException();
    }

    static final class SnapshotNotReadyException extends IllegalStateException {
        SnapshotNotReadyException() {
            super("snapshot not ready");
        }
    }

    static final class SnapshotFenceTimeoutException extends IllegalStateException {
        SnapshotFenceTimeoutException() {
            super("snapshot fence timed out");
        }
    }

    @FunctionalInterface
    interface MatcherSnapshotCapture {
        CompletableFuture<MatcherSnapshot> capture(
                long snapshotId,
                long coreSequence,
                long businessStateHash,
                TradingCoreState state,
                Iterable<CoreOrderState> activeOrders);
    }

    @FunctionalInterface
    interface SnapshotEncoder {
        CompletableFuture<SectionedCoreSnapshotCodec.SectionedSnapshot> encode(CoreSnapshotImage image);
    }

    FatalMatchingDivergenceException failMatching(
            CommandSlot pending,
            String detail,
            Throwable cause) {
        try {
            matchingAdapter.poisonFromOwner("deterministic matcher settlement failed sequence="
                    + pending.sequence() + " operation=" + pending.operation());
        } catch (RuntimeException poisonFailure) {
            if (cause != null) cause.addSuppressed(poisonFailure);
            else cause = poisonFailure;
        }
        fatalFailure = cause == null
                ? new FatalMatchingDivergenceException(pending.operation().name(), pending.sequence(), 0, detail)
                : new FatalMatchingDivergenceException(pending.operation().name(), pending.sequence(), 0, detail, cause);
        return fatalFailure;
    }

    void assertClusterCallbackComplete() {
        if (!activated) activate();
        assertOwner();
        assertHealthy();
        if (directCommand.active() || !pendingMatching.isEmpty()
                || !bookQueries.queryIds.isEmpty()) {
            throw new IllegalStateException("unfinished business work outside cluster log callback"
                    + " direct=" + directCommand.active()
                    + " matching=" + !pendingMatching.isEmpty()
                    + " book=" + !bookQueries.queryIds.isEmpty());
        }
    }

    void assertHealthy() {
        terminalRetention.assertHealthy();
        if (fatalFailure != null) throw fatalFailure;
        if (commitPublicationFailure != null) throw commitPublicationFailure;
        if (runtimeState != null) runtimeState.assertAccountLanesHealthy();
        commits.publication.assertHealthy();
        RuntimeException auditFailure = snapshots.snapshotAuditFailure.get();
        if (auditFailure != null) throw auditFailure;
    }

    public static TradingCoreRuntime fromSnapshot(ProductLine productLine, byte[] snapshot) {
        return SectionedCoreSnapshotCodec.decode(snapshot, productLine);
    }

    public static CoreSnapshotManifest inspectSnapshot(ProductLine productLine, byte[] snapshot) {
        return SectionedCoreSnapshotCodec.manifest(snapshot, productLine);
    }

    public ProductLine productLine() {
        return productLine;
    }

    public long appliedCommandCount() {
        return appliedCommandCount;
    }

    public long committedCoreSequence() {
        return committedCoreSequence;
    }

    /** 为流水线命令预分配下一个单调自增序号。由唯一 Owner 线程调用。 */
    long nextAppliedSequence() {
        assertOwner();
        return Math.incrementExact(appliedCommandCount);
    }

    /**
     * 确认推进已应用命令序号至指定序号，同步刷新已提交全局水位，并登记来源序号幂等去重。
     * 由唯一 Owner 线程在命令正式入队或应用时调用。
     */
    void recordAppliedSequence(long sequence, SourceKey sourceKey, long sourceSequence) {
        assertOwner();
        this.appliedCommandCount = sequence;
        refreshCommittedCoreSequence();
        if (sourceKey != null) {
            recordSourceSequence(sourceKey, sourceSequence);
        }
    }

    /**
     * 刷新全局已提交水位；根据在途撮合环首序列号或已应用命令计数推进，
     * 并同步通知账户 Lane 释放终态订单回收栅栏。
     */
    void refreshCommittedCoreSequence() {
        assertOwner();
        long candidate = pendingMatching.isEmpty()
                ? appliedCommandCount
                : Math.subtractExact(pendingMatching.firstSequence(), 1);
        if (candidate > committedCoreSequence) {
            committedCoreSequence = candidate;
        }
        runtimeState.releaseRetiredOrderFence(committedCoreSequence);
    }

    long committedProjectionSequence() {
        return commits.publication.publishedSequence();
    }

    public long probeValue() {
        return probeValue;
    }

    public TradingCoreState tradingState() {
        runtimeState.requireSnapshotFenceReady();
        boolean acquired = runtimeState.accountLanesStarted() && !runtimeState.ownerLaneAccess;
        try {
            if (acquired) {
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (!runtimeState.tryAcquireOwnerLaneAccess()) {
                    if (System.nanoTime() - deadline >= 0) {
                        throw new IllegalStateException("timed out waiting for owner lane access in current state materialization");
                    }
                    Thread.onSpinWait();
                }
            }
            long currentRevision = runtimeState.revision();
            long currentMarketRevision = runtimeState.marketRevision();
            long currentSequence = commits.publication.publishedSequence();
            if (cachedTradingState == null || cachedRevision != currentRevision || cachedMarketRevision != currentMarketRevision || cachedSequence != currentSequence) {
                cachedTradingState = com.surprising.aeron.service.state.RuntimeStateMaterializer.materialize(runtimeState, identities);
                cachedRevision = currentRevision;
                cachedMarketRevision = currentMarketRevision;
                cachedSequence = currentSequence;
            }
            return cachedTradingState;
        } finally {
            if (acquired) runtimeState.releaseOwnerLaneAccess();
        }
    }

    int incompleteRiskScanCount() { return runtimeState.incompleteRiskScanCount(); }
    int incompleteFundingCount() { return runtimeState.treasury().incompleteFundingCount(); }
    int activeOrderCount() { return activeOrderIndex.count(); }
    int positionCount() { return adlPositionIndex.count(); }
    int triggerOrderCount() { return triggerOrderIndex.ids().size(); }

    TradingCoreState snapshotTradingState() {
        CoreSnapshotLifecycle.SnapshotFence fence = snapshots.snapshotFence;
        return fence != null && fence.snapshotState != null ? fence.snapshotState : tradingState();
    }
    long snapshotBusinessStateHash() { return currentBusinessStateHash(); }
    long snapshotProjectionSequence() { return commits.publication.publishedSequence(); }
    boolean runtimeRiskScanComplete() { return runtimeState.firstIncompleteRiskScan() == null; }
    boolean runtimeRiskScanComplete(String instrumentId) {
        RiskScanRuntime scan = runtimeRiskScan(instrumentId);
        return scan == null || scan.complete();
    }
    RiskScanRuntime runtimeRiskScan(String instrumentId) {
        Integer symbolId = identities.findSymbolId(instrumentId);
        return symbolId == null ? null : runtimeState.riskScan(symbolId);
    }
    MarkPriceRuntime runtimeMarkPrice(String instrumentId) {
        Integer symbolId = identities.findSymbolId(instrumentId);
        return symbolId == null ? null : runtimeState.markPrice(symbolId);
    }
    long runtimeFundingSettlement(String instrumentId) {
        Integer symbolId = identities.findSymbolId(instrumentId);
        return symbolId == null ? 0 : runtimeState.treasury().fundingSettlement(symbolId);
    }
    LiquidationRuntime runtimeLiquidation(long liquidationId) { return runtimeState.liquidation(liquidationId); }
    boolean snapshotHasPendingCommands() {
        return factContextActive || directCommand.active() || pendingMatching.inFlight() != 0;
    }
    Map<Long, com.surprising.aeron.service.state.model.CoreFeePolicyState> feePolicies() {
        return runtimeState.feePoliciesSnapshot();
    }
    void restoreFeePolicies(Map<Long, com.surprising.aeron.service.state.model.CoreFeePolicyState> policies) {
        runtimeState.restoreFeePolicies(policies);
    }
    Map<Long, com.surprising.aeron.service.state.account.TransferRuntime> pendingTransfers() {
        boolean acquired = runtimeState.accountLanesStarted() && !runtimeState.ownerLaneAccess;
        try {
            if (acquired) {
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (!runtimeState.tryAcquireOwnerLaneAccess()) {
                    if (System.nanoTime() - deadline >= 0) {
                        throw new IllegalStateException("timed out waiting for owner lane access in pendingTransfers");
                    }
                    Thread.onSpinWait();
                }
            }
            return runtimeState.pendingTransfersSnapshot();
        } finally {
            if (acquired) runtimeState.releaseOwnerLaneAccess();
        }
    }
    void restorePendingTransfers(Map<Long, com.surprising.aeron.service.state.account.TransferRuntime> transfers) {
        runtimeState.restorePendingTransfers(transfers);
    }
    long sourceSequenceDigest() {
        return lastSourceSequences.digest();
    }

    Map<UUID, StoredResult> commandResults() {
        return resultLedger.entries();
    }

    Map<SourceKey, Long> lastSourceSequences() {
        return Collections.unmodifiableMap(lastSourceSequences.snapshot());
    }


    public void cancelAllOcoSiblings(com.surprising.aeron.service.state.model.CoreTriggerOrderState trigger) {
        long cursor = Long.MAX_VALUE;
        while (true) {
            TriggerOrderCommands.OcoCancellationPage page = triggers.cancelOcoSiblings(
                    trigger, cursor, DEFAULT_TRIGGER_SCAN_BATCH_SIZE);
            if (page.complete()) return;
            if (page.workUnits() == 0) throw new IllegalStateException("OCO cancellation made no progress");
            cursor = page.nextCursor();
        }
    }

    void bindOwner() {
        Thread current = Thread.currentThread();
        if (owner != null && owner != current) {
            throw new IllegalStateException("trading runtime is bound to another thread");
        }
        owner = current;
        runtimeState.bindOwner();
        identities.assertOwner();
    }

    void assertOwner() {
        if (owner == null) bindOwner();
        else if (owner != Thread.currentThread()) {
            throw new IllegalStateException("trading runtime is bound to another thread");
        }
    }

    void deferProvisionalSnapshotProjection() {
        commits.deferProvisionalCommitPublication();
    }

    com.surprising.aeron.service.state.PlaceAdmissionEvent dispatchPlaceAdmission(
            long userId, PlaceOrderCommand command, UUID commandId, long coreSequence,
            long timestamp, long position) {
        var event = runtimeState.acquirePlaceAdmission();
        com.surprising.aeron.service.state.TreasuryRuntime.SettlementStatus settlementStatus;
        int assetId;
        try {
            CoreOrderDecisionResolver.prepare(event, runtimeState, identities, userId,
                    command, currentClusterTimestamp);
            settlementStatus = runtimeState.treasury().settlementStatus(event.symbolId());
            var instrument = event.instrument();
            assetId = identities.assetId(productLine.isDerivative() ? instrument.settleAsset()
                    : command.side() == com.surprising.aeron.protocol.CoreOrderSide.BUY
                    ? instrument.quoteAsset() : instrument.baseAsset());
        } catch (RuntimeException | Error failure) {
            runtimeState.discardPlaceAdmission(event);
            throw failure;
        }
        return runtimeState.dispatchPlaceAdmission(coreSequence, userId, event, commandId,
                settlementStatus,
                assetId, identities, timestamp, position);
    }

    public void reservePlaceOrderRuntime(long userId, PlaceOrderCommand command, UUID commandId,
                                  long pendingCoreSequence) {
        if (runtimeState.asynchronousCommands())
            throw new IllegalStateException("active trigger admission requires its Account Lane continuation");
        ResolvedPlaceOrder resolved = CoreOrderDecisionResolver.resolve(runtimeState,
                identities, userId, command, currentClusterTimestamp);
        var admissionIdentity = com.surprising.aeron.service.state.RuntimeOrderAdmission.admissionIdentity(
                runtimeState, identities, userId, resolved);
        long openInterestSteps = resolved.instrument().usesOpenInterestForUserLimit() && !command.reduceOnly()
                ? openInterestIndex.openInterestSteps(command.instrumentId()) : 0;
        int symbolId = resolved.symbolId();
        int assetId = identities.assetId(resolved.reservationAsset());
        runtimeState.executeUserSettlement(userId, () -> {
            var preparedClientKey = identities.prepareClientKeyInCurrentLane(
                    userId, resolved.clientOrderId());
            try {
                long requiredReservation = com.surprising.aeron.service.state.RuntimeOrderAdmission.requiredReservationPrepared(
                        runtimeState, runtimeState.accountLaneForUser(userId), userId, resolved, openInterestSteps, runtimeState.admissionOrders(userId, resolved.symbolId()), admissionIdentity);
                RuntimeOrderStateTransitions.placePrepared(runtimeState, userId, resolved,
                        commandId, requiredReservation,
                        com.surprising.aeron.service.state.RuntimeIdentityRegistry.clientKeyValue(preparedClientKey),
                        symbolId, assetId);
                runtimeState.markPendingReservation(userId, resolved.orderId(), pendingCoreSequence);
                return null;
            } catch (RuntimeException | Error failure) {
                if (preparedClientKey != 0) {
                    identities.rollbackClientKeyInCurrentLane(
                            userId, resolved.clientOrderId(), preparedClientKey);
                }
                throw failure;
            }
        });
        if (!batches.hasPendingBatches()) deferProvisionalSnapshotProjection();
        else commits.requestCommitPublication();
    }

    ResolvedMatchingAdmission requireMatchingAdmission(CommandSlot pending) {
        ResolvedMatchingAdmission admission = pending.matchingAdmission();
        if (admission == null) throw new IllegalStateException("replace admission is missing");
        return admission;
    }



    OrderRuntime runtimeOrder(long orderId) {
        return runtimeState.hasChangedOrder(orderId) ? runtimeState.changedOrderValue(orderId)
                : runtimeState.order(orderId);
    }

    String runtimeOrderSymbol(OrderRuntime order) {
        return identities.instrumentId(order.symbolId());
    }

    String runtimeLiquidationSymbol(
            com.surprising.aeron.service.state.LiquidationRuntime liquidation) {
        return identities.instrumentId(liquidation.symbolId());
    }

    com.surprising.aeron.service.state.TreasuryRuntime.LifecycleProgressRuntime
            runtimeLifecycleProgress(String instrumentId) {
        Integer symbolId = identities.findSymbolId(instrumentId);
        return symbolId == null ? null : runtimeState.treasury().lifecycleProgress(symbolId);
    }



    void stampOrderChangesRuntime(long timestamp, long clusterPosition,
                                          Iterable<Long> changedOrderIds) {
        if (RuntimeOrderCommitStateTransitions.stampChangedOrdersByLane(
                runtimeState, timestamp, clusterPosition,
                changedOrderIds, resultBuilder.commandChangedUserIds)) {
            commits.requestCommitPublication();
        }
    }



    void suspendMatchingCommitContext(CommandSlot pending) {
        if (!commits.commitPublicationDeferred() || pending == null
                || pending.settlementEvent() == null
                && pending.orderBatch == null
                && !commits.controlPending(pending.sequence())) {
            throw new IllegalStateException("matching commit context cannot be suspended");
        }
        // The continuation owns the primitive change buffers until it is restored.  Transfer
        // those buffers to the sequence slot instead of materializing boxed Lists and copying
        // them back when the ordered commit resumes.
        seedChangeAccumulators();
        runtimeState.acceptChangedUserIds(resultBuilder.changedUserIds::add);
        CommandSlot context = pending;
        context.suspendCommitContext(
                resultBuilder, runtimeState.coldCommandFunds(),
                commits.commitPublicationDirty(), commits.commitPublicationProvisionalOnly());
        commits.suspendCommitPublication();
        resultBuilder.commandChangedUserIds = List.of();
        resultBuilder.commandChangedOrderIds = List.of();
        resultBuilder.resetChangeAccumulators();
        clearFactContext();
    }

    void restoreMatchingCommitContext(CommandSlot pending) {
        if (commits.commitPublicationDeferred() || factContextActive) {
            throw new IllegalStateException("another owner commit context is active");
        }
        CommandSlot context = pending;
        activateFactContext(pending.command(), pending.fingerprint());
        commits.restoreCommitPublication(context.commitSnapshotDirty(), context.commitSnapshotProvisionalOnly());
        context.restoreCommitContext(resultBuilder);
        resultBuilder.commandChangedUserIds = List.of();
        resultBuilder.commandChangedOrderIds = List.of();
        context.takeCommitFundsTo(runtimeState.coldCommandFunds());
        context.clearCommitContext();
        resultBuilder.commandTradeCount = 0;
        resultBuilder.commandLiquidationProgress = null;
        resultBuilder.commandLiquidationBatchResult = null;
        resultBuilder.commandRiskScanControl = null;
    }

    public void requireOrderIdentityAvailable(long userId, PlaceOrderCommand command) {
        if (command == null || terminalRetention.containsOrder(command.orderId(), userId, command.clientOrderId())) {
            throw new CoreStateRejectedException("DUPLICATE_ORDER_ID", "terminal order identity is retained");
        }
    }

    public boolean orderIdInUse(long orderId) {
        return activeOrderIndex.activeOrderRoute(orderId) != null
                || runtimeState.changedOrderValue(orderId) != null || terminalRetention.orderIdInUse(orderId);
    }

    void rollbackCommandState(
            long runtimeCheckpoint,
            long positionIdentityCheckpoint,
            long commandSequence) {
        commits.abortCommitPublicationBatch();
        runtimeState.rollbackActiveCommand(runtimeCheckpoint, commandSequence);
        identities.rollbackPositionKeys(positionIdentityCheckpoint);
    }

    public void queueTriggerMatching(com.surprising.aeron.service.state.model.CoreTriggerOrderState trigger,
                                      long triggerSequence, long triggeredPriceTicks,
                                      long triggeredAtEpochMillis, UUID parentCommandId, OrderRuntime childOrder) {
        if (childOrder == null || childOrder.userId() != trigger.userId())
            throw new IllegalStateException("trigger child admission receipt is missing");
        long parentMsb = parentCommandId == null ? 0L : parentCommandId.getMostSignificantBits();
        long parentLsb = parentCommandId == null ? 0L : parentCommandId.getLeastSignificantBits();
        long commandIdMsb = parentMsb ^ 0x545249474745524cL; // "TRIGGER"
        long commandIdLsb = parentLsb ^ trigger.triggerOrderId();
        CoreMessageHeader header = CoreMessageHeader.command(CoreMessageType.EXECUTE_TRIGGER_ORDER, commandIdMsb, commandIdLsb,
                productLine, com.surprising.aeron.protocol.CommandSource.OPERATIONS, trigger.userId(), 0,
                trigger.userId(), triggeredAtEpochMillis, 0);
        admissions.borrowQueuedMatching(
                new CoreMessage(header, com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeExecute(
                        trigger.triggerOrderId(), triggerSequence, triggeredPriceTicks, triggeredAtEpochMillis)),
                childOrder, trigger);
    }

    void setCommandFundsDelta(
            com.surprising.aeron.service.state.RuntimeFundsDelta fundsDelta) {
        if (runtimeState.systemLedgerStarted()) return;
        runtimeState.coldCommandFunds().clear();
        runtimeState.coldCommandFunds().add(fundsDelta);
    }

    void setCommandFunds(
            com.surprising.aeron.service.state.RuntimeFundsAccumulator fundsAccumulator) {
        if (runtimeState.systemLedgerStarted()) return;
        runtimeState.coldCommandFunds().clear();
        runtimeState.coldCommandFunds().add(fundsAccumulator);
    }

    void validateFundsConservation(CoreMessage command) {
        if (runtimeState.systemLedgerStarted()) return;
        boolean externalAdjustment = command.header().messageType() == CoreMessageType.ADJUST_BALANCE
                || command.header().messageType() == CoreMessageType.TRANSFER_OUT
                || command.header().messageType() == CoreMessageType.TRANSFER_IN
                || command.header().messageType() == CoreMessageType.ADJUST_INSURANCE_FUND;
        runtimeState.coldCommandFunds().requireConserved(externalAdjustment);
    }

    public void seedChangeAccumulators() {
        if (resultBuilder.commandChangedUserIds != null) resultBuilder.changedUserIds.addAll(resultBuilder.commandChangedUserIds);
        if (resultBuilder.commandChangedOrderIds != null) resultBuilder.changedOrderIds.addAll(resultBuilder.commandChangedOrderIds);
    }

    static <T> List<T> appendDistinct(List<T> existing, List<T> additions) {
        if (existing == null || existing.isEmpty()) return additions == null ? List.of() : additions;
        if (additions == null || additions.isEmpty()) return existing;
        if (additions.size() == 1 && existing.contains(additions.get(0))) return existing;
        java.util.LinkedHashSet<T> values = new java.util.LinkedHashSet<>();
        if (existing != null) values.addAll(existing);
        if (additions != null) values.addAll(additions);
        return List.copyOf(values);
    }

    static List<Long> boxedOrderIds(
            com.surprising.aeron.service.state.MatcherSettlementPlan plan) {
        if (plan == null || plan.orderCount() == 0) return List.of();
        return plan.orderIdList();
    }

    /** Collect lifecycle order identities without creating boxed Long elements. */
    static List<Long> boxedOrderIds(List<CancellationOrder> orders) {
        if (orders == null || orders.isEmpty()) return List.of();
        long[] values = new long[orders.size()];
        for (int index = 0; index < values.length; index++) values[index] = orders.get(index).orderId();
        return ImmutableLongArrayList.takeOwnership(values);
    }

    public int pendingRiskScanCount() {
        return runtimeState.incompleteRiskScanCount();
    }

    public void logRiskScan(String operation, String instrumentId, int batchSize, int pendingBefore, long startedAt) {
        long elapsedMicros = (System.nanoTime() - startedAt) / 1_000L;
        int pendingAfter = pendingRiskScanCount();
        if (!log.isDebugEnabled()) return;
        log.debug("risk scan operation={} instrumentId={} batchSize={} elapsedMicros={} "
                        + "pendingSymbolsBefore={} pendingSymbolsAfter={}",
                operation, instrumentId, batchSize, elapsedMicros, pendingBefore, pendingAfter);
    }

    @Override
    public void close() {
        if (closed) return;
        if (activated) assertOwner();
        closed = true;
        snapshots.releaseSnapshotFence();
        snapshots.inFlightMatcherSnapshot.set(null);
        matcherPipeline.closeShards(matchingAdapter::closeShard);
        runtimeState.endOrderBatchMutationScope();
        commits.publication.close();
        try { runtimeState.close(); }
        finally {
            // Account/Ledger workers must finish before their result slots or command references are cleared.
            bookQueries.clearPendingQueries();
            clearFactContext();
            directCommand.clear();
            batches.clearPendingBatches();
            pendingMatching.clear();
            crossShardCancellations.clear();
            admissions.pendingLifecycleScopes.clear();
            terminalRetention.close();
        }
    }

    void captureRealtimeTrades(CommandSlot pending) {
        var plan = pending.settlementPlan();
        OrderRuntime taker = pending.realtimeTakerOrder;
        ResolvedPlaceOrder resolvedTaker = pending.place.realtimeResolvedTakerOrder;
        pending.realtimeTakerOrder = null;
        pending.place.realtimeResolvedTakerOrder = null;
        if (realtimeCapture == null || !realtimeCapture.active()) return;
        try {
            for (int index = 0; index < plan.matcherEventCount(); index++) {
                MatcherEvent match = plan.matcherEvent(index);
                if (match.eventType() != MatcherEventType.TRADE) continue;
                if (resolvedTaker != null) {
                    realtimeCapture.trade(pending.command().header().userId(), resolvedTaker.orderId(),
                            resolvedTaker.symbolId(), resolvedTaker.side(), plan.coreSequence(), index,
                            match.price(), match.size(), match.matchedOrderId(), match.matchedOrderUid());
                } else {
                    realtimeCapture.trade(taker, plan.coreSequence(), index, match.price(), match.size(),
                            match.matchedOrderId(), match.matchedOrderUid());
                }
            }
        } catch (RuntimeException failure) { realtimeCapture.failed(); }
    }

    static long tradeCount(com.surprising.aeron.service.state.MatcherSettlementPlan plan) {
        long trades = 0;
        for (int index = 0; index < plan.matcherEventCount(); index++)
            if (plan.matcherEvent(index).eventType() == MatcherEventType.TRADE) trades++;
        return trades;
    }

    OrderRuntime responseOrder(long orderId) {
        return runtimeOrder(orderId);
    }

    long currentBusinessStateHash() {
        runtimeState.requireSnapshotFenceReady();
        boolean acquired = runtimeState.accountLanesStarted() && !runtimeState.ownerLaneAccess;
        try {
            if (acquired) {
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (!runtimeState.tryAcquireOwnerLaneAccess()) {
                    if (System.nanoTime() - deadline >= 0) {
                        throw new IllegalStateException("timed out waiting for owner lane access in businessStateHash");
                    }
                    Thread.onSpinWait();
                }
            }
            TradingCoreState current = tradingState();
            return canonicalBusinessStateHash(current.businessStateHash(),
                    runtimeState.feePoliciesSnapshot(), runtimeState.pendingTransfersSnapshot());
        } finally {
            if (acquired) runtimeState.releaseOwnerLaneAccess();
        }
    }

    static long canonicalBusinessStateHash(
            long base,
            Map<Long, com.surprising.aeron.service.state.model.CoreFeePolicyState> policies,
            Map<Long, com.surprising.aeron.service.state.account.TransferRuntime> transfers) {
        long feePolicyHash = computeFeePolicyHash(policies);
        if (feePolicyHash != 0) base = mix(base, feePolicyHash);
        long transferHash = computeTransferHash(transfers);
        return transferHash == 0 ? base : mix(base, transferHash);
    }

    static long computeTransferHash(
            Map<Long, com.surprising.aeron.service.state.account.TransferRuntime> transfers) {
        if (transfers.isEmpty()) return 0;
        long digest = HASH_OFFSET_BASIS;
        for (var transfer : transfers.values()) {
            var command = transfer.command();
            digest = mix(digest, transfer.userId());
            digest = mix(digest, command.transferId());
            digest = mix(digest, command.sourceUserId());
            digest = mix(digest, command.targetUserId());
            digest = mix(digest, com.surprising.aeron.service.state.snapshot.SnapshotEnumCodes.encode(command.sourceProductLine()));
            digest = mix(digest, com.surprising.aeron.service.state.snapshot.SnapshotEnumCodes.encode(command.targetProductLine()));
            digest = mixText(digest, command.sourceAccountType());
            digest = mixText(digest, command.targetAccountType());
            digest = mixText(digest, command.asset());
            digest = mix(digest, command.amountUnits());
            digest = mixText(digest, command.referenceId());
            digest = mixText(digest, command.reason());
        }
        return digest;
    }

    static boolean isFundsIdempotencyCommand(CoreMessageType type) {
        return type == CoreMessageType.ADJUST_BALANCE || type == CoreMessageType.TRANSFER_OUT
                || type == CoreMessageType.TRANSFER_IN || type == CoreMessageType.COMPLETE_TRANSFER;
    }

    static long computeFeePolicyHash(
            Map<Long, com.surprising.aeron.service.state.model.CoreFeePolicyState> policies) {
        if (policies.isEmpty()) return 0;
        long digest = HASH_OFFSET_BASIS;
        for (var policy : policies.values()) {
            digest = mix(digest, policy.policyId());
            digest = mix(digest, policy.policyRevision());
            digest = mix(digest, policy.userId());
            digest = mixText(digest, policy.instrumentId());
            digest = mix(digest, policy.makerFeeRatePpm());
            digest = mix(digest, policy.takerFeeRatePpm());
            digest = mix(digest, policy.sourcePriority());
            digest = mix(digest, policy.active() ? 1 : 0);
            digest = mix(digest, policy.effectiveFromEpochMillis());
            digest = mix(digest, policy.expireAtEpochMillis());
        }
        return digest;
    }

    static long mixText(long hash, String value) {
        if (value == null || value.isEmpty()) return mix(hash, 0);
        int length = value.length();
        boolean isAscii = true;
        for (int i = 0; i < length; i++) {
            if (value.charAt(i) >= 0x80) {
                isAscii = false;
                break;
            }
        }
        if (isAscii) {
            long result = mix(hash, length);
            for (int i = 0; i < length; i++) {
                result ^= value.charAt(i);
                result *= HASH_PRIME;
            }
            return result;
        }
        int utf8Len = 0;
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (c < 0x80) utf8Len++;
            else if (c < 0x800) utf8Len += 2;
            else if (Character.isHighSurrogate(c) && i + 1 < length && Character.isLowSurrogate(value.charAt(i + 1))) {
                utf8Len += 4;
                i++;
            } else if (!Character.isSurrogate(c)) utf8Len += 3;
            else utf8Len += 1;
        }
        long result = mix(hash, utf8Len);
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (c < 0x80) {
                result ^= c;
                result *= HASH_PRIME;
            } else if (c < 0x800) {
                result ^= (0xc0 | (c >>> 6));
                result *= HASH_PRIME;
                result ^= (0x80 | (c & 0x3f));
                result *= HASH_PRIME;
            } else if (!Character.isSurrogate(c)) {
                result ^= (0xe0 | (c >>> 12));
                result *= HASH_PRIME;
                result ^= (0x80 | ((c >>> 6) & 0x3f));
                result *= HASH_PRIME;
                result ^= (0x80 | (c & 0x3f));
                result *= HASH_PRIME;
            } else if (Character.isHighSurrogate(c) && i + 1 < length && Character.isLowSurrogate(value.charAt(i + 1))) {
                int point = Character.toCodePoint(c, value.charAt(++i));
                result ^= (0xf0 | (point >>> 18));
                result *= HASH_PRIME;
                result ^= (0x80 | ((point >>> 12) & 0x3f));
                result *= HASH_PRIME;
                result ^= (0x80 | ((point >>> 6) & 0x3f));
                result *= HASH_PRIME;
                result ^= (0x80 | (point & 0x3f));
                result *= HASH_PRIME;
            } else {
                result ^= '?';
                result *= HASH_PRIME;
            }
        }
        return result;
    }

    static long mix(long hash, long value) {
        long result = hash;
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
            result ^= (value >>> shift) & 0xff;
            result *= HASH_PRIME;
        }
        return result;
    }

    static long sourceSequenceDigest(Map<SourceKey, Long> sequences) {
        long digest = 0;
        for (Map.Entry<SourceKey, Long> entry : sequences.entrySet()) {
            digest ^= sourceSequenceDigest(entry.getKey(), entry.getValue());
        }
        return digest;
    }

    static long sourceSequenceDigest(SourceKey key, long sequence) {
        long digest = HASH_OFFSET_BASIS;
        digest = mix(digest, key.source().wireCode());
        digest = mix(digest, key.sourceId());
        return mix(digest, sequence);
    }

    CoreResponse rejected(CoreResultCode resultCode) {
        return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                resultCode, appliedCommandCount);
    }

    static CoreResultCode matchingPendingCode() {
        return CoreResultCode.fromWireCode(MATCHING_PENDING_WIRE_CODE);
    }

    static final class PipelinedBatchNotApplicable extends RuntimeException {
        PipelinedBatchNotApplicable() {
            super(null, null, false, false);
        }
    }

    record SourceKey(CommandSource source, long sourceId) {
    }

    public long querySequence(UUID queryId) { return bookQueries.querySequence(queryId); }
    public CoreResponse takeQueryResult(long queryId) { return bookQueries.takeQueryResult(queryId); }
    public byte[] snapshot() { return snapshots.snapshot(); }
    public byte[] snapshot(long snapshotId) { return snapshots.snapshot(snapshotId); }

    public CoreResponse completeMatching(long sequence,
                                  com.surprising.aeron.service.matching.MatchingResult matchingResult,
                                  long clusterTimestamp, long clusterPosition) { return commits.completeMatching(sequence, matchingResult, clusterTimestamp, clusterPosition); }

    static final class CrossShardCancellationCoordinator {
        private final TradingCoreRuntime owner;
        private final java.util.ArrayDeque<Progress> pending = new java.util.ArrayDeque<>();

        CrossShardCancellationCoordinator(TradingCoreRuntime owner) { this.owner = owner; }

        void start(CommandSlot command, java.util.List<com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter.CancellationOrder> orders) {
            if (command.crossShardCancellationStarted) return;
            command.crossShardCancellationStarted = true;
            pending.addLast(new Progress(command, orders));
            poll();
        }

        void poll() {
            for (int work = 0; work < 64 && !pending.isEmpty(); work++) {
                Progress progress = pending.peekFirst();
                if (progress.token != 0) {
                    var result = (com.surprising.aeron.service.matching.CoreMatchingResult) owner.matcherPipeline.pollControl(progress.shard, progress.token);
                    if (result == null) return;
                    progress.results.add(result);
                    progress.failed = !result.accepted();
                    progress.token = 0;
                    progress.index++;
                }
                if (progress.index < progress.orders.size()) {
                    if (progress.failed) {
                        progress.results.add(new com.surprising.aeron.service.matching.CoreMatchingResult(false, "NOT_SUBMITTED"));
                        progress.index++;
                        continue;
                    }
                    var order = progress.orders.get(progress.index);
                    progress.shard = owner.matchingAdapter.matcherShardId(order.instrumentId());
                    progress.token = owner.matcherPipeline.submitControl(progress.shard,
                            () -> owner.matchingAdapter.cancelControlOrder(progress.command.command().header().submittedAtEpochMillis(),
                                    order.userId(), order.orderId(), order.instrumentId()));
                    continue;
                }
                var command = progress.command;
                var evidenced = owner.matchingAdapter.completeControlCancellations(
                        command.sequence(), command.command().header().commandId(), 0,
                        command.command().header().submittedAtEpochMillis(), progress.orders, progress.results);
                pending.removeFirst();
                command.crossShardCancellationStarted = false;
                owner.matchingFlow.publishMatchingCompletion(command.sequence(), evidenced);
                command.matchingSubmitted();
                owner.matchingProgress.submissionCompleted(command);
            }
        }

        void clear() { pending.clear(); }

        boolean hasPending() { return !pending.isEmpty(); }

        private static final class Progress {
            final CommandSlot command;
            final java.util.List<com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter.CancellationOrder> orders;
            final java.util.ArrayList<com.surprising.aeron.service.matching.CoreMatchingResult> results;
            int index, shard;
            long token;
            boolean failed;
            Progress(CommandSlot command, java.util.List<com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter.CancellationOrder> orders) {
                this.command = command; this.orders = orders;
                results = new java.util.ArrayList<>(orders.size());
            }
        }
    }

    com.surprising.aeron.service.state.MatcherSettlementEvent prepareForMatching(CommandSlot pending) {
        if (pending.operation() == CommandSlot.Operation.REPLACE
                || pending.operation() == CommandSlot.Operation.AMEND) {
            return prepareReplacement(pending);
        }
        return switch (pending.operation()) {
            case PLACE -> preparePlace(pending);
            case TRIGGER -> prepareTrigger(pending);
            case CANCEL -> prepareCancellation(pending);
            default -> null;
        };
    }

    private com.surprising.aeron.service.state.MatcherSettlementEvent preparePlace(CommandSlot pending) {
        var admission = java.util.Objects.requireNonNull(pending.placeAdmission(), "place admission receipt");
        com.surprising.aeron.service.state.MatcherSettlementEvent direct = runtimeState.prepareDirectMatcherSettlement(
                pending.sequence(), directInitialLaneMask(admission.userId()), admission,
                pending.command().header().commandId(), matchingFlow.matcherShard(pending),
                identities, pending.commitFenceTimestamp(), pending.commitFenceClusterPosition(),
                pending.preMatchingCancellationOrderIds(), pending.laneResultTarget());
        reserveMatcherPublication(pending, direct);
        return direct;
    }

    private com.surprising.aeron.service.state.MatcherSettlementEvent prepareReplacement(CommandSlot pending) {
        var admission = requireMatchingAdmission(pending);
        com.surprising.aeron.service.state.MatcherSettlementEvent direct = runtimeState.prepareDirectReplacement(
                pending.sequence(), pending.sequence(), directInitialLaneMask(admission.userId()), admission,
                pending.command().header().commandId(), matchingFlow.matcherShard(pending), identities,
                pending.commitFenceTimestamp(), pending.commitFenceClusterPosition(),
                pending.preMatchingCancellationOrderIds(), pending.laneResultTarget());
        reserveMatcherPublication(pending, direct);
        if (realtimeCapture != null) pending.realtimeTakerOrder = direct.admittedOrder();
        return direct;
    }

    private com.surprising.aeron.service.state.MatcherSettlementEvent prepareTrigger(CommandSlot pending) {
        long[] execute = pending.decodedCommand().trigger();
        var trigger = java.util.Objects.requireNonNull(pending.sourceTrigger(),
                "admitted trigger is missing");
        com.surprising.aeron.service.state.OrderRuntime triggerOrder = java.util.Objects.requireNonNull(pending.triggerAdmissionOrder(),
                "trigger admission receipt is missing");
        com.surprising.aeron.service.state.MatcherSettlementEvent direct = runtimeState.prepareDirectMatcherSettlement(pending.sequence(),
                directInitialLaneMask(triggerOrder.userId()), triggerOrder, null, 1,
                pending.command().header().commandId(), matchingFlow.matcherShard(pending), identities,
                pending.commitFenceTimestamp(), pending.commitFenceClusterPosition(),
                pending.preMatchingCancellationOrderIds(), pending.laneResultTarget());
        direct.triggerCompletion(trigger, execute[3]);
        reserveMatcherPublication(pending, direct);
        if (realtimeCapture != null) pending.realtimeTakerOrder = triggerOrder;
        return direct;
    }

    private com.surprising.aeron.service.state.MatcherSettlementEvent prepareCancellation(CommandSlot pending) {
        com.surprising.aeron.service.state.MatcherSettlementEvent direct = runtimeState.prepareDirectCancellation(pending.sequence(),
                pending.command().header().userId(), pending.decodedCommand().cancelOrder().orderId(),
                pending.command().header().commandId(), matchingFlow.matcherShard(pending), identities,
                pending.commitFenceTimestamp(), pending.commitFenceClusterPosition(), pending.laneResultTarget());
        reserveMatcherPublication(pending, direct);
        return direct;
    }

    private void reserveMatcherPublication(CommandSlot pending, com.surprising.aeron.service.state.MatcherSettlementEvent direct) {
        pending.settlement(direct, direct.plan());
        direct.reserveMatcherPublication();
    }

    private long directInitialLaneMask(long userId) {
        if (userId <= 0) throw new IllegalArgumentException("direct settlement user is required");
        return runtimeState.topology().accountLaneMask(userId);
    }
}
