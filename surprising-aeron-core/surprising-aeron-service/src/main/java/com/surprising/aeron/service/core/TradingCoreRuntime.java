package com.surprising.aeron.service.core;

import com.surprising.aeron.config.CoreConfiguration;
import com.surprising.aeron.config.LaneTopology;
import com.surprising.aeron.config.TradingCpuAffinity;
import com.surprising.aeron.config.CoreRuntimeConfiguration;
import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.exception.FatalMatchingDivergenceException;
import com.surprising.aeron.model.CoreOrderState;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.WireMessageKind;
import com.surprising.aeron.service.core.command.DirectCommandDispatcher;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.core.index.ActiveOrderIndex;
import com.surprising.aeron.service.core.index.AdlPositionIndex;
import com.surprising.aeron.service.core.index.AlgoOrderIndex;
import com.surprising.aeron.service.core.index.LiquidationIndex;
import com.surprising.aeron.service.core.index.OpenInterestIndex;
import com.surprising.aeron.service.core.index.PositionUserIndex;
import com.surprising.aeron.service.core.index.TriggerOrderIndex;
import com.surprising.aeron.service.market.MarkPriceRuntime;
import com.surprising.aeron.service.matcher.DeterministicExchangeCoreAdapter;
import com.surprising.aeron.service.matcher.DeterministicExchangeCoreAdapter.CancellationOrder;
import com.surprising.aeron.service.matcher.MatcherSnapshot;
import com.surprising.aeron.service.matcher.pipeline.MatcherPipelineGroup;
import com.surprising.aeron.service.order.ClusterCommandWindow;
import com.surprising.aeron.service.order.CommandSlot;
import com.surprising.aeron.service.order.CoreMatchingFlow;
import com.surprising.aeron.service.order.DirectCommandSlot;
import com.surprising.aeron.service.order.MatchingCommandAdmission;
import com.surprising.aeron.service.order.MatchingPipelineProgress;
import com.surprising.aeron.service.order.OrderBatchExecutor;
import com.surprising.aeron.service.order.OrderBatchPending;
import com.surprising.aeron.service.order.PendingMatchingRing;
import com.surprising.aeron.service.order.admission.RuntimeOrderAdmission;
import com.surprising.aeron.service.order.admission.ResolvedPlaceOrder;
import com.surprising.aeron.service.order.command.DecodedMatchingCommand;
import com.surprising.aeron.service.order.command.ResolvedMatchingAdmission;
import com.surprising.aeron.service.order.commit.OrderedCommitCoordinator;
import com.surprising.aeron.service.order.state.OrderRuntime;
import com.surprising.aeron.service.query.CoreQueryService;
import com.surprising.aeron.service.result.CommandResultBuilder;
import com.surprising.aeron.service.result.CommandResultLedger;
import com.surprising.aeron.service.result.CommandResultLedger.StoredResult;
import com.surprising.aeron.service.result.ResponseArena;
import com.surprising.aeron.service.result.SourceSequenceIndex;
import com.surprising.aeron.service.retention.TerminalStateRetention;
import com.surprising.aeron.service.risk.LiquidationRuntime;
import com.surprising.aeron.service.risk.RiskScanRuntime;
import com.surprising.aeron.service.snapshot.CoreSnapshotImage;
import com.surprising.aeron.service.snapshot.CoreSnapshotLifecycle;
import com.surprising.aeron.service.snapshot.CoreSnapshotManifest;
import com.surprising.aeron.service.snapshot.SectionedCoreSnapshotCodec;
import com.surprising.aeron.service.snapshot.SectionedCoreSnapshotWriter;
import com.surprising.aeron.service.trigger.command.TriggerOrderCommands;
import com.surprising.aeron.protocol.CoreLaneMetricsView;
import com.surprising.product.api.ProductLine;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

/**
 * 交易步骤 1：Owner 上的业务执行入口。接收复制命令后，依次处理查询边界、幂等与来源检查、业务准入、撮合派发和有序完成。
 * 撮合准入交给 MatchingCommandAdmission，撮合推进交给 CoreMatchingFlow，
 * 结果完成交给 OrderedCommitCoordinator；其他命令交给对应业务 Commands。
 * <p>账户 Lane、Matcher 和账本各自拥有权威状态。本类保存当前命令的变化范围、路由索引、收据及恢复水位，
 * 借出引用不会自动授予跨线程账户读写权限。构造保持被动，activate 才建立活动执行边界。</p>
 * 日志： 运行时生命周期及故障诊断日志，不参与业务计算。
 */
@Slf4j
public final class TradingCoreRuntime implements AutoCloseable {

    /**  本 Owner 复用的命令指纹计算器，用于同一命令身份的内容一致性检查。 借出请求指纹计算器，供同一 Owner 的幂等准备使用。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    final CommandFingerprint.Calculator fingerprints = new CommandFingerprint.Calculator();

    /**  撮合命令准入入口，承担解码后的订单与账户准备。 借出撮合准入入口，使用当前 Owner 与命令上下文。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    final MatchingCommandAdmission admissions = new MatchingCommandAdmission(this);

    /** 撮合结果的有序提交入口，等待账户和账本完成后交出终态。 */
    public final OrderedCommitCoordinator commits = new OrderedCommitCoordinator(this);

    /** 触发单、算法单、触发扫描及衍生子单的业务入口。 */
    final TriggerOrderCommands triggers = new TriggerOrderCommands(this);

    /** 直接控制命令的统一业务派发入口。 */
    final DirectCommandDispatcher directCommands = new DirectCommandDispatcher(this, triggers);

    private java.util.function.Consumer<com.surprising.aeron.service.funding.RuntimePerpetualFundingProcessor.FundingResult> replayFundingObserver;

    public void observeReplayFundingPayments(java.util.function.Consumer<com.surprising.aeron.service.funding.RuntimePerpetualFundingProcessor.FundingResult> observer) {
        replayFundingObserver = java.util.Objects.requireNonNull(observer);
    }

    public void notifyReplayFundingObserver(com.surprising.aeron.service.funding.RuntimePerpetualFundingProcessor.FundingResult result) {
        if (replayFundingObserver != null) replayFundingObserver.accept(result);
    }
    /**  本次调用是否采用独立窗口准入，执行结束恢复外层值。 返回本次调用是否允许按独立撮合窗口接纳。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private boolean clusterPipelineAdmission;
    /** 本次借用的原输入对象，用对象身份约束解码与指纹缓存。 */
    private CoreMessage decodedIngressMessage;
    /** 本次借用的解码槽，可能尚待串行路径延迟解码。 */
    private DecodedMatchingCommand decodedIngressCommand;
    /** 当前输入已准备的可空指纹，只能与同一原输入配套复用。 */
    private CommandFingerprint preparedIngressFingerprint;
    /**  最近一次窗口预检查留下的用户 Lane 位，未适用时为零。 返回预检查记下的用户 Lane 位；它只用于窗口路由，不代表业务已经受理。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long preparedRouteUserLaneBit;
    /**  最近一次窗口预检查留下的 Matcher 分片，未适用时为负一。 返回预检查记下的 Matcher 分片；没有可用路由时为负一。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private int preparedRouteMatcherShard = -1;
    /** 普通撮合派发与完成推进的业务协作入口。 */
    public final CoreMatchingFlow matchingFlow = new CoreMatchingFlow(this);
    /** Owner 物化读取缓存；只在运行时修订、行情修订及发布序号均未变时复用，不替代账户与账本权威状态。 */
    private TradingCoreState cachedTradingState;
    /** 缓存视图对应的运行时修订，初始极小值迫使首次读取重建。 */
    private long cachedRevision = Long.MIN_VALUE;
    /** 缓存视图对应的行情修订，避免只看账户修订漏掉行情变化。 */
    private long cachedMarketRevision = Long.MIN_VALUE;
    /** 缓存视图对应的已发布投影序号。 */
    private long cachedSequence = Long.MIN_VALUE;

    /**  有界响应缓冲池，同时由近期收据和传输出口持有租约；收据退窗不一定立即可复用。 借出响应缓冲池，调用方仍须遵守收据与传输租约。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    final ResponseArena responseArena;

    /** 当前命令复用的结果构建器，响应最终交接前持有其缓冲租约。 */
    public final CommandResultBuilder resultBuilder = new CommandResultBuilder(this);

    /**  批量下单、撤单和改单业务处理对象。 借出批量订单业务入口，沿用当前命令与有序提交边界。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    final OrderBatchExecutor batches = new OrderBatchExecutor(this);
    /**  Owner 串行推进的跨分片撤单队列。 借出跨分片撤单推进对象，由 Owner 串行调用。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    final CrossShardCancellationCoordinator crossShardCancellations = new CrossShardCancellationCoordinator(this);

    /** 快照栅栏、捕获、编码和恢复所需的生命周期对象。 */
    public final CoreSnapshotLifecycle snapshots = new CoreSnapshotLifecycle(this);

    /** 盘口及相关异步查询的有界工作管理对象。 */
    public final CoreQueryService bookQueries = new CoreQueryService(this);
    /** 撮合完成与提交进度的观测和通知入口。 */
    public final MatchingPipelineProgress matchingProgress = new MatchingPipelineProgress(this);

    /**  可选实时变化捕获器，未接入实时输出时为空。 取得可空的实时变更捕获器。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    com.surprising.aeron.service.realtime.state.RealtimeStateCapture realtimeCapture;
    /**
     * 接入实时输出队列，创建本产品线的变更捕获器并交给运行时；这里只连接既有业务状态，不启动输出线程。
     * @param outbox 接入的实时输出队列，沿用其已有线程边界
     */
    public com.surprising.aeron.service.realtime.state.RealtimeStateCapture attachRealtime(
            com.surprising.aeron.client.RealtimeOutbox outbox) {
        realtimeCapture = new com.surprising.aeron.service.realtime.state.RealtimeStateCapture(
                outbox, productLine, identities);
        runtimeState.realtimeCapture(realtimeCapture);
        return realtimeCapture;
    }

    /** 尚待收集的账户快照未来结果，同一时间最多保存一个。 */
    private java.util.concurrent.CompletableFuture<com.surprising.aeron.service.realtime.state.RealtimeUserSnapshot> pendingRealtimeSnapshot;
    /**
     * realtimeSnapshotUser：本次实时快照请求的原用户身份。
     * realtimeSnapshotId：本次实时快照请求的身份。
     * realtimeSnapshotPosition：本次实时快照请求保存的原日志位置。
     * realtimeSnapshotTimestamp：本次实时快照请求保存的原业务时间。
     * realtimeSnapshotExportSequence：注册本次实时快照时固定的已发布投影序号。
     */
    private long realtimeSnapshotUser, realtimeSnapshotId, realtimeSnapshotPosition, realtimeSnapshotTimestamp, realtimeSnapshotExportSequence;
    /** 尚待收集的盘口未来结果，同一时间最多保存一个。 */
    private java.util.concurrent.CompletableFuture<java.util.List<com.surprising.aeron.protocol.CoreBookLevelView>> realtimeBook;
    /** 本次实时盘口请求的标的身份。 */
    private String realtimeBookSymbol;
    /**
     * realtimeBookPosition：本次实时盘口请求保存的原日志位置。
     * realtimeBookTimestamp：本次实时盘口请求保存的原业务时间。
     */
    private long realtimeBookPosition, realtimeBookTimestamp;

    /**
     * 返回已经发布的投影序号，用它标明实时快照可见的提交边界。
     */
    public long realtimeExportSequence() { return commits.publication.publishedSequence(); }
    /**
     * 判断是否还保存着一个待收集的实时账户快照请求。
     */
    public boolean realtimeSnapshotPending() { return pendingRealtimeSnapshot != null; }
    /**
     * 判断是否还保存着一个待收集的实时盘口请求。
     */
    public boolean realtimeBookPending() { return realtimeBook != null; }

    /**
     * 未来结果就绪后先取走等待槽，再按原请求位置、时间和导出序号发布账户快照；收集失败时记录捕获故障，并尝试发布快照不可用事件。
     */
    public int pollRealtimeSnapshot() {
        if (pendingRealtimeSnapshot == null || !pendingRealtimeSnapshot.isDone()) return 0;
        // 已经从等待槽取出的本次未来结果，后续失败也不会继续占用原等待槽
        var future = pendingRealtimeSnapshot;
        pendingRealtimeSnapshot = null;
        try {
            // 账户读取完成后得到的实时快照内容
            var snapshot = future.join();
            realtimeCapture.begin(realtimeSnapshotPosition, realtimeSnapshotTimestamp, realtimeSnapshotId);
            realtimeCapture.snapshot(realtimeSnapshotUser, snapshot, realtimeSnapshotExportSequence);
            realtimeCapture.commit();
        } catch (/* 本次实时快照收集或发布的运行时故障，随后尝试发出不可用事件 */ RuntimeException failure) {
            realtimeCapture.failed();
            realtimeCapture.begin(realtimeSnapshotPosition, realtimeSnapshotTimestamp, realtimeSnapshotId);
            realtimeCapture.emit(com.surprising.aeron.protocol.RealtimeFrame.Kind.SNAPSHOT_UNAVAILABLE,
                    realtimeSnapshotUser, "", "", new byte[0]);
            realtimeCapture.commit();
        }
        return 1;
    }

    /**
     * 收集已就绪的盘口读取并发布 BOOK 事件；先清等待槽，读取或编码失败只记录实时捕获故障。
     */
    public int pollRealtimeBook() {
        if (realtimeBook == null || !realtimeBook.isDone()) return 0;
        // 已经从等待槽取出的本次未来结果，后续失败也不会继续占用原等待槽
        var future = realtimeBook;
        realtimeBook = null;
        try {
            // 在 Matcher 提交栅栏后读到的盘口档位
            var levels = future.join();
            realtimeCapture.begin(realtimeBookPosition, realtimeBookTimestamp, 0);
            realtimeCapture.emit(com.surprising.aeron.protocol.RealtimeFrame.Kind.BOOK, 0,
                    realtimeBookSymbol, realtimeBookSymbol,
                    com.surprising.aeron.protocol.CoreStateQueryCodec.encodeOrderBookView(
                            new com.surprising.aeron.protocol.CoreOrderBookView(realtimeBookPosition, levels)));
            realtimeCapture.commit();
        } catch (/* 本次盘口读取或发布故障，只记实时捕获失败 */ RuntimeException failure) {
            realtimeCapture.failed();
        }
        return 1;
    }

    /**
     * 实时功能启用且没有同类请求时，确认业务回调已完成，保存原请求元数据并发起账户快照读取；注册失败只记捕获故障。
     * @param userId 本次业务所属用户身份
     * @param snapshotId 本次快照身份，交给既有捕获和编码流程
     * @param position 本次请求原复制日志位置
     * @param timestamp 本次请求原复制日志业务时间
     */
    public void captureRealtimeSnapshot(long userId, long snapshotId, long position, long timestamp) {
        if (realtimeCapture == null || pendingRealtimeSnapshot != null) return;
        assertClusterCallbackComplete();
        realtimeSnapshotExportSequence = realtimeExportSequence();
        realtimeSnapshotUser = userId;
        realtimeSnapshotId = snapshotId;
        realtimeSnapshotPosition = position;
        realtimeSnapshotTimestamp = timestamp;
        try { pendingRealtimeSnapshot = runtimeState.realtimeSnapshot(userId); }
        catch (/* 注册账户快照读取时的故障，只记捕获失败 */ RuntimeException failure) { realtimeCapture.failed(); }
    }

    /**
     * 实时功能启用且没有同类请求时，在对应 Matcher 的提交栅栏后读取最多 50 档盘口；保留原请求元数据，交由轮询收集结果。
     * @param instrumentId 对外标的 ID，按本产品线身份表解析
     * @param position 本次请求原复制日志位置
     * @param timestamp 本次请求原复制日志业务时间
     */
    public void captureRealtimeBook(String instrumentId, long position, long timestamp) {
        if (realtimeCapture == null || realtimeBook != null) return;
        assertClusterCallbackComplete();
        realtimeBookSymbol = instrumentId;
        realtimeBookPosition = position;
        realtimeBookTimestamp = timestamp;
        try {
            realtimeBook = matcherPipeline.readAtSubmissionFence(matchingAdapter.matcherShardId(instrumentId),
                    () -> matchingAdapter.orderBookLevels(instrumentId, 50));
        } catch (/* 注册盘口读取时的故障，只记捕获失败 */ RuntimeException failure) {
            realtimeCapture.failed();
        }
    }

    /**
     * 查询入口：按协议类型选择 Owner 元数据、账户 Lane、账本或盘口读取，映射各分支明确捕获的业务错误。
     * 大多数分支要求 QUERY 头；标的维护分支这里只检查消息类型，沿用外层协议约束。
     * 不识别的请求返回空，由后续入口继续判断。
     * @param message 本次借用的原协议输入，调用期间保持其缓冲租约有效
     * @param clusterTimestamp 本命令原复制日志业务时间
     */
    CoreResponse routeQuery(CoreMessage message, long clusterTimestamp) {
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && CoreQueryService.accountQuery(message.header().messageType())) {
            try { return bookQueries.beginAccountQuery(message); }
            catch (/* 账户查询响应超过既有容量边界的业务异常，映射为响应过大 */ com.surprising.aeron.service.query.state.RuntimeOperationalQueryService.QueryTooLargeException tooLarge) {
                return rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
            } catch (/* 账户查询参数不合法的异常，映射为无效命令 */ IllegalArgumentException invalid) { return rejected(CoreResultCode.INVALID_COMMAND); }
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && (message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.STATE_HASH_QUERY
                || message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.BUSINESS_STATE_HASH_QUERY)) {
            return new CoreResponse(com.surprising.aeron.protocol.ResponseStatus.OK, appliedCommandCount,
                    com.surprising.aeron.protocol.CoreStateQueryCodec.encodeStateHash(stateHash()));
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.LANE_METRICS_QUERY) {
            if (message.payloadLength() != 0) return rejected(CoreResultCode.INVALID_COMMAND);
            return new CoreResponse(com.surprising.aeron.protocol.ResponseStatus.OK, appliedCommandCount, encodeLaneMetrics());
        }
        if (message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.INSTRUMENT_MAINTENANCE_QUERY) {
            try {
                // 本查询分支从协议载荷解出的筛选条件
                var query = com.surprising.aeron.protocol.CoreMaintenanceCodec.decodeQuery(message.payloadUnsafe());
                // 当前业务查到的标的配置，用于身份、结算规则或路由判断
                var instrument = runtimeState.instrument(query.instrumentId());
                if (instrument == null) return rejected(CoreResultCode.ENTITY_NOT_FOUND);
                return bookQueries.beginPositionIndexQuery(message, query, instrument.maintenance());
            } catch (/* 本分支明确捕获的业务或输入异常，按对应协议码处理 */ IllegalArgumentException | java.nio.BufferUnderflowException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.COMMAND_RESULT_QUERY) {
            try {
                // 命令结果查询请求的原命令 UUID
                java.util.UUID commandId = com.surprising.aeron.protocol.CoreStateQueryCodec.decodeCommandResultQuery(message.payloadUnsafe());
                // 近期收据表查出的原终态，响应正文仍由缓冲池持有
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
            } catch (/* 本分支明确捕获的业务或输入异常，按对应协议码处理 */ IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && (message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.TRIGGER_ORDER_QUERY
                || message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.USER_OPEN_TRIGGER_ORDERS_QUERY)) {
            try { return bookQueries.beginTriggerQuery(message);
            } catch (/* 本分支明确捕获的业务或输入异常，按对应协议码处理 */ com.surprising.aeron.service.query.state.RuntimeOperationalQueryService.QueryTooLargeException exception) {
                return rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
            } catch (/* 本分支明确捕获的业务或输入异常，按对应协议码处理 */ IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.BOOK_STATE_QUERY) {
            try {
                return bookQueries.beginBookQuery(message);
            } catch (/* 本分支明确捕获的业务或输入异常，按对应协议码处理 */ IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.ORDER_BOOK_BOOTSTRAP_QUERY) {
            try {
                return bookQueries.beginBookBootstrapQuery(message);
            } catch (/* 本分支明确捕获的业务或输入异常，按对应协议码处理 */ IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.TREASURY_STATE_QUERY) {
            try {
                return bookQueries.beginTreasuryQuery(message);
            } catch (/* 本分支明确捕获的业务或输入异常，按对应协议码处理 */ com.surprising.aeron.service.query.state.RuntimeOperationalQueryService.QueryTooLargeException exception) {
                return rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
            }
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.FUNDING_PROGRESS_QUERY) {
            try {
                // 当前进度查询解出的外部标的 ID
                String instrumentId = com.surprising.aeron.protocol.CoreStateQueryCodec.decodeFundingProgressQuery(message.payloadUnsafe());
                // 按标的构造的资金费或生命周期进度只读视图
                var view = com.surprising.aeron.service.query.state.RuntimeOperationalQueryService
                        .fundingProgress(runtimeState, identities, instrumentId);
                return new CoreResponse(com.surprising.aeron.protocol.ResponseStatus.OK, appliedCommandCount,
                        com.surprising.aeron.protocol.CoreFundingProgressCodec.encode(view));
            } catch (/* 本分支明确捕获的业务或输入异常，按对应协议码处理 */ IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.SETTLEMENT_PROGRESS_QUERY) {
            try {
                // 当前进度查询解出的外部标的 ID
                String instrumentId = com.surprising.aeron.protocol.CoreStateQueryCodec.decodeSettlementProgressQuery(message.payloadUnsafe());
                // 按标的构造的资金费或生命周期进度只读视图
                var view = com.surprising.aeron.service.query.state.RuntimeOperationalQueryService
                        .settlementProgress(runtimeState, identities, instrumentId);
                return new CoreResponse(com.surprising.aeron.protocol.ResponseStatus.OK, appliedCommandCount,
                        com.surprising.aeron.protocol.CoreSettlementProgressCodec.encode(view));
            } catch (/* 本分支明确捕获的业务或输入异常，按对应协议码处理 */ IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && CoreQueryService.riskAccountQuery(message.header().messageType())) {
            try { return bookQueries.beginRiskAccountQuery(message); }
            catch (/* 本分支捕获的失败，按当前边界记录、延后拒绝或继续传播 */ com.surprising.aeron.service.query.state.RuntimeOperationalQueryService.QueryTooLargeException failure) {
                return rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
            } catch (/* 本分支捕获的失败，按当前边界记录、延后拒绝或继续传播 */ IllegalArgumentException failure) { return rejected(CoreResultCode.INVALID_COMMAND); }
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.RISK_SCAN_CONTROL_QUERY) {
            if (message.payloadLength() != 0) return rejected(CoreResultCode.INVALID_COMMAND);
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
                // 本查询分支从协议载荷解出的筛选条件
                var query = com.surprising.aeron.protocol.CoreCancelAllAfterCodec.decodeQuery(message.payloadUnsafe());
                // 定时全撤索引按筛选条件返回的候选键
                var keys = factIndexes.timers().query(query.userId(), query.symbolScope(), query.dueAtEpochMillis(),
                        query.limit(), runtimeState::cancelAllAfterTimer);
                // 根据定时全撤候选键构造的查询结果列表
                var values = com.surprising.aeron.service.query.state.RuntimeOperationalQueryService.cancelAllAfter(
                        runtimeState, keys);
                return new CoreResponse(com.surprising.aeron.protocol.ResponseStatus.OK, appliedCommandCount,
                        com.surprising.aeron.protocol.CoreCancelAllAfterCodec.encodeList(values));
            } catch (/* 本分支明确捕获的业务或输入异常，按对应协议码处理 */ com.surprising.aeron.service.query.state.RuntimeOperationalQueryService.QueryTooLargeException exception) {
                return rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
            } catch (/* 本分支明确捕获的业务或输入异常，按对应协议码处理 */ IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.LIQUIDATION_WORK_QUERY) {
            try {
                // 本查询分支从协议载荷解出的筛选条件
                var query = com.surprising.aeron.protocol.CoreLiquidationWorkCodec.decodeQuery(message.payloadUnsafe());
                if (query.productLine() != productLine) {
                    return rejected(CoreResultCode.PRODUCT_LINE_MISMATCH);
                }
                return bookQueries.beginLiquidationWorkQuery(message, query);
            } catch (/* 本分支明确捕获的业务或输入异常，按对应协议码处理 */ com.surprising.aeron.service.query.state.RuntimeOperationalQueryService.QueryTooLargeException exception) {
                return rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
            } catch (/* 本分支明确捕获的业务或输入异常，按对应协议码处理 */ IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.ORDER_PREFLIGHT_QUERY) {
            try {
                return bookQueries.beginPreflightQuery(message, clusterTimestamp);
            } catch (/* 预检业务拒绝，保留它携带的具体结果码 */ com.surprising.aeron.exception.CoreStateRejectedException exception) {
                return rejected(CoreResultCode.fromRejectionCode(exception.code()));
            } catch (/* 预检整数计算溢出，映射为 OVERFLOW */ ArithmeticException exception) {
                return rejected(CoreResultCode.ARITHMETIC_OVERFLOW);
            } catch (/* 预检输入不合法，映射为无效命令 */ IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.PENDING_TRANSFER_QUERY) {
            try {
                // 待完成划转查询允许返回的最大条数
                int limit = com.surprising.aeron.protocol.CorePendingTransferCodec.decodeQuery(
                        message.payloadUnsafe());
                return bookQueries.beginPendingTransferQuery(message, limit);
            } catch (/* 本分支明确捕获的业务或输入异常，按对应协议码处理 */ IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        return null;
    }

    /** 近期完整命令收据最多保留 128 条；长期资金身份由另一索引保存。 */
    public static final int MAX_IDEMPOTENCY_RESULTS = 128;
    /** 近期收据账本的响应字节预算，固定为 32 MiB。 */
    public static final long MAX_RESULT_LEDGER_BYTES = 32L * 1024 * 1024;
    /** 来源序号索引最多跟踪 65536 个来源键。 */
    public static final int MAX_SOURCE_SEQUENCES = 65_536;
    /** 盘口响应允许的最大档位数量。 */
    public static final int MAX_BOOK_RESPONSE_LEVELS = 10_000;
    /** 盘口响应允许的最大编码字节数，固定为 1 MiB。 */
    public static final int MAX_BOOK_RESPONSE_BYTES = 1024 * 1024;
    /** 撮合显式等待路径使用的 30 秒纳秒预算。 */
    public static final long MATCHING_AWAIT_TIMEOUT_NANOS =
            java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
    /** 盘口引导快照同时保留的数量上限。 */
    public static final int MAX_BOOK_BOOTSTRAP_SNAPSHOTS = 4;
    /** 每轮触发扫描的默认工作预算，固定为两项。 */
    private static final int DEFAULT_TRIGGER_SCAN_BATCH_SIZE = 2;
    /** FNV 摘要的初始值，不是业务序号。 */
    private static final long HASH_OFFSET_BASIS = 0xcbf29ce484222325L;
    /** FNV 字节混合所用乘数，溢出遵循长整数哈希规则。 */
    private static final long HASH_PRIME = 0x100000001b3L;
    /** 没有响应正文时共享的空字节数组。 */
    public static final byte[] EMPTY_RESPONSE_DATA = new byte[0];
    private final CoreRuntimeConfiguration configuration;
    public CoreRuntimeConfiguration configuration() { return configuration; }
    /**  本运行时唯一产品线，隔离协议、标的、账户和恢复状态。 返回该运行时唯一所属的产品线。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    final ProductLine productLine;
    /** 当前绑定的 Owner 线程，被动构造后可交接，活动业务要求线程一致。 */
    Thread owner;
    /**  基于运行时事实建立的索引集合，恢复时重新绑定并重建。 借出运行时事实索引集合，沿用各索引已有所有权。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    final com.surprising.aeron.service.core.index.RuntimeFactIndexes factIndexes;
    /**  近期完整命令收据及响应租约的有界保存表。 借出近期命令收据表，不延长其中响应缓冲的持有期限。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    final CommandResultLedger resultLedger;

    /** Owner 按来源类型和来源身份记录的最新已登记序号。 */
    final SourceSequenceIndex lastSourceSequences;
    /** 按 Core 序号保存的有界在途撮合队列，用队首约束有序提交。 */
    private final PendingMatchingRing pendingMatching;
    /** Matcher 分片的执行、控制任务、完成通知及路由边界。 */
    private final MatcherPipelineGroup matcherPipeline;
    /** Owner 内部协作的受控借用；不把任意跨线程调用当成合法读写。 */
    public PendingMatchingRing pendingMatching() { assertOwner(); return pendingMatching; }
    public MatcherPipelineGroup matcherPipeline() { assertOwner(); return matcherPipeline; }

    /**  本产品线的确定性撮合适配器，构造时先保持被动状态。 借出 Matcher 适配器，仍遵守其分片线程和控制协议。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    final DeterministicExchangeCoreAdapter matchingAdapter;

    /**  持仓用户索引；活动阶段由账本维护，Owner 使用路由及查询描述。 借出持仓用户索引引用；活动阶段的可变内容仍由账本拥有。 */
    public PositionUserIndex positionUserIndex() { return factIndexes.positionUsers(); }
    /**  持仓量索引；活动阶段的可变权威状态归账本。 借出持仓量索引引用，活动阶段由账本维护其权威变化。 */
    public OpenInterestIndex openInterestIndex() { return factIndexes.openInterest(); }
    /**  Owner 维护的活动触发单及扫描索引。 借出 Owner 维护的触发单索引，供触发扫描及命令编排使用。 */
    public TriggerOrderIndex triggerOrderIndex() { return factIndexes.triggers(); }
    /**  Owner 维护的算法单索引。 借出 Owner 算法单索引。 */
    public AlgoOrderIndex algoOrderIndex() { return factIndexes.algos(); }
    /**  Owner 维护的强平身份及工作索引。 借出 Owner 强平索引，供既有业务编排使用。 */
    public LiquidationIndex liquidationIndex() { return factIndexes.liquidations(); }
    /**  Owner 维护的活动订单路由索引，避免路由判断扫描账户。 借出 Owner 的活动订单路由索引，查询路由不等于读取账户权威状态。 */
    public ActiveOrderIndex activeOrderIndex() { return factIndexes.activeOrders(); }
    /**  ADL 持仓索引；活动阶段按账本所有权读写。 借出 ADL 持仓索引引用，遵守账本的状态所有权。 */
    public AdlPositionIndex adlPositionIndex() { return factIndexes.adlPositions(); }

    /**
     *  已经累计到终态统计中的 TRADE 事件条数。
     * 保存调用方给出的终态成交事件计数，不在这里增加或重新统计。
     * @param terminalTradeCount 需要保存的终态 TRADE 事件总数
     */
    @Accessors(fluent = true, chain = false)
    @Setter
    long terminalTradeCount;
    /**  近期终态候选和墓碑，以及长期资金身份的保留入口。 借出终态保留对象，沿用 Owner、恢复和账本边界。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    final TerminalStateRetention terminalRetention;

    /**  当前是否有一条命令占用事实变化作用域。 返回当前是否处于已激活的命令事实作用域。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    boolean factContextActive;
    /**  已登记受理的 Core 序号上界，可能包含尚未完成的撮合命令。 返回已经登记受理的 Core 命令计数；在途撮合可能尚未推进到已提交水位。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    long appliedCommandCount;
    /**  已经完成的有序 Core 前缀，不能跨越在途撮合队首。 返回已经完成有序前缀的 Core 序号。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    long committedCoreSequence;
    /**
     *  已经登记的提交发布故障，后续健康检查立即传播。
     * 登记调用方给出的提交发布故障，后续健康检查传播该值。
     * @param commitPublicationFailure 需要登记的可空提交发布异常
     */
    @Accessors(fluent = true, chain = false)
    @Setter
    RuntimeException commitPublicationFailure;
    /**  标的、资产和持仓等内部身份注册表，沿用 Owner 与恢复边界。 借出本产品线的身份注册表，沿用其线程与恢复边界。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    final RuntimeIdentityRegistry identities;
    /**  账户、持仓、账本及直接命令的实际运行时；Owner 不取得各 Lane 的任意写权限。 借出实际运行时引用，供既有编排访问；引用本身不授予跨线程账户读写权限。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    final TradingAccountRuntime runtimeState;

    /**  已经登记的致命撮合分歧，非空时阻止后续健康检查通过。 取得已经登记的撮合致命分歧，未登记时返回空。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    FatalMatchingDivergenceException fatalFailure;

    /**
     *  当前命令保存的原复制日志位置。
     * 返回当前保存的复制日志位置。
     * 保存调用方提供的当前复制日志位置；本方法只赋值，不自行推进业务或检查线程。
     * @param currentClusterPosition 需要保存的当前复制日志位置
     */
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    long currentClusterPosition;
    /**
     *  当前命令保存的原复制日志业务时间。
     * 返回当前命令保存的复制日志业务时间。
     * 保存调用方提供的复制日志业务时间，不读取系统时间或自行执行业务。
     * @param currentClusterTimestamp 需要保存的当前复制日志业务时间
     */
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    long currentClusterTimestamp;
    /** 准入前的日志位置，准入拒绝时恢复。 */
    long admissionPreviousClusterPosition;
    /** 准入前的业务时间，准入拒绝时恢复。 */
    long admissionPreviousClusterTimestamp;
    /**  是否已经完整执行过激活流程，关闭时不会自动改回假。 返回是否已经完成激活；不把该值当作资源仍未关闭的独立证明。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    boolean activated;
    /** 是否已经进入关闭流程，重复关闭直接返回。 */
    boolean closed;

    /**
     * 要求当前提交发布器记录变化；实际何时发布仍由命令完成边界决定。
     */
    public void requestCommitPublication() { commits.publication.request(); }
    /**
     * 取得强平索引中的活动强平身份集合，供既有强平编排使用。
     */
    public Iterable<Long> activeLiquidationIds() { return factIndexes.liquidations().activeIds(); }
    /**
     * 把本命令涉及的触发扫描初始化交给触发单业务处理。
     * @param command 本次业务使用的已解码命令或命令槽，沿用当前命令生命周期
     */
    public void initializeTriggerScan(com.surprising.aeron.protocol.ApplyMarkPriceCommand command) {
        triggers.initializeTriggerScan(command);
    }
    /**
     * 返回探测指定标的是否仍有触发扫描工作的函数；真正探测时沿用给定的单轮预算。
     * @param instrumentId 对外标的 ID，按本产品线身份表解析
     * @param maxWork 本轮最多处理的工作数量
     */
    public java.util.function.BooleanSupplier pendingTriggerScan(String instrumentId, int maxWork) {
        return triggers.pendingTriggerScan(instrumentId, maxWork);
    }
    /**
     * 按本轮预算推进指定标的的触发扫描，由既有触发单逻辑裁决和生成后续工作。
     * @param instrumentId 对外标的 ID，按本产品线身份表解析
     * @param maxWork 本轮最多处理的工作数量
     */
    public void evaluatePendingTriggerScan(String instrumentId, int maxWork) {
        triggers.evaluatePendingTriggerScan(instrumentId, maxWork);
    }
    /**
     * 返回已经排队、尚待追加派发的衍生撮合命令数量。
     */
    public int queuedMatchingCount() { return admissions.queuedMatchingCount(); }
    /**
     * 返回触发扫描每轮的默认工作预算。
     */
    public int defaultTriggerScanBatchSize() { return DEFAULT_TRIGGER_SCAN_BATCH_SIZE; }

    /**
     * 为指定产品线建立空的被动运行时；线程和活动资源由后续 activate 接入。
     * @param productLine 必须明确隔离的所属产品线
     */
    public TradingCoreRuntime(ProductLine productLine) {
        this(productLine, CoreConfiguration.standaloneLaneTopology());
    }

    /**
     * 按启动边界传入的拓扑创建空白运行时，账户和 Matcher 使用同一个值。
     * @param productLine 本运行时唯一处理的产品线
     * @param startupTopology 首次启动使用的不可变交易拓扑
     */
    public TradingCoreRuntime(ProductLine productLine,
            LaneTopology startupTopology) {
        this(productLine, startupTopology, CoreRuntimeConfiguration.standalone());
    }

    public TradingCoreRuntime(ProductLine productLine, LaneTopology startupTopology,
            CoreRuntimeConfiguration configuration) {
        this(productLine, 0, new LinkedHashMap<>(), new LinkedHashMap<>(),
                TradingCoreState.empty(productLine), new TerminalStateRetention(configuration.fundsIndexDirectory()), null,
                0, Map.of(), Map.of(), null, null,
                java.util.Objects.requireNonNull(startupTopology, "startupTopology"), configuration);
    }

    /**
     * 建立空的被动运行时，并允许注入 Matcher 快照捕获方式。
     * @param productLine 必须明确隔离的所属产品线
     * @param matcherSnapshotCapture 可空的 Matcher 捕获实现，空时采用默认快照捕获
     */
    public TradingCoreRuntime(ProductLine productLine, MatcherSnapshotCapture matcherSnapshotCapture) {
        this(productLine, 0, new LinkedHashMap<>(), new LinkedHashMap<>(),
                TradingCoreState.empty(productLine), new TerminalStateRetention(), null,
                0, Map.of(), Map.of(), matcherSnapshotCapture, null);
    }

    /**
     * 建立空的被动运行时，并允许分别注入 Matcher 捕获和分块快照编码方式。
     * @param productLine 必须明确隔离的所属产品线
     * @param matcherSnapshotCapture 可空的 Matcher 捕获实现，空时采用默认快照捕获
     * @param snapshotEncoder 可空的分块编码实现，空时在调用线程编码后返回已完成结果
     */
    public TradingCoreRuntime(ProductLine productLine, MatcherSnapshotCapture matcherSnapshotCapture,
                   SnapshotEncoder snapshotEncoder) {
        this(productLine, 0, new LinkedHashMap<>(), new LinkedHashMap<>(),
                TradingCoreState.empty(productLine), new TerminalStateRetention(), null,
                0, Map.of(), Map.of(), matcherSnapshotCapture, snapshotEncoder);
    }

    /**
     * 根据初始或恢复状态装配账户、Matcher、索引、幂等表及快照协作对象，初始化水位后释放 Owner 绑定以便交接；此处不激活工作线程。默认快照编码在调用线程执行，再返回已完成的未来结果。
     * @param productLine 必须明确隔离的所属产品线
     * @param appliedCommandCount 初始或恢复的已受理 Core 序号
     * @param commandResults 按既有顺序恢复的近期命令收据
     * @param lastSourceSequences 恢复的来源键与最新序号映射
     * @param snapshotState 初始或恢复的业务状态镜像
     * @param terminalRetention 初始或恢复的终态保留内容
     * @param matcherSnapshot 恢复使用的 Matcher 状态，普通构造可由默认路径准备
     * @param projectionSequence 初始或恢复的已发布投影序号
     * @param restoredFeePolicies 恢复的费用政策映射，遍历顺序沿用输入
     * @param restoredPendingTransfers 恢复的待完成划转映射，遍历顺序沿用输入
     * @param matcherSnapshotCapture 可空的 Matcher 捕获实现，空时采用默认快照捕获
     * @param snapshotEncoder 可空的分块编码实现，空时在调用线程编码后返回已完成结果
     */
    public TradingCoreRuntime(
            ProductLine productLine,
            long appliedCommandCount,
            LinkedHashMap<UUID, StoredResult> commandResults,
            LinkedHashMap<SourceKey, Long> lastSourceSequences,
            TradingCoreState snapshotState,
            TerminalStateRetention terminalRetention,
            MatcherSnapshot matcherSnapshot,
            long projectionSequence,
            Map<Long, com.surprising.aeron.model.CoreFeePolicyState> restoredFeePolicies,
            Map<Long, com.surprising.aeron.protocol.TransferFundsCommand> restoredPendingTransfers,
            MatcherSnapshotCapture matcherSnapshotCapture,
            SnapshotEncoder snapshotEncoder) {
        this(productLine, appliedCommandCount, commandResults, lastSourceSequences, snapshotState,
                terminalRetention, matcherSnapshot, projectionSequence, restoredFeePolicies,
                restoredPendingTransfers, matcherSnapshotCapture, snapshotEncoder,
                matcherSnapshot == null
                        ? CoreConfiguration.standaloneLaneTopology()
                        : matcherSnapshot.topology(), CoreRuntimeConfiguration.standalone());
    }

    /**
     * 组装运行时；首次启动使用传入拓扑；恢复边界将快照固定路由与当前部署容量组合后交入 Matcher 快照对象。
     * @param productLine 本运行时唯一处理的产品线
     * @param appliedCommandCount 初始或恢复的 Core 序号
     * @param commandResults 已恢复的近期命令结果
     * @param lastSourceSequences 已恢复的来源消费水位
     * @param snapshotState 同一冻结点的业务状态
     * @param terminalRetention 同一冻结点的终态身份和资金指纹
     * @param matcherSnapshot 恢复的 Matcher 快照，首次启动时为空
     * @param projectionSequence 已恢复的投影水位
     * @param restoredFeePolicies 已恢复的费率配置
     * @param restoredPendingTransfers 已恢复的待完成划转
     * @param matcherSnapshotCapture 可空的 Matcher 快照捕获函数
     * @param snapshotEncoder 可空的分块编码函数
     * @param startupTopology 启动边界传入的拓扑，恢复时由快照中的拓扑决定
     */
    private TradingCoreRuntime(
            ProductLine productLine,
            long appliedCommandCount,
            LinkedHashMap<UUID, StoredResult> commandResults,
            LinkedHashMap<SourceKey, Long> lastSourceSequences,
            TradingCoreState snapshotState,
            TerminalStateRetention terminalRetention,
            MatcherSnapshot matcherSnapshot,
            long projectionSequence,
            Map<Long, com.surprising.aeron.model.CoreFeePolicyState> restoredFeePolicies,
            Map<Long, com.surprising.aeron.protocol.TransferFundsCommand> restoredPendingTransfers,
            MatcherSnapshotCapture matcherSnapshotCapture,
            SnapshotEncoder snapshotEncoder,
            LaneTopology startupTopology, CoreRuntimeConfiguration configuration) {
        this.configuration = java.util.Objects.requireNonNull(configuration);
        this.responseArena = new ResponseArena(configuration);
        this.productLine = productLine;
        this.appliedCommandCount = appliedCommandCount;
        this.committedCoreSequence = appliedCommandCount;
        this.resultLedger = new CommandResultLedger(commandResults, responseArena);
        this.lastSourceSequences = new SourceSequenceIndex(lastSourceSequences);
        admissions.initializeLifecycleScopes();
        snapshots.lastSnapshotId(matcherSnapshot == null ? 0 : matcherSnapshot.snapshotId());
        this.terminalRetention = terminalRetention;
        // 初始状态及恢复费用、划转组合得到的业务哈希
        long restoredBusinessStateHash = canonicalBusinessStateHash(
                snapshotState.businessStateHash(), restoredFeePolicies, restoredPendingTransfers);
        // 首次启动共用注入拓扑；恢复时不按节点当前配置重新分配账户。
        var topology = matcherSnapshot == null
                ? startupTopology
                : matcherSnapshot.topology();
        this.identities = new RuntimeIdentityRegistry(topology);
        this.runtimeState = com.surprising.aeron.service.snapshot.state.RuntimeStateProjector.project(
                snapshotState, identities, topology, configuration);
        this.factIndexes = runtimeState.factIndexes();
        this.matchingAdapter = matcherSnapshot == null
                ? new DeterministicExchangeCoreAdapter(topology, false, configuration)
                : new DeterministicExchangeCoreAdapter(snapshotState, snapshotState.orders().values().stream().filter(/* 从恢复状态筛出的订单，仅 OPEN 订单交给 Matcher 重建 */ order -> order.status() == com.surprising.aeron.model.CoreOrderStatus.OPEN).toList(),
                        appliedCommandCount, matcherSnapshot, false, restoredBusinessStateHash, configuration);
        this.pendingMatching = new PendingMatchingRing(
                Math.min(configuration.maxPendingMatching(), matchingAdapter.topology().matcherWindowSize()),
                matchingAdapter.topology().matchingEngineCount());
        commits.initializeMatcherProgress(matchingAdapter.topology().matchingEngineCount() + 1);
        commits.initializeMatcherProgress(matcherSnapshot);
        this.matcherPipeline = new MatcherPipelineGroup(
                matchingAdapter.topology().matchingEngineCount(),
                Math.min(matchingAdapter.topology().matcherWindowSize(),
                        matchingAdapter.topology().matchingCompletionCapacity()), false, pendingMatching.contexts());
        snapshots.matcherSnapshotCapture(matcherSnapshotCapture == null
                ? snapshots::captureMatcherSnapshot : matcherSnapshotCapture);
        if (snapshotEncoder == null) {
            snapshots.snapshotEncoder(/* 默认编码器收到的快照镜像，在本调用中同步编码 */ image -> CompletableFuture.completedFuture(
                    SectionedCoreSnapshotWriter.encode(image, configuration.snapshot().maxBytes())));
        } else {
            snapshots.snapshotEncoder(snapshotEncoder);
        }
        this.runtimeState.restoreFeePolicies(restoredFeePolicies);
        this.runtimeState.restorePendingTransfers(restoredPendingTransfers);
        commits.publication.initialize(snapshotState.revision(), projectionSequence);
        runtimeState.releaseOwnerForHandoff();
        identities.releaseOwnerForHandoff();
    }

    /**
     * 检查恢复序号、容量、产品线及必需快照部件，复制收据与来源表，建立尚未激活的候选运行时；账户 Lane 快照仍须在激活前恢复。
     * @param productLine 必须明确隔离的所属产品线
     * @param appliedCommandCount 初始或恢复的已受理 Core 序号
     * @param commandResults 按既有顺序恢复的近期命令收据
     * @param lastSourceSequences 恢复的来源键与最新序号映射
     * @param snapshotState 初始或恢复的业务状态镜像
     * @param terminalRetention 初始或恢复的终态保留内容
     * @param matcherSnapshot 恢复使用的 Matcher 状态，普通构造可由默认路径准备
     * @param projectionSequence 初始或恢复的已发布投影序号
     * @param feePolicies 待恢复的费用政策映射
     * @param pendingTransfers 待恢复的未完成划转映射
     */
    public static TradingCoreRuntime prepareRestore(
            ProductLine productLine,
            long appliedCommandCount,
            Map<UUID, StoredResult> commandResults,
            Map<SourceKey, Long> lastSourceSequences,
            TradingCoreState snapshotState,
            TerminalStateRetention terminalRetention,
            MatcherSnapshot matcherSnapshot,
            long projectionSequence,
            Map<Long, com.surprising.aeron.model.CoreFeePolicyState> feePolicies,
            Map<Long, com.surprising.aeron.protocol.TransferFundsCommand> pendingTransfers) {
        return prepareRestore(productLine, appliedCommandCount, commandResults, lastSourceSequences,
                snapshotState, terminalRetention, matcherSnapshot, projectionSequence, feePolicies,
                pendingTransfers, CoreRuntimeConfiguration.standalone());
    }

    public static TradingCoreRuntime prepareRestore(ProductLine productLine, long appliedCommandCount,
            Map<UUID, StoredResult> commandResults, Map<SourceKey, Long> lastSourceSequences,
            TradingCoreState snapshotState, TerminalStateRetention terminalRetention, MatcherSnapshot matcherSnapshot,
            long projectionSequence, Map<Long, com.surprising.aeron.model.CoreFeePolicyState> feePolicies,
            Map<Long, com.surprising.aeron.protocol.TransferFundsCommand> pendingTransfers,
            CoreRuntimeConfiguration configuration) {
        if (projectionSequence < 0 || appliedCommandCount < 0 || commandResults == null
                || commandResults.size() > MAX_IDEMPOTENCY_RESULTS || lastSourceSequences == null
                || lastSourceSequences.size() > MAX_SOURCE_SEQUENCES
                || matcherSnapshot == null || snapshotState == null
                || snapshotState.productLine() != productLine
                || terminalRetention == null || feePolicies == null || pendingTransfers == null) {
            throw new IllegalArgumentException("invalid passive restored trading state");
        }
        CommandResultLedger.validateResultLedger(commandResults);
        return new TradingCoreRuntime(productLine, appliedCommandCount,
                new LinkedHashMap<>(commandResults), new LinkedHashMap<>(lastSourceSequences),
                snapshotState, terminalRetention, matcherSnapshot, projectionSequence,
                feePolicies, pendingTransfers, null, null, matcherSnapshot.topology(), configuration);
    }

    /**
     * 独立调用的便捷入口：用请求提交时间作为业务时间，并计算下一个计数作为位置；真实 Cluster 调用应传入原日志时间和位置。
     * @param message 本次借用的原协议输入，调用期间保持其缓冲租约有效
     */
    public CoreResponse apply(CoreMessage message) {
        return apply(message, message.header().submittedAtEpochMillis(), Math.addExact(appliedCommandCount, 1));
    }

    /**
     * 交易入口：激活并绑定 Owner，检查健康、控制命令和快照边界，登记原日志元数据，按查询需要取得读栅栏；先尝试查询，再把合法命令交给幂等和业务准入入口。
     * @param message 本次借用的原协议输入，调用期间保持其缓冲租约有效
     * @param clusterTimestamp 本命令原复制日志业务时间
     * @param clusterPosition 本命令原复制日志位置
     */
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
        if (snapshots.inFlightMatcherSnapshot().get() != null
                || snapshots.snapshotFence() != null && snapshots.snapshotFence().encodedSnapshot() == null) {
            throw new IllegalStateException("snapshot fence is active");
        }
        if (!pendingMatching.isEmpty()
                && !isCommitCursorSafeWhileMatching(message)) {
            // 本请求头中的用户身份，用于判断能否只等待单用户 Lane
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
        // 查询入口已经给出的响应，空表示还需判断命令路径
        CoreResponse queryResponse = routeQuery(message, clusterTimestamp);
        if (queryResponse != null) return queryResponse;
        if (message.header().kind() != WireMessageKind.COMMAND) {
            return rejected(CoreResultCode.INVALID_MESSAGE);
        }
        return applyCommandIngress(message, clusterTimestamp, clusterPosition);
    }

    /**
     * 判断准备此请求是否需要 Owner 账户访问边界；异步账户、账本查询走各自派发路径，普通命令也不在此统一取得账户访问权。
     * @param message 本次借用的原协议输入，调用期间保持其缓冲租约有效
     */
    public boolean requiresOwnerLaneAccessForPreparation(CoreMessage message) {
        if (message.header().kind() != WireMessageKind.QUERY) return false;
        // 请求头中的消息类型，用于区分准备阶段的访问边界
        var type = message.header().messageType();
        if (CoreQueryService.accountQuery(type) || CoreQueryService.riskAccountQuery(type)) return false;
        if (type == CoreMessageType.ORDER_PREFLIGHT_QUERY || type == CoreMessageType.LANE_METRICS_QUERY) return false;
        if (type == CoreMessageType.TRIGGER_ORDER_QUERY || type == CoreMessageType.USER_OPEN_TRIGGER_ORDERS_QUERY) return false;
        if (type == CoreMessageType.TREASURY_STATE_QUERY || type == CoreMessageType.FUNDING_PROGRESS_QUERY
                || type == CoreMessageType.SETTLEMENT_PROGRESS_QUERY) return false;
        if (type == CoreMessageType.LIQUIDATION_WORK_QUERY || type == CoreMessageType.PENDING_TRANSFER_QUERY) return false;
        if (type == CoreMessageType.OPEN_INTEREST_QUERY || type == CoreMessageType.INSTRUMENT_MAINTENANCE_QUERY) return false;
        return true;
    }

    /**
     * 将 Cluster 命令按未提供预解码对象的形式交给统一异步入口。
     * @param message 本次借用的原协议输入，调用期间保持其缓冲租约有效
     * @param timestamp 本次请求原复制日志业务时间
     * @param position 本次请求原复制日志位置
     */
    public CoreResponse applyClusterCommand(CoreMessage message, long timestamp, long position) {
        return applyClusterCommand(message, timestamp, position, null);
    }

    /**
     * 必要时进入异步命令作用域，按独立窗口方式执行已准备的 Cluster 输入；只在本方法进入作用域时负责退出。
     * @param message 本次借用的原协议输入，调用期间保持其缓冲租约有效
     * @param timestamp 本次请求原复制日志业务时间
     * @param position 本次请求原复制日志位置
     * @param decoded 与该输入配套的可空解码槽，未解码时由正式准入路径补齐
     */
    public CoreResponse applyClusterCommand(CoreMessage message, long timestamp, long position,
                                     DecodedMatchingCommand decoded) {
        return applyDecodedCommand(message, timestamp, position, decoded, true);
    }

    /**
     * 为已准备输入补上空的预计算指纹参数，再进入统一执行路径。
     * @param message 本次借用的原协议输入，调用期间保持其缓冲租约有效
     * @param timestamp 本次请求原复制日志业务时间
     * @param position 本次请求原复制日志位置
     * @param decoded 与该输入配套的可空解码槽，未解码时由正式准入路径补齐
     * @param independent 是否允许当前输入使用独立撮合窗口
     */
    public CoreResponse applyDecodedCommand(CoreMessage message, long timestamp, long position,
                                     DecodedMatchingCommand decoded, boolean independent) {
        return applyDecodedCommand(message, timestamp, position, decoded, independent, null);
    }

    /**
     * 临时接入本条输入的解码对象、指纹和窗口准入标记，调用正常交易入口；无论成功或抛错都恢复外层调用的准备状态。
     * @param message 本次借用的原协议输入，调用期间保持其缓冲租约有效
     * @param timestamp 本次请求原复制日志业务时间
     * @param position 本次请求原复制日志位置
     * @param decoded 与该输入配套的可空解码槽，未解码时由正式准入路径补齐
     * @param independent 是否允许当前输入使用独立撮合窗口
     * @param fingerprint 与当前命令身份配套的内容指纹
     */
    public CoreResponse applyDecodedCommand(CoreMessage message, long timestamp, long position,
                                     DecodedMatchingCommand decoded, boolean independent,
                                     CommandFingerprint fingerprint) {
        // 进入本条已准备输入之前的外层指纹引用
        CommandFingerprint previousFingerprint = preparedIngressFingerprint;
        preparedIngressFingerprint = fingerprint;
        // 进入本条输入之前的独立窗口准入标记
        boolean previousAdmission = clusterPipelineAdmission;
        // 进入本条输入之前的外层原消息引用
        CoreMessage previousMessage = decodedIngressMessage;
        // 进入本条输入之前的外层解码槽引用
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

    /**
     * 取得当前输入的撮合解码结果；复用槽尚未解码时在此延迟解码，使串行输入先经过幂等和来源检查，再落入业务拒绝处理边界。
     * @param message 本次借用的原协议输入，调用期间保持其缓冲租约有效
     */
    public DecodedMatchingCommand decodeMatchingCommand(CoreMessage message) {
        if (message != decodedIngressMessage || decodedIngressCommand == null)
            return DecodedMatchingCommand.decode(message);
        // 解码留在幂等和来源检查之后，并处于准入的业务拒绝处理范围内。
        // 窗口条目通常已经解码；未解码时在此补解。
        if (!decodedIngressCommand.isDecoded()) decodedIngressCommand.decodeInto(message);
        return decodedIngressCommand;
    }

    /**
     * 只做独立窗口的路由预检查：支持同一 Matcher 分片的下单、撤单及对应批量命令，排除需要全局持仓量依赖等情况；解码或路由不适用时返回假，实际业务校验仍走正式入口。
     * @param message 本次借用的原协议输入，调用期间保持其缓冲租约有效
     * @param window 当前输入使用的独立命令窗口，用于记录候选路由
     */
    public boolean prepareClusterPipelineScope(CoreMessage message, ClusterCommandWindow window) {
        if (!activated()) activate();
        assertOwner();
        // 本次预检查的正用户身份
        long user = message.header().userId();
        if (message.header().productLine() != productLine || user <= 0) {
            return rememberPipelineRoute(window, false);
        }
        window.resetCandidate(user);
        try {
            // 从当前输入取得或解出的撮合命令内容
            DecodedMatchingCommand decoded = window.decoded(message);
            switch (message.header().messageType()) {
                case PLACE_ORDER -> {
                    // 当前下单命令，用于判断持仓量依赖和分片路由
                    var command = decoded.placeOrder();
                    // 当前业务查到的标的配置，用于身份、结算规则或路由判断
                    var instrument = runtimeState.instrument(command.instrumentId());
                    if (instrument != null && instrument.usesOpenInterestForUserLimit() && !command.reduceOnly())
                        return rememberPipelineRoute(window, false);
                    window.route(decoded.matcherShard(matchingAdapter, command.instrumentId()),
                            runtimeState.topology().accountLaneMask(user));
                    return rememberPipelineRoute(window, true);
                }
                case CANCEL_ORDER -> {
                    // 撤单载荷中的订单号，用 Owner 活动路由定位分片
                    long orderId = decoded.cancelOrder().orderId();
                    // Owner 索引提供的活动订单路由，还需核对用户及标的
                    var route = factIndexes.activeOrders().activeOrderRoute(orderId);
                    if (route == null || route.userId() != user || route.instrumentId() == null
                            || route.instrumentId().isBlank()) return rememberPipelineRoute(window, false);
                    window.route(matchingAdapter.matcherShardId(route.instrumentId()),
                            runtimeState.topology().accountLaneMask(user));
                    return rememberPipelineRoute(window, true);
                }
                case PLACE_ORDER_BATCH -> {
                    // 当前批次共同使用的 Matcher 分片，负一表示尚未确定
                    int shard = -1;
                    for (var /* 本次检查的批量下单或撤单描述 */ order : decoded.placeOrderBatch().orders()) {
                        // 当前业务查到的标的配置，用于身份、结算规则或路由判断
                        var instrument = runtimeState.instrument(order.instrumentId());
                        if (instrument != null && instrument.usesOpenInterestForUserLimit() && !order.reduceOnly())
                            return rememberPipelineRoute(window, false);
                        // 当前批量成员计算出的 Matcher 分片，必须与已有分片一致
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
                    // 当前批次共同使用的 Matcher 分片，负一表示尚未确定
                    int shard = -1;
                    for (var /* 本次检查的批量下单或撤单描述 */ order : decoded.cancelOrderBatch().orders()) {
                        // Owner 索引提供的活动订单路由，还需核对用户及标的
                        var route = factIndexes.activeOrders().activeOrderRoute(order.orderId());
                        if (route == null || route.userId() != user || route.instrumentId() == null
                                || route.instrumentId().isBlank()) return rememberPipelineRoute(window, false);
                        // 当前批量成员计算出的 Matcher 分片，必须与已有分片一致
                        int current = matchingAdapter.matcherShardId(route.instrumentId());
                        if (shard >= 0 && current != shard) return rememberPipelineRoute(window, false);
                        shard = current;
                    }
                    window.route(shard, runtimeState.topology().accountLaneMask(user));
                    return rememberPipelineRoute(window, true);
                }
                default -> { return rememberPipelineRoute(window, false); }
            }
        } catch (/* 预检查解码不适用的异常，返回串行正式入口继续校验 */ IllegalArgumentException | java.nio.BufferUnderflowException invalid) {
            return rememberPipelineRoute(window, false);
        }
    }

    /**
     * 保存本次窗口预检查是否适用及候选路由；不适用时清空路由提示，避免沿用上一条输入。
     * @param window 当前输入使用的独立命令窗口，用于记录候选路由
     * @param eligible 本次预检查是否适用独立窗口
     */
    private boolean rememberPipelineRoute(ClusterCommandWindow window, boolean eligible) {
        preparedRouteUserLaneBit = eligible ? window.routeUserLaneBit() : 0;
        preparedRouteMatcherShard = eligible ? window.routeMatcherShard() : -1;
        return eligible;
    }

    /**
     * 命令入口：计算或复用指纹，先判定近期收据和在途 UUID，再处理资金长期身份或来源序号，最后分流批量撮合、普通撮合及直接控制命令。
     * @param message 本次借用的原协议输入，调用期间保持其缓冲租约有效
     * @param clusterTimestamp 本命令原复制日志业务时间
     * @param clusterPosition 本命令原复制日志位置
     */
    private CoreResponse applyCommandIngress(CoreMessage message, long clusterTimestamp, long clusterPosition) {
        // 仅同一原输入可复用预计算值，否则重新计算的请求内容指纹
        CommandFingerprint fingerprint = message == decodedIngressMessage && preparedIngressFingerprint != null
                ? preparedIngressFingerprint : fingerprints.of(message);
        // 近期收据或在途 UUID 检查给出的重复或冲突响应，空时继续准入
        CoreResponse replayResponse = checkCommandReplay(message, fingerprint);
        if (replayResponse != null) return replayResponse;

        // 该来源已有的最新序号，未记录时使用负一
        long lastSourceSequence = lastSourceSequences.lookupOrDefault(
                message.header().source(), message.header().sourceId(), -1);
        // 复用已有规范来源键或为新来源建立的键
        SourceKey sourceKey = lastSourceSequences.lastLookupKey();
        if (sourceKey == null) {
            sourceKey = new SourceKey(message.header().source(), message.header().sourceId());
        }
        if (isFundsIdempotencyCommand(message.header().messageType())) {
            directCommand.beginFundsCheck(message, fingerprint, sourceKey, clusterTimestamp,
                    clusterPosition, lastSourceSequence, terminalRetention);
            return null;
        }
        // 来源序号或容量检查给出的拒绝响应，空时继续业务处理
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

    /**
     * 直接控制入口：建立命令事实、回滚检查点及发布作用域，执行业务处理并映射已列明的业务异常；有异步续步时返回空，否则继续完成终态。
     * @param message 本次借用的原协议输入，调用期间保持其缓冲租约有效
     * @param clusterTimestamp 本命令原复制日志业务时间
     * @param clusterPosition 本命令原复制日志位置
     * @param sourceKey 本次命令的来源类型与来源身份键
     * @param fingerprint 与当前命令身份配套的内容指纹
     */
    public CoreResponse applyDirectCommand(CoreMessage message, long clusterTimestamp, long clusterPosition,
                                    SourceKey sourceKey, CommandFingerprint fingerprint) {
        // 直接业务处理状态，未识别类型时为空
        ResponseStatus status;
        // 本次直接命令处理与收尾使用的协议结果码
        CoreResultCode resultCode = CoreResultCode.NONE;
        activateFactContext(message, fingerprint);
        // 命令开始前的运行时修订，失败后辅助判断是否发生变化
        long beforeRuntimeRevision = runtimeState.revision();
        // 命令开始前的账户回滚检查点
        long runtimeCommandCheckpoint = runtimeState.revision();
        // 命令开始前的持仓身份注册检查点
        long positionIdentityCheckpoint = identities.positionCheckpoint();
        directCommand.initialize(message, fingerprint, sourceKey, clusterTimestamp, clusterPosition,
                beforeRuntimeRevision, runtimeCommandCheckpoint, positionIdentityCheckpoint);
        resultBuilder.beginCommand();
        admissions.clearQueuedMatching();
        commits.publication.begin();
        try {
            if (directCommands.dispatch(message, clusterTimestamp)) {
                status = ResponseStatus.APPLIED;
            } else {
                switch (message.header().messageType()) {
                    case PLACE_ORDER, CANCEL_ORDER, REPLACE_ORDER, AMEND_ORDER,
                            PLACE_ORDER_BATCH, CANCEL_ORDER_BATCH, AMEND_ORDER_BATCH,
                            EXECUTE_LIQUIDATION, SETTLE_INSTRUMENT ->
                            throw new IllegalStateException("matching command must use async continuation");
                    default -> status = null;
                }
            }
        } catch (/* 直接命令的明确业务拒绝，保留原结果码 */ CoreStateRejectedException exception) {
            status = ResponseStatus.REJECTED;
            resultCode = CoreResultCode.fromRejectionCode(exception.code());
        } catch (/* 直接命令整数计算溢出，映射为 OVERFLOW */ ArithmeticException exception) {
            status = ResponseStatus.REJECTED;
            resultCode = CoreResultCode.ARITHMETIC_OVERFLOW;
        } catch (/* 直接命令参数不合法，映射为无效命令 */ IllegalArgumentException exception) {
            status = ResponseStatus.REJECTED;
            resultCode = CoreResultCode.INVALID_COMMAND;
        }
        if (directCommand.hasControlWork()) return null;
        return finishDirectCommand(message, clusterTimestamp, clusterPosition, sourceKey, fingerprint,
                beforeRuntimeRevision, runtimeCommandCheckpoint,
                positionIdentityCheckpoint, status, resultCode);
    }

    /**
     * 先查询近期终态收据，再查询在途 UUID；指纹冲突直接拒绝，同一在途命令只返回等待提示，避免重复准入。
     * @param message 本次借用的原协议输入，调用期间保持其缓冲租约有效
     * @param fingerprint 与当前命令身份配套的内容指纹
     */
    private CoreResponse checkCommandReplay(CoreMessage message, CommandFingerprint fingerprint) {
        if (resultLedger.isEmpty() && pendingMatching.isEmpty()) return null;
        // 本次查重命令 UUID 的高 64 位
        long msb = message.header().commandIdMsb();
        // 本次查重命令 UUID 的低 64 位
        long lsb = message.header().commandIdLsb();
        if (!resultLedger.isEmpty()) {
            // 近期收据表中的同 UUID 终态，随后核对指纹
            CommandResultLedger.StoredResult terminalDuplicate = resultLedger.get(msb, lsb);
            if (terminalDuplicate != null) {
                return resultLedger.duplicateResponse(terminalDuplicate, fingerprint, appliedCommandCount);
            }
        }
        if (!pendingMatching.isEmpty()) {
            // 撮合队列中同 UUID 的在途命令，随后核对指纹
            CommandSlot pendingDuplicate = pendingMatching.findByCommandId(msb, lsb);
            if (pendingDuplicate != null) {
                if (!pendingDuplicate.fingerprint().equals(fingerprint)) {
                    return new CoreResponse(com.surprising.aeron.protocol.ResponseStatus.REJECTED,
                            com.surprising.aeron.protocol.ResponseStatus.REJECTED,
                            CoreResultCode.IDEMPOTENCY_CONFLICT, appliedCommandCount,
                            EMPTY_RESPONSE_DATA);
                }
                return new CoreResponse(com.surprising.aeron.protocol.ResponseStatus.DUPLICATE,
                        com.surprising.aeron.protocol.ResponseStatus.OK, CoreResultCode.MATCHING_PENDING,
                        pendingDuplicate.sequence(),
                        EMPTY_RESPONSE_DATA);
            }
        }
        return null;
    }

    /**
     * 把长期资金身份查询结果转换为重试响应：未命中返回空，指纹不符拒绝，已完成的同一资金命令返回重复成功和当前计数，不重建原响应正文。
     * @param fingerprint 与当前命令身份配套的内容指纹
     * @param retained 长期资金索引查出的可空命令身份记录
     */
    public CoreResponse fundsReplayResponse(CommandFingerprint fingerprint, CommandFingerprint retained) {
        if (retained == null) return null;
        if (!retained.equals(fingerprint)) {
            return new CoreResponse(com.surprising.aeron.protocol.ResponseStatus.REJECTED,
                    com.surprising.aeron.protocol.ResponseStatus.REJECTED,
                    CoreResultCode.IDEMPOTENCY_CONFLICT, appliedCommandCount,
                    EMPTY_RESPONSE_DATA);
        }
        return new CoreResponse(com.surprising.aeron.protocol.ResponseStatus.DUPLICATE,
                    com.surprising.aeron.protocol.ResponseStatus.APPLIED, CoreResultCode.NONE,
                    appliedCommandCount, EMPTY_RESPONSE_DATA);
    }

    /**
     * 拒绝不大于已记来源序号的输入，并限制新增来源数量；此处只判定，不更新来源表。
     * @param message 本次借用的原协议输入，调用期间保持其缓冲租约有效
     * @param lastSourceSequence 该来源此前记录的最新序号，尚未记录时为负一
     */
    public CoreResponse checkSourceSequence(CoreMessage message, long lastSourceSequence) {
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

    /**
     * 直接控制完成入口：必要时回滚被拒绝的命令，收集关闭的触发单、订单变化和账户提交，等待资金校验及发布完成，最后进入资金长期身份保留与终态保存。空状态表示无效消息，异步未齐返回空。
     * @param message 本次借用的原协议输入，调用期间保持其缓冲租约有效
     * @param clusterTimestamp 本命令原复制日志业务时间
     * @param clusterPosition 本命令原复制日志位置
     * @param sourceKey 本次命令的来源类型与来源身份键
     * @param fingerprint 与当前命令身份配套的内容指纹
     * @param beforeRuntimeRevision 本命令开始前的运行时修订，用于判断失败后是否需要回滚
     * @param runtimeCommandCheckpoint 本命令开始前的账户命令回滚检查点
     * @param positionIdentityCheckpoint 本命令开始前的持仓身份注册检查点
     * @param status 业务处理得到的状态，空表示未识别直接命令类型
     * @param resultCode 与当前业务状态配套的协议结果码
     */
    public CoreResponse finishDirectCommand(CoreMessage message, long clusterTimestamp, long clusterPosition,
            SourceKey sourceKey, CommandFingerprint fingerprint,
            long beforeRuntimeRevision, long runtimeCommandCheckpoint, long positionIdentityCheckpoint,
            ResponseStatus status, CoreResultCode resultCode) {
        // 本次计算出的下一个已受理序号，实际写回仍需等待完成条件
        long nextAppliedCommandCount = Math.incrementExact(appliedCommandCount);
        if (directCommand.publicationComplete()) {
            return finishResponseAfterFundsRetention(message, clusterTimestamp, clusterPosition,
                    sourceKey, fingerprint, status, resultCode, nextAppliedCommandCount);
        }
        if (!directCommand.finalizationPrepared()) {
            if (status != ResponseStatus.APPLIED
                    && (commits.publication.dirty()
                    || runtimeState.revision() != beforeRuntimeRevision
                    || runtimeState.hasUncommittedCommandChanges())) {
                rollbackCommandState(runtimeCommandCheckpoint, positionIdentityCheckpoint,
                        Math.incrementExact(appliedCommandCount));
            }
            if (status == null) {
                commits.publication.clearBatchState();
                return finishDirectCommandContext(rejected(CoreResultCode.INVALID_MESSAGE));
            }
            if (status == ResponseStatus.APPLIED) {
                try {
                    if (!triggers.collectClosingTriggerIds().isEmpty()) {
                        commits.publication.request();
                    }
                } catch (/* 本分支捕获的失败，按当前边界记录、延后拒绝或继续传播 */ ArithmeticException failure) {
                    if (deferFinalizationRejection(CoreResultCode.ARITHMETIC_OVERFLOW)) return null;
                    throw failure;
                }
                if (pendingMatching.size() + admissions.queuedMatchingCount()
                        > pendingMatching.capacity()) {
                    admissions.clearQueuedMatching();
                    if (deferFinalizationRejection(CoreResultCode.MATCHING_BACKPRESSURE)) return null;
                    rollbackCommandState(runtimeCommandCheckpoint, positionIdentityCheckpoint,
                            Math.incrementExact(appliedCommandCount));
                    return finishDirectCommandContext(rejected(CoreResultCode.MATCHING_BACKPRESSURE));
                }
            }
            if (status == ResponseStatus.APPLIED) {
                // 本次需要校验或盖章的订单身份，没有变化时使用共享空列表
                java.util.List<Long> changedOrderIds = resultBuilder.commandChangedOrderIds == null
                        ? java.util.List.of() : resultBuilder.commandChangedOrderIds;
                try {
                    runtimeState.validateCommitStampInputs(
                            clusterTimestamp, clusterPosition, changedOrderIds);
                } catch (/* 订单变化盖章准备失败，按异步续步或同步回滚路径处理 */ IllegalStateException exception) {
                    if (deferFinalizationRejection(CoreResultCode.INVALID_COMMAND)) return null;
                    rollbackCommandState(runtimeCommandCheckpoint, positionIdentityCheckpoint,
                            Math.incrementExact(appliedCommandCount));
                    return finishDirectCommandContext(rejected(CoreResultCode.INVALID_COMMAND));
                }
            }
            resultBuilder.materializeDirectChangeAccumulators();
            if (status == ResponseStatus.APPLIED && !resultBuilder.commandChangedUserIds.isEmpty()) {
                runtimeState.releaseCompletedSequentialLaneStage();
                directCommand.commitEvent(runtimeState.dispatchLaneMutation(
                        nextAppliedCommandCount, resultBuilder.commandChangedUserIds,
                        resultBuilder.commandChangedOrderIds, triggers.closingTriggerIds(),
                        clusterTimestamp, clusterPosition));
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
        commits.publication.complete();
        directCommand.markPublicationComplete();
        return finishResponseAfterFundsRetention(message, clusterTimestamp, clusterPosition,
                sourceKey, fingerprint, status, resultCode, nextAppliedCommandCount);
    }

    /**
     * 成功资金命令先等长期身份保存，再推进计数、记来源、追加衍生撮合并保存近期收据，最后移交响应缓冲租约。长期保存失败可能发生在账户和发布已完成之后，不能据此声称业务已回滚。
     * @param message 本次借用的原协议输入，调用期间保持其缓冲租约有效
     * @param clusterTimestamp 本命令原复制日志业务时间
     * @param clusterPosition 本命令原复制日志位置
     * @param sourceKey 本次命令的来源类型与来源身份键
     * @param fingerprint 与当前命令身份配套的内容指纹
     * @param status 业务处理得到的状态，空表示未识别直接命令类型
     * @param resultCode 与当前业务状态配套的协议结果码
     * @param nextAppliedCommandCount 本次终态将登记的下一个 Core 序号，调用前尚未写回
     */
    private CoreResponse finishResponseAfterFundsRetention(CoreMessage message, long clusterTimestamp,
            long clusterPosition, SourceKey sourceKey, CommandFingerprint fingerprint,
            ResponseStatus status, CoreResultCode resultCode, long nextAppliedCommandCount) {
        if (isFundsIdempotencyCommand(message.header().messageType())
                && status == ResponseStatus.APPLIED) {
            if (!directCommand.retainFundsOnLedger()) {
                directCommand.result(status, resultCode);
                return null;
            }
        }
        recordAppliedSequence(nextAppliedCommandCount, sourceKey, message.header().sourceSequence());
        admissions.appendQueuedMatching(clusterTimestamp, clusterPosition);
        // 结果构建器交出的池化响应正文，随后移交收据和传输租约
        byte[] responseData = resultBuilder.commandResultData();
        // 当前响应正文在池化缓冲中的起始字节偏移
        int responseOffset = resultBuilder.responseDataOffset();
        // 当前响应正文的有效字节数
        int responseLength = resultBuilder.responseDataLength();
        resultLedger.storeOwnedResult(message.header().commandIdMsb(), message.header().commandIdLsb(), fingerprint, status, resultCode,
                appliedCommandCount, responseData,
                responseOffset, responseLength);
        // 使用原请求与当前终态创建的响应，已获得对应正文租约
        CoreResponse response = message.ownedResponse(status, status, resultCode, appliedCommandCount,
                responseData, responseOffset, responseLength);
        resultBuilder.transferResponseOwnership();
        return finishDirectCommandContext(response);
    }

    /**
     * 清理唯一直接命令槽，再结束当前命令事实上下文并返回原响应。
     * @param response 要交接或归还的响应，包含其缓冲租约
     */
    private CoreResponse finishDirectCommandContext(CoreResponse response) {
        directCommand.clear();
        return finishFactContext(response);
    }

    /**
     * 仅在异步命令作用域内，把完成阶段的业务拒绝记回活动控制槽，留给下一轮收尾；同步路径返回假，由调用方立即处理。
     * @param code 异步完成阶段需要保存的业务拒绝码
     */
    private boolean deferFinalizationRejection(CoreResultCode code) {
        if (!directCommand.active()) {
            throw new IllegalStateException("asynchronous finalization has no command context");
        }
        directCommand.result(ResponseStatus.REJECTED, code);
        return true;
    }
    /** 唯一直接控制命令续步槽，与普通撮合序号槽分开管理。 */
    public final DirectCommandSlot directCommand = new DirectCommandSlot(this);

    /**
     * 写入调用方已经判定的来源序号；本方法不另做递增校验。
     * @param sourceKey 本次命令的来源类型与来源身份键
     * @param sourceSequence 本次完成后需要登记的来源序号
     */
    void recordSourceSequence(SourceKey sourceKey, long sourceSequence) {
        lastSourceSequences.put(sourceKey, sourceSequence);
    }

    /**
     * 列举对外命令中需要撮合路径的类型；内部触发执行命令由衍生撮合路径单独处理。
     * @param type 要分类的协议消息类型
     */
    static boolean isMatchingCommand(CoreMessageType type) {
        return type == CoreMessageType.PLACE_ORDER || type == CoreMessageType.CANCEL_ORDER
                || type == CoreMessageType.REPLACE_ORDER || type == CoreMessageType.AMEND_ORDER
                || isOrderBatchCommand(type)
                || type == CoreMessageType.EXECUTE_LIQUIDATION
                || type == CoreMessageType.EXECUTE_LIQUIDATION_BATCH
                || type == CoreMessageType.SETTLE_INSTRUMENT;
    }

    /**
     * 判断是否为批量下单、批量撤单或批量改单。
     * @param type 要分类的协议消息类型
     */
    public static boolean isOrderBatchCommand(CoreMessageType type) {
        return type == CoreMessageType.PLACE_ORDER_BATCH || type == CoreMessageType.CANCEL_ORDER_BATCH
                || type == CoreMessageType.AMEND_ORDER_BATCH;
    }

    /**
     * 清除命令事实激活标记，并把原响应交还调用方。
     * @param response 要交接或归还的响应，包含其缓冲租约
     */
    public CoreResponse finishFactContext(CoreResponse response) {
        clearFactContext();
        return response;
    }

    /**
     * 归还响应占用的缓冲租约；空响应不处理，缓冲是否可复用仍取决于其他引用。
     * @param response 要交接或归还的响应，包含其缓冲租约
     */
    void releaseResponse(CoreResponse response) {
        if (response != null) responseArena.release(response.dataUnsafe());
    }

    /**
     * 确认命令、指纹和提交发布边界有效，再标记当前命令事实已经激活。
     * @param command 本次业务使用的已解码命令或命令槽，沿用当前命令生命周期
     * @param fingerprint 与当前命令身份配套的内容指纹
     */
    public void activateFactContext(CoreMessage command, CommandFingerprint fingerprint) {
        if (command == null || fingerprint == null) {
            throw new IllegalArgumentException("complete commit context is required before mutation");
        }
        commits.publication.assertHealthy();
        factContextActive = true;
    }

    /**
     * 结束当前命令事实作用域，不在此执行账户回滚。
     */
    public void clearFactContext() {
        factContextActive = false;
    }

    /**
     * 将原日志位置和业务时间恢复到准入前值，再生成本次准入拒绝响应。
     * @param resultCode 与当前业务状态配套的协议结果码
     */
    public CoreResponse admissionRejected(CoreResultCode resultCode) {
        currentClusterTimestamp = admissionPreviousClusterTimestamp;
        currentClusterPosition = admissionPreviousClusterPosition;
        return rejected(resultCode);
    }

    /**
     * 按 Core 序号取走已完成的撮合终态；尚未可取时由既有完成存储返回空。
     * @param sequence 本次业务对应的 Core 序号
     */
    public com.surprising.aeron.service.matcher.MatchingResult takeMatchingResult(long sequence) {
        return matchingFlow.takeMatchingResult(sequence);
    }
    /**
     * 判断在途撮合期间是否可继续处理该请求：允许撮合命令和不建立业务读栅栏的运行指标查询。
     * @param message 本次借用的原协议输入，调用期间保持其缓冲租约有效
     */
    static boolean isCommitCursorSafeWhileMatching(CoreMessage message) {
        boolean nonFencingQuery = message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.LANE_METRICS_QUERY;
        return nonFencingQuery || (message.header().kind() == WireMessageKind.COMMAND
                && isMatchingCommand(message.header().messageType()));
    }

    /**
     * 列举需要账户读取边界的业务查询类型，供入口选择读栅栏。
     * @param type 要分类的协议消息类型
     */
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

    /**
     * 列举能够只等待指定用户 Lane 的查询类型，其余账户读取使用整体前缀边界。
     * @param type 要分类的协议消息类型
     */
    static boolean singleUserLaneQuery(CoreMessageType type) {
        return switch (type) {
            case USER_STATE_HASH_QUERY, USER_STATE_QUERY, CLIENT_ORDER_STATE_QUERY,
                    USER_OPEN_ORDERS_QUERY, USER_OPEN_TRIGGER_ORDERS_QUERY, RISK_STATE_QUERY,
                    ORDER_PREFLIGHT_QUERY -> true;
            default -> false;
        };
    }

    /**
     * 按 UUID 两个原始长整数查在途撮合序号；未找到返回零。
     * @param msb 命令 UUID 的高 64 位
     * @param lsb 命令 UUID 的低 64 位
     */
    public long matchingSequence(long msb, long lsb) {
        // 按 UUID 查到的可空在途撮合槽
        CommandSlot pending = pendingMatching.findByCommandId(msb, lsb);
        return pending == null ? 0 : pending.sequence();
    }

    /**
     * 按可空 UUID 查在途撮合序号；空身份返回零。
     * @param commandId 要查询的可空命令 UUID，空时返回零
     */
    public long matchingSequence(UUID commandId) {
        return commandId == null ? 0 : matchingSequence(commandId.getMostSignificantBits(), commandId.getLeastSignificantBits());
    }

    /**
     * 探测控制槽、撮合队首及批次中是否有已完成的账户或账本回执可推进；这是唤醒提示，不表示整条命令已经终态完成。
     */
    boolean hasCompletedLaneCommit() {
        // 当前直接控制命令保存的账户提交回执
        var directCommit = directCommand.commitEvent();
        if (directCommit != null && directCommit.complete()) return true;

        // 当前在途撮合队首，只观察该前缀的可推进事实
        CommandSlot pending = pendingMatching.head();
        if (pending == null) return false;
        // 队首命令持有的结算事件，账户或账本完成后可继续推进
        var settlement = pending.settlementEvent();
        if (settlement != null && settlement.complete()) return true;
        if (pending.commitEvent() != null && pending.commitEvent().complete()) return true;
        // 队首批量命令的专用事实槽，批次回执可能晚于普通通知完成
        OrderBatchPending batch = pending.orderBatch();
        if (batch == null) return false;
        // 批次事实保存在专用批次槽里，普通命令槽不重复保存。
        // 即使 Matcher 和账户通知已消费，账本仍可能刚完成，所以继续探测这些事实。
        if (batch.itemSettlementEvent() != null && batch.itemSettlementEvent().complete()) return true;
        if (batch.settlementEvent() != null && batch.settlementEvent().complete()) return true;
        return batch.laneCommitEvent() != null && batch.laneCommitEvent().complete();
    }

    /**
     * 有序撮合提交完成后的终态交接回调，由 Owner 调用。
     */
    @FunctionalInterface
    public interface MatchingCommitHandler {
        /**
         * 接收已经完成有序提交的命令序号及响应，由外层继续交接终态。
         * @param sequence 本次业务对应的 Core 序号
         * @param response 要交接或归还的响应，包含其缓冲租约
         */
        void onCommitted(long sequence, CoreResponse response);
    }

    /**
     * 在 Owner 线程读取当前终态已累计的实际 TRADE 事件数。
     */
    public long terminalTradeCount() {
        assertOwner();
        return terminalTradeCount;
    }

    /**
     * 从原语观测计数直接构造独立视图；各字段允许独立滞后，不取得账户业务读取权限。
     */
    public CoreLaneMetricsView laneMetrics() {
        assertOwner();
        int count = matchingAdapter.topology().accountLaneCount();
        int operations = Math.multiplyExact(count, CoreLaneMetricsView.OPERATION_TYPE_COUNT);
        long[] revisions = new long[count], applied = new long[count], committed = new long[count];
        int[] depths = new int[count], capacities = new int[count], highWaterMarks = new int[count];
        long[] completed = new long[operations];
        for (int laneId = 0; laneId < count; laneId++) {
            var lane = runtimeState.accountLane(laneId);
            revisions[laneId] = lane.observedRevision();
            committed[laneId] = lane.observedCommittedSequence();
            applied[laneId] = Math.max(committed[laneId], lane.observedAppliedSequence());
            depths[laneId] = runtimeState.laneWorkerDepth(laneId);
            capacities[laneId] = runtimeState.laneWorkerCapacity(laneId);
            highWaterMarks[laneId] = runtimeState.observeLaneQueueHighWaterMark(laneId, depths[laneId]);
            for (int operation = 0; operation < CoreLaneMetricsView.OPERATION_TYPE_COUNT; operation++) {
                completed[laneId * CoreLaneMetricsView.OPERATION_TYPE_COUNT + operation] = lane.observedOperationCount(operation);
            }
        }
        return new CoreLaneMetricsView(matchingAdapter.topology().matchingEngineCount(), count,
                matcherPipeline.submissionDepth(), matcherPipeline.capacity(), matcherPipeline.submissionHighWaterMark(),
                matcherPipeline.completionDepth(), matcherPipeline.capacity(), matcherPipeline.completionHighWaterMark(),
                pendingMatching.inFlight(), pendingMatching.capacity(), pendingMatching.highWaterMark(), committedCoreSequence,
                revisions, applied, committed, depths, capacities, highWaterMarks, new long[count], new long[count],
                completed, new long[operations], new long[operations], new long[operations]);
    }

    /**
     * 在 Owner 上编码 Matcher 队列、在途窗口、提交水位及账户 Lane 观测指标；这些采样不构成一致的业务快照。
     */
    byte[] encodeLaneMetrics() {
        assertOwner();
        // 需要编码的 Matcher 分片数量
        int count = matchingAdapter.topology().accountLaneCount();
        // 当前复用协议编码器，按既有缓冲布局写入观测指标
        var encoder = new com.surprising.aeron.protocol.CoreLaneMetricsCodec.Encoder(
                matchingAdapter.topology().matchingEngineCount(), count,
                matcherPipeline.submissionDepth(), matcherPipeline.capacity(),
                matcherPipeline.submissionHighWaterMark(), matcherPipeline.completionDepth(),
                matcherPipeline.capacity(), matcherPipeline.completionHighWaterMark(),
                pendingMatching.inFlight(), pendingMatching.capacity(),
                pendingMatching.highWaterMark(), committedCoreSequence);
        for (/* 当前正在采样队列指标的 Matcher 分片下标 */ int laneId = 0; laneId < count; laneId++) {
            runtimeState.writeAccountLaneMetrics(laneId, encoder);
        }
        return encoder.finish();
    }

    /**
     * 返回 Matcher 与账户路由共用的 Lane 拓扑。
     */
    LaneTopology laneTopology() {
        return matchingAdapter.topology();
    }

    /**
     * 按指定完成栅栏和全局状态请求各账户 Lane 的快照。
     * @param fenceSequence 账户快照需要满足的完成栅栏序号
     * @param globalState 与账户 Lane 快照配套的全局状态镜像
     */
    public List<com.surprising.aeron.service.snapshot.state.AccountLaneSnapshot> accountLaneSnapshots(
            long fenceSequence, TradingCoreState globalState) {
        return runtimeState.accountLaneSnapshots(fenceSequence, globalState);
    }

    /**
     * 只允许在激活前恢复账户 Lane 快照，并使用当前快照全局状态核对恢复边界。
     * @param snapshots 待恢复的各账户 Lane 快照
     * @param fenceSequence 账户快照需要满足的完成栅栏序号
     */
    public void restoreAccountLaneSnapshots(
            List<com.surprising.aeron.service.snapshot.state.AccountLaneSnapshot> snapshots,
            long fenceSequence) {
        if (activated) throw new IllegalStateException("account lanes must restore before activation");
        runtimeState.restoreAccountLaneSnapshots(snapshots, fenceSequence, snapshotTradingState());
    }

    /**
     * 以禁用 CPU 亲和的配置激活运行时。
     */
    public void activate() {
        activate(TradingCpuAffinity.disabled());
    }

    /**
     * 启动步骤：先校验亲和配置，再启动 Matcher，绑定 Owner，启动账户 Lane 和账本 Lane，最后激活适配器和发布器。启动中的运行时异常会尝试关闭资源并保留关闭故障；已激活调用直接返回。
     * @param affinity 本次激活采用的 CPU 亲和配置
     */
    public void activate(TradingCpuAffinity affinity) {
        if (activated) return;
        if (closed) throw new IllegalStateException("cannot activate closed core state");
        affinity.validateConfiguration(laneTopology());
        try {
            matcherPipeline.start(matchingAdapter::activateShard, affinity);
            bindOwner();
            runtimeState.startAccountLanes(affinity);
            runtimeState.startSystemLedgerLane();
            matchingAdapter.activate();
            commits.publication.activate();
            activated = true;
        } catch (/* 激活失败仍关闭所有已启动资源。 */ RuntimeException | Error failure) {
            try {
                close();
            } catch (/* 保留原启动错误与清理故障。 */ RuntimeException | Error closeFailure) {
                if (failure != closeFailure) failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    /**
     * 返回近期终态墓碑当前条数，用于保留窗口观测。
     */
    int terminalRetentionTombstoneCount() {
        return terminalRetention.tombstoneCount();
    }

    /**
     * 返回运行时保存的客户身份数量。
     */
    int runtimeClientIdentityCount() {
        return identities.clientIdentityCount();
    }

    /**
     * 取得当前业务状态哈希，在完整快照与账户访问边界内物化状态，并合并费用政策和待完成划转哈希；读边界在退出时释放。
     */
    public long stateHash() {
        runtimeState.requireSnapshotFenceReady();
        // 当前是否需要由本方法取得账户访问边界；它不表示访问已经取得
        boolean acquired = acquireOwnerLaneAccess("businessStateHash");
        try {
            // 在账户和快照读取边界内取得的业务物化视图
            TradingCoreState current = tradingState();
            return canonicalBusinessStateHash(current.businessStateHash(),
                    runtimeState.feePoliciesSnapshot(), runtimeState.pendingTransfersSnapshot());
        } finally {
            if (acquired) runtimeState.releaseOwnerLaneAccess();
        }
    }

    /**
     * 单次快照尝试尚未完成时的明确等待信号。
     */
    public static final class SnapshotNotReadyException extends IllegalStateException {
        /**
         * 构造快照尚未准备好的明确异常，交由外层决定是否重试。
         */
        public SnapshotNotReadyException() {
            super("snapshot not ready");
        }
    }

    /**
     * 快照读取栅栏超过等待预算时的故障。
     */
    public static final class SnapshotFenceTimeoutException extends IllegalStateException {
        /**
         * 构造快照栅栏等待超时的明确异常。
         */
        public SnapshotFenceTimeoutException() {
            super("snapshot fence timed out");
        }
    }

    /**
     * 快照边界与 Matcher 捕获实现之间的契约，未来结果不等于必须创建线程。
     */
    public @FunctionalInterface
    interface MatcherSnapshotCapture {
        /**
         * 按指定快照身份及提交边界捕获 Matcher 状态并返回未来结果；接口本身不承诺创建后台线程。
         * @param snapshotId 本次快照身份，交给既有捕获和编码流程
         * @param coreSequence 本次准备或快照固定的 Core 序号
         * @param businessStateHash 本次快照固定的业务状态摘要
         * @param state 本次快照固定的全局业务镜像
         * @param activeOrders 与快照边界对应的活动订单内容
         */
        java.util.concurrent.CompletionStage<MatcherSnapshot> capture(
                long snapshotId,
                long coreSequence,
                long businessStateHash,
                TradingCoreState state,
                Iterable<CoreOrderState> activeOrders);
    }

    /**
     * 快照镜像到分块编码结果的契约，允许接入既有同步或异步编码实现。
     */
    public @FunctionalInterface
    interface SnapshotEncoder {
        /**
         * 把已经组装的快照镜像编码为分块结果；具体同步或异步执行方式由注入实现决定。
         * @param image 已经组装好的快照镜像，交给编码实现
         */
        CompletableFuture<SectionedCoreSnapshotCodec.SectionedSnapshot> encode(CoreSnapshotImage image);
    }

    /**
     * 尝试毒化 Matcher 并登记带命令序号的致命分歧；毒化本身失败时保留到异常因果中，返回登记的故障，由后续健康检查传播。
     * @param pending 当前借用的在途命令槽，完成或释放后不能继续使用
     * @param detail 此次致命分歧的具体诊断内容
     * @param cause 导致撮合失败的可空原异常
     */
    public FatalMatchingDivergenceException failMatching(
            CommandSlot pending,
            String detail,
            Throwable cause) {
        try {
            matchingAdapter.poisonFromOwner("deterministic matcher settlement failed sequence="
                    + pending.sequence() + " operation=" + pending.operation());
        } catch (/* 尝试毒化 Matcher 时发生的额外故障，保留在最终异常因果中 */ RuntimeException poisonFailure) {
            if (cause != null) cause.addSuppressed(poisonFailure);
            else cause = poisonFailure;
        }
        fatalFailure = cause == null
                ? new FatalMatchingDivergenceException(pending.operation().name(), pending.sequence(), 0, detail)
                : new FatalMatchingDivergenceException(pending.operation().name(), pending.sequence(), 0, detail, cause);
        return fatalFailure;
    }

    /**
     * 必要时激活并确认 Owner 与健康状态，再要求直接命令、在途撮合及盘口查询均已完成；此检查不表示所有实时读取都已结束。
     */
    public void assertClusterCallbackComplete() {
        if (!activated) activate();
        assertOwner();
        assertHealthy();
        if (directCommand.active() || !pendingMatching.isEmpty()
                || bookQueries.hasPendingQueries()) {
            throw new IllegalStateException("unfinished business work outside cluster log callback"
                    + " direct=" + directCommand.active()
                    + " matching=" + !pendingMatching.isEmpty()
                    + " book=" + bookQueries.hasPendingQueries());
        }
    }

    /**
     * 依次检查终态索引、撮合分歧、提交发布、账户 Lane、发布器和快照审计故障，发现已有故障立即传播。
     */
    public void assertHealthy() {
        terminalRetention.assertHealthy();
        if (fatalFailure != null) throw fatalFailure;
        if (commitPublicationFailure != null) throw commitPublicationFailure;
        if (runtimeState != null) runtimeState.assertAccountLanesHealthy();
        commits.publication.assertHealthy();
        // 后台快照审计已经记录的故障，健康检查负责传播
        RuntimeException auditFailure = snapshots.snapshotAuditFailure().get();
        if (auditFailure != null) throw auditFailure;
    }

    /**
     * 按指定产品线解码完整分块快照，恢复协议决定候选运行时的装配与激活。
     * @param productLine 必须明确隔离的所属产品线
     * @param snapshot 完整快照编码字节
     */
    public static TradingCoreRuntime fromSnapshot(ProductLine productLine, byte[] snapshot) {
        return SectionedCoreSnapshotCodec.decode(snapshot, productLine);
    }

    /**
     * 只检查快照清单和产品线边界，不在这里运行交易命令。
     * @param productLine 必须明确隔离的所属产品线
     * @param snapshot 完整快照编码字节
     */
    public static CoreSnapshotManifest inspectSnapshot(ProductLine productLine, byte[] snapshot) {
        return SectionedCoreSnapshotCodec.manifest(snapshot, productLine);
    }

    /**
     * 在 Owner 上计算下一个 Core 序号，溢出直接报错；此方法只计算，不预留或写回计数。
     */
    public long nextAppliedSequence() {
        assertOwner();
        return Math.incrementExact(appliedCommandCount);
    }

    /**
     * 写入调用方提供的已受理序号，刷新可提交前缀并按需记录来源；序号有效性和调用顺序由上层保证。
     * @param sequence 本次业务对应的 Core 序号
     * @param sourceKey 本次命令的来源类型与来源身份键
     * @param sourceSequence 本次完成后需要登记的来源序号
     */
    public void recordAppliedSequence(long sequence, SourceKey sourceKey, long sourceSequence) {
        assertOwner();
        this.appliedCommandCount = sequence;
        refreshCommittedCoreSequence();
        if (sourceKey != null) {
            recordSourceSequence(sourceKey, sourceSequence);
        }
    }

    /**
     * 把已提交水位推进到在途撮合队首之前，或无在途时推进到已受理计数；随后释放已经越过退休边界的订单栅栏。
     */
    public void refreshCommittedCoreSequence() {
        assertOwner();
        // 本次允许推进的 Core 前缀上界，不越过在途撮合队首
        long candidate = pendingMatching.isEmpty()
                ? appliedCommandCount
                : Math.subtractExact(pendingMatching.firstSequence(), 1);
        if (candidate > committedCoreSequence) {
            committedCoreSequence = candidate;
        }
        runtimeState.releaseRetiredOrderFence(committedCoreSequence);
    }

    /**
     * 活动账户已启动且当前未持有 Owner 访问边界时，最多自旋等待五秒取得访问权；返回是否由本次调用新取得访问权。
     * @param operation 超时报错信息中的操作名称
     */
    private boolean acquireOwnerLaneAccess(String operation) {
        // 当前是否需要由本方法取得账户访问边界
        boolean acquired = runtimeState.accountLanesStarted() && !runtimeState.ownerLaneAccess();
        if (acquired) {
            // 等待账户访问权的单调纳秒截止时间，最多五秒
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!runtimeState.tryAcquireOwnerLaneAccess()) {
                if (System.nanoTime() - deadline >= 0) {
                    throw new IllegalStateException("timed out waiting for owner lane access in " + operation);
                }
                Thread.onSpinWait();
            }
        }
        return acquired;
    }

    /**
     * 在快照及账户读取边界内取得物化业务视图；运行时修订、行情修订或发布序号变化才重建缓存。活动账户访问最多自旋等待五秒，最后释放本方法需要取得的访问边界。
     */
    public TradingCoreState tradingState() {
        runtimeState.requireSnapshotFenceReady();
        // 当前是否需要由本方法取得账户访问边界；它不表示访问已经取得
        boolean acquired = acquireOwnerLaneAccess("current state materialization");
        try {
            // 当前运行时修订，与物化缓存对应值比较
            long currentRevision = runtimeState.revision();
            // 当前行情修订，与物化缓存对应值比较
            long currentMarketRevision = runtimeState.marketRevision();
            // 当前已发布投影序号，与物化缓存对应值比较
            long currentSequence = commits.publication.publishedSequence();
            if (cachedTradingState == null || cachedRevision != currentRevision || cachedMarketRevision != currentMarketRevision || cachedSequence != currentSequence) {
                cachedTradingState = com.surprising.aeron.service.snapshot.state.RuntimeStateMaterializer.materialize(runtimeState, identities);
                cachedRevision = currentRevision;
                cachedMarketRevision = currentMarketRevision;
                cachedSequence = currentSequence;
            }
            return cachedTradingState;
        } finally {
            if (acquired) runtimeState.releaseOwnerLaneAccess();
        }
    }

    /**
     * 已有快照栅栏时使用其固定状态，否则通过正常物化读取取得业务状态。
     */
    public TradingCoreState snapshotTradingState() {
        // 当前可空快照栅栏，存在时使用它已经固定的状态
        CoreSnapshotLifecycle.SnapshotFence fence = snapshots.snapshotFence();
        return fence != null && fence.snapshotState() != null ? fence.snapshotState() : tradingState();
    }
    /**
     * 判断是否不存在尚未完成的风险扫描。
     */
    boolean runtimeRiskScanComplete() { return runtimeState.firstIncompleteRiskScan() == null; }
    /**
     * 判断指定标的风险扫描是否不存在或已经完成。
     * @param instrumentId 对外标的 ID，按本产品线身份表解析
     */
    boolean runtimeRiskScanComplete(String instrumentId) {
        // 指定标的当前风险扫描状态，空表示没有该扫描
        RiskScanRuntime scan = runtimeRiskScan(instrumentId);
        return scan == null || scan.complete();
    }
    /**
     * 把外部标的转成内部身份并查风险扫描状态，未知标的返回空。
     * @param instrumentId 对外标的 ID，按本产品线身份表解析
     */
    RiskScanRuntime runtimeRiskScan(String instrumentId) {
        // 从外部标的解析出的内部身份，未知标的可为空
        Integer symbolId = identities.findSymbolId(instrumentId);
        return symbolId == null ? null : runtimeState.riskScan(symbolId);
    }
    /**
     * 把外部标的转成内部身份并查标记价格，未知标的返回空。
     * @param instrumentId 对外标的 ID，按本产品线身份表解析
     */
    MarkPriceRuntime runtimeMarkPrice(String instrumentId) {
        // 从外部标的解析出的内部身份，未知标的可为空
        Integer symbolId = identities.findSymbolId(instrumentId);
        return symbolId == null ? null : runtimeState.markPrice(symbolId);
    }
    /**
     * 查询指定标的已记录的资金费结算进度值，未知标的返回零。
     * @param instrumentId 对外标的 ID，按本产品线身份表解析
     */
    long runtimeFundingSettlement(String instrumentId) {
        // 从外部标的解析出的内部身份，未知标的可为空
        Integer symbolId = identities.findSymbolId(instrumentId);
        return symbolId == null ? 0 : runtimeState.treasury().fundingSettlement(symbolId);
    }
    /**
     * 在必要的账户读取边界内查强平状态，并在返回前释放本方法取得的访问权；返回值是借出的运行时引用，不延长调用方的读取权限。
     * @param liquidationId 需要读取的强平实体 ID
     */
    LiquidationRuntime runtimeLiquidation(long liquidationId) {
        // 当前是否需要由本方法取得账户访问边界；它不表示访问已经取得
        boolean acquired = acquireOwnerLaneAccess("runtimeLiquidation");
        try {
            return runtimeState.liquidation(liquidationId);
        } finally {
            if (acquired) {
                runtimeState.releaseOwnerLaneAccess();
            }
        }
    }
    /**
     * 检查当前命令事实、直接控制槽和撮合队列是否有在途业务；不把盘口或实时读取未来结果计入其中。
     */
    public boolean snapshotHasPendingCommands() {
        return factContextActive || directCommand.active() || pendingMatching.inFlight() != 0;
    }
    /**
     * 取得费用政策快照，供快照和哈希边界使用。
     */
    public Map<Long, com.surprising.aeron.model.CoreFeePolicyState> feePolicies() {
        return runtimeState.feePoliciesSnapshot();
    }
    /**
     * 在必要的账户访问边界内取得运行时提供的待完成划转快照；等待最多五秒并在退出时归还访问边界。
     */
    public Map<Long, com.surprising.aeron.protocol.TransferFundsCommand> pendingTransfers() {
        // 当前是否需要由本方法取得账户访问边界；它不表示访问已经取得
        boolean acquired = acquireOwnerLaneAccess("pendingTransfers");
        try {
            return runtimeState.pendingTransfersSnapshot();
        } finally {
            if (acquired) runtimeState.releaseOwnerLaneAccess();
        }
    }

    /**
     * 取得近期收据表的只读浅层视图；其中响应仍遵守缓冲池租约，不是可永久持有的深复制。
     */
    public Map<UUID, StoredResult> commandResults() {
        return resultLedger.entries();
    }

    /**
     * 复制来源序号表并返回只读映射，避免调用方直接修改 Owner 表。
     */
    public Map<SourceKey, Long> lastSourceSequences() {
        return Collections.unmodifiableMap(lastSourceSequences.snapshot());
    }



    /**
     * 把当前线程绑定为 Owner；已有其他 Owner 时拒绝，再绑定账户运行时并检查身份表所有权。
     */
    void bindOwner() {
        // 尝试绑定本运行时的当前线程
        Thread current = Thread.currentThread();
        if (owner != null && owner != current) {
            throw new IllegalStateException("trading runtime is bound to another thread");
        }
        owner = current;
        runtimeState.bindOwner();
        identities.assertOwner();
    }

    /**
     * 首次访问时绑定当前线程，已经绑定时要求调用线程一致。
     */
    public void assertOwner() {
        if (owner == null) bindOwner();
        else if (owner != Thread.currentThread()) {
            throw new IllegalStateException("trading runtime is bound to another thread");
        }
    }

    /**
     * 借用准入事件，按当前运行时业务时间解析下单规则并确定结算状态与资产，再派发账户准入；准备阶段失败时归还事件，派发后的生命周期由账户路径承担。
     * @param userId 本次业务所属用户身份
     * @param command 本次业务使用的已解码命令或命令槽，沿用当前命令生命周期
     * @param commandId 本次命令 UUID，只有标明可空的查询路径允许为空
     * @param coreSequence 本次准备或快照固定的 Core 序号
     * @param timestamp 本次请求原复制日志业务时间
     * @param position 本次请求原复制日志位置
     */
    public com.surprising.aeron.service.order.admission.PlaceAdmissionEvent dispatchPlaceAdmission(
            long userId, PlaceOrderCommand command, UUID commandId, long coreSequence,
            long timestamp, long position) {
        // 从账户准入池借出的事件，准备失败时必须归还
        var event = runtimeState.acquirePlaceAdmission();
        // 准入需要观察的标的生命周期结算状态
        com.surprising.aeron.service.treasury.TreasuryRuntime.SettlementStatus settlementStatus;
        // 本次下单需要预留的结算资产身份
        int assetId;
        try {
            RuntimeOrderAdmission.prepare(event, runtimeState, identities, userId,
                    command, currentClusterTimestamp);
            settlementStatus = runtimeState.treasury().settlementStatus(event.symbolId());
            // 当前业务查到的标的配置，用于身份、结算规则或路由判断
            var instrument = event.instrument();
            assetId = identities.assetId(productLine.isDerivative() ? instrument.settleAsset()
                    : command.side() == com.surprising.aeron.protocol.CoreOrderSide.BUY
                    ? instrument.quoteAsset() : instrument.baseAsset());
        } catch (/* 准入准备失败，归还事件槽后继续抛出原异常 */ RuntimeException | Error failure) {
            runtimeState.discardPlaceAdmission(event);
            throw failure;
        }
        return runtimeState.dispatchPlaceAdmission(coreSequence, userId, event, commandId,
                settlementStatus,
                assetId, identities, timestamp, position);
    }



    /**
     * 取得在途命令必须具备的准入结果，缺失视为流程错误。
     * @param pending 当前借用的在途命令槽，完成或释放后不能继续使用
     */
    public ResolvedMatchingAdmission requireMatchingAdmission(CommandSlot pending) {
        // 在途命令必须具备的下单或改单准入结果
        ResolvedMatchingAdmission admission = pending.matchingAdmission();
        if (admission == null) throw new IllegalStateException("replace admission is missing");
        return admission;
    }

    /**
     * 先查本命令变化缓冲，包括已删除的空值，再查账户运行时中的订单。
     * @param orderId 需要查询或检查占用的订单实体 ID
     */
    public OrderRuntime runtimeOrder(long orderId) {
        return runtimeState.hasChangedOrder(orderId) ? runtimeState.changedOrderValue(orderId)
                : runtimeState.order(orderId);
    }

    /**
     * 查询标的生命周期进度，未知标的返回空。
     * @param instrumentId 对外标的 ID，按本产品线身份表解析
     */
    public com.surprising.aeron.service.treasury.TreasuryRuntime.LifecycleProgressRuntime
            lifecycleProgress(String instrumentId) {
        // 从外部标的解析出的内部身份，未知标的可为空
        Integer symbolId = identities.findSymbolId(instrumentId);
        return symbolId == null ? null : runtimeState.treasury().lifecycleProgress(symbolId);
    }

    /**
     * 把本次变化订单的序号、时间和日志位置交给所属 Lane 盖章；确有变化时要求发布。
     * @param timestamp 本次请求原复制日志业务时间
     * @param clusterPosition 本命令原复制日志位置
     * @param changedOrderIds 本次实际变化的订单身份列表
     */
    public void stampOrderChangesRuntime(long timestamp, long clusterPosition,
                                          Iterable<Long> changedOrderIds) {
        if (runtimeState.stampChangedOrders(
                timestamp, clusterPosition,
                changedOrderIds, resultBuilder.commandChangedUserIds)) {
            commits.publication.request();
        }
    }

    /**
     * 仅挂起具有有效续步的撮合提交：把原始变化缓冲、资金累加器及发布标记交给序号槽，清空当前公开变化视图并结束事实作用域，等待按序恢复。
     * @param pending 当前借用的在途命令槽，完成或释放后不能继续使用
     */
    public void suspendMatchingCommitContext(CommandSlot pending) {
        if (!commits.publication.deferred() || pending == null
                || pending.settlementEvent() == null
                && pending.orderBatch() == null
                && !commits.controlPending(pending.sequence())) {
            throw new IllegalStateException("matching commit context cannot be suspended");
        }
        // 续步恢复之前由序号槽持有原始变化缓冲。
        // 直接移交这些缓冲，避免挂起时装箱成列表、恢复时再复制回来。
        seedChangeAccumulators();
        runtimeState.acceptChangedUserIds(resultBuilder.changedUserIds()::add);
        // 保存在 Core 序号槽中的变化缓冲与提交发布续步上下文
        CommandSlot context = pending;
        context.suspendCommitContext(
                resultBuilder,
                commits.publication.dirty(), commits.publication.provisionalOnly());
        commits.publication.clearBatchState();
        resultBuilder.commandChangedUserIds = List.of();
        resultBuilder.commandChangedOrderIds = List.of();
        resultBuilder.resetChangeAccumulators();
        clearFactContext();
    }

    /**
     * 确认没有其他活动事实或发布作用域后，取回该序号槽保存的变化缓冲与发布上下文，并重置本轮成交数及相关结果字段。
     * @param pending 当前借用的在途命令槽，完成或释放后不能继续使用
     */
    public void restoreMatchingCommitContext(CommandSlot pending) {
        if (commits.publication.deferred() || factContextActive) {
            throw new IllegalStateException("another owner commit context is active");
        }
        // 保存在 Core 序号槽中的变化缓冲与提交发布续步上下文
        CommandSlot context = pending;
        activateFactContext(pending.command(), pending.fingerprint());
        commits.publication.restore(context.commitSnapshotDirty(), context.commitSnapshotProvisionalOnly());
        context.restoreCommitContext(resultBuilder);
        resultBuilder.commandChangedUserIds = List.of();
        resultBuilder.commandChangedOrderIds = List.of();
        context.clearCommitContext();
        resultBuilder.commandTradeCount(0);
        resultBuilder.commandLiquidationProgress = null;
        resultBuilder.commandLiquidationBatchResult = null;
        resultBuilder.commandRiskScanControl = null;
    }

    /**
     * 检查下单身份不为空，且订单号或同用户客户号未命中保留终态；活动身份的其他校验仍由准入负责。
     * @param userId 本次业务所属用户身份
     * @param command 本次业务使用的已解码命令或命令槽，沿用当前命令生命周期
     */
    public void requireOrderIdentityAvailable(long userId, PlaceOrderCommand command) {
        if (command == null || terminalRetention.containsOrder(command.orderId(), userId, command.clientOrderId())) {
            throw new CoreStateRejectedException("DUPLICATE_ORDER_ID", "terminal order identity is retained");
        }
    }

    /**
     * 通过 Owner 活动路由、本命令变化订单及终态保留判断订单号是否被占用，不在这里扫描全部账户。
     * @param orderId 需要查询或检查占用的订单实体 ID
     */
    public boolean orderIdInUse(long orderId) {
        return factIndexes.activeOrders().activeOrderRoute(orderId) != null
                || runtimeState.changedOrderValue(orderId) != null || terminalRetention.orderIdInUse(orderId);
    }

    /**
     * 中止当前发布，按检查点回滚账户命令及持仓身份注册；此入口不回滚已经执行的 Matcher 状态。
     * @param runtimeCheckpoint 需要恢复到账户命令开始前的检查点
     * @param positionIdentityCheckpoint 本命令开始前的持仓身份注册检查点
     * @param commandSequence 需要回滚的命令 Core 序号
     */
    public void rollbackCommandState(
            long runtimeCheckpoint,
            long positionIdentityCheckpoint,
            long commandSequence) {
        commits.publication.clearBatchState();
        runtimeState.rollbackActiveCommand(runtimeCheckpoint, commandSequence);
        identities.rollbackPositionKeys(positionIdentityCheckpoint);
    }

    /**
     * 校验触发子单与原用户一致，派生稳定的内部命令身份，编码触发信息并排入衍生撮合队列；真正派发与提交由后续路径完成。
     * @param trigger 本次业务关联的原触发单
     * @param triggerSequence 本次触发派生使用的序号信息
     * @param triggeredPriceTicks 导致触发的整数价格
     * @param triggeredAtEpochMillis 原触发业务时间，单位毫秒
     * @param parentCommandId 父命令身份，空时使用零值参与稳定派生
     * @param childOrder 由触发单生成且必须属于同一用户的子单
     */
    public void queueTriggerMatching(com.surprising.aeron.model.CoreTriggerOrderState trigger,
                                      long triggerSequence, long triggeredPriceTicks,
                                      long triggeredAtEpochMillis, UUID parentCommandId, OrderRuntime childOrder) {
        if (childOrder == null || childOrder.userId() != trigger.userId())
            throw new IllegalStateException("trigger child admission receipt is missing");
        // 父命令 UUID 高位，父身份为空时用零
        long parentMsb = parentCommandId == null ? 0L : parentCommandId.getMostSignificantBits();
        // 父命令 UUID 低位，父身份为空时用零
        long parentLsb = parentCommandId == null ? 0L : parentCommandId.getLeastSignificantBits();
        // 父 UUID 高位与固定触发执行标签混合出的子命令高位
        long commandIdMsb = parentMsb ^ 0x545249474745524cL; // 内部触发执行命令的固定身份标签
        // 父 UUID 低位与触发单身份混合出的子命令低位
        long commandIdLsb = parentLsb ^ trigger.triggerOrderId();
        // 内部触发执行命令头，保留原用户和触发业务时间
        CoreMessageHeader header = CoreMessageHeader.command(CoreMessageType.EXECUTE_TRIGGER_ORDER, commandIdMsb, commandIdLsb,
                productLine, com.surprising.aeron.protocol.CommandSource.OPERATIONS, trigger.userId(), 0,
                trigger.userId(), triggeredAtEpochMillis, 0);
        admissions.borrowQueuedMatching(
                new CoreMessage(header, com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeExecute(
                        trigger.triggerOrderId(), triggerSequence, triggeredPriceTicks, triggeredAtEpochMillis)),
                childOrder, trigger);
    }

    /**
     * 把公开变化列表中的现有身份补入原始累加器，供挂起与恢复复用，不在此清空原列表。
     */
    public void seedChangeAccumulators() {
        if (resultBuilder.commandChangedUserIds != null) resultBuilder.changedUserIds().addAll(resultBuilder.commandChangedUserIds);
        if (resultBuilder.commandChangedOrderIds != null) resultBuilder.changedOrderIds().addAll(resultBuilder.commandChangedOrderIds);
    }


    /**
     * 返回运行时记录的待处理风险扫描数量。
     */
    public int pendingRiskScanCount() {
        return runtimeState.incompleteRiskScanCount();
    }

    /**
     * 计算本轮耗时及剩余风险扫描数，在 debug 开启时输出诊断；耗时使用单调时钟，不参与业务时间判断。
     * @param operation 产生诊断的业务操作名称
     * @param instrumentId 对外标的 ID，按本产品线身份表解析
     * @param batchSize 本轮风险扫描的工作预算
     * @param pendingBefore 开始本轮之前的待扫描数量
     * @param startedAt 本轮扫描开始时的单调纳秒时间
     */
    public void logRiskScan(String operation, String instrumentId, int batchSize, int pendingBefore, long startedAt) {
        // 本轮扫描由单调时钟测出的微秒耗时
        long elapsedMicros = (System.nanoTime() - startedAt) / 1_000L;
        // 当前剩余风险扫描数，在判断 debug 日志开关之前已经读取
        int pendingAfter = pendingRiskScanCount();
        if (!log.isDebugEnabled()) return;
        log.debug("risk scan operation={} instrumentId={} batchSize={} elapsedMicros={} "
                        + "pendingSymbolsBefore={} pendingSymbolsAfter={}",
                operation, instrumentId, batchSize, elapsedMicros, pendingBefore, pendingAfter);
    }

    /** 逐项停止活动资源，首异常继续传播；清理失败不能跳过账户和账本线程。 */
    @Override
    public void close() {
        if (closed) return;
        if (activated) assertOwner();
        closed = true;
        Throwable failure = null;
        try { snapshots.releaseSnapshotFence(); }
        catch (RuntimeException | Error problem) { failure = problem; }
        snapshots.inFlightMatcherSnapshot().set(null);
        try { matcherPipeline.closeShards(matchingAdapter::closeShard); }
        catch (RuntimeException | Error problem) { failure = shutdownFailure(failure, problem); }
        try { runtimeState.endOrderBatchMutationScope(); }
        catch (RuntimeException | Error problem) { failure = shutdownFailure(failure, problem); }
        try { commits.publication.close(); }
        catch (RuntimeException | Error problem) { failure = shutdownFailure(failure, problem); }
        try { runtimeState.close(); }
        catch (RuntimeException | Error problem) { failure = shutdownFailure(failure, problem); }
        try { terminalRetention.assertHealthy(); }
        catch (RuntimeException | Error problem) {
            failure = shutdownFailure(failure, new IllegalStateException("Core workers stopped with a failure", problem));
        }
        // 存活的消费者可能仍借用槽和索引；未确认全部线程退出时保留其输入引用。
        if (matcherPipeline.workersStopped() && runtimeState.workersStopped()) {
            try {
                bookQueries.clearPendingQueries();
                clearFactContext();
                directCommand.clear();
                batches.clearPendingBatches();
                pendingMatching.clear();
                crossShardCancellations.clear();
                admissions.clearLifecycleScopes();
            } catch (RuntimeException | Error problem) { failure = shutdownFailure(failure, problem); }
            try { terminalRetention.close(); }
            catch (RuntimeException | Error problem) { failure = shutdownFailure(failure, problem); }
        }
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) throw runtime;
    }

    private static Throwable shutdownFailure(Throwable first, Throwable next) {
        if (first == null) return next;
        if (first != next) first.addSuppressed(next);
        return first;
    }

    /**
     * 先保存成交计划与 taker 引用，再清在途实时引用；仅捕获 TRADE 事件，实时捕获异常只计故障，不回滚交易。
     * @param pending 当前借用的在途命令槽，完成或释放后不能继续使用
     */
    public void captureRealtimeTrades(CommandSlot pending) {
        // 清在途引用之前保存的本次结算计划
        var plan = pending.settlementPlan();
        // 在途命令此前保存的可空 taker 订单引用
        OrderRuntime taker = pending.realtimeTakerOrder();
        // 此前保存的已解析 taker 信息，存在时优先用于实时成交捕获
        ResolvedPlaceOrder resolvedTaker = pending.realtimeResolvedTakerOrder();
        pending.realtimeTakerOrder(null);
        pending.realtimeResolvedTakerOrder(null);
        if (realtimeCapture == null || !realtimeCapture.active()) return;
        try {
            for (/* 当前数组或事件列表的遍历下标 */ int index = 0; index < plan.matcherEventCount(); index++) {
                // 计划中的当前撮合事件，只有 TRADE 事件计入成交捕获
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
        } catch (/* 本分支捕获的失败，按当前边界记录、延后拒绝或继续传播 */ RuntimeException failure) { realtimeCapture.failed(); }
    }

    /**
     * 统计结算计划中的 TRADE 事件条数，不以订单数或成交数量代替。
     * @param plan 借用的结算计划，按已有订单及成交事件视图读取
     */
    public static long tradeCount(com.surprising.aeron.service.settlement.MatcherSettlementPlan plan) {
        // 当前已经统计出的 TRADE 事件条数
        long trades = 0;
        for (/* 当前数组或事件列表的遍历下标 */ int index = 0; index < plan.matcherEventCount(); index++)
            if (plan.matcherEvent(index).eventType() == MatcherEventType.TRADE) trades++;
        return trades;
    }

    /**
     * 以基础状态哈希为起点，依次混入非空费用政策和待完成划转摘要；调用方仍须提供稳定的映射遍历顺序。
     * @param base 基础业务状态摘要
     * @param policies 待混入摘要的费用政策映射，调用方保证稳定遍历顺序
     * @param transfers 待混入摘要的未完成划转映射，调用方保证稳定遍历顺序
     */
    public static long canonicalBusinessStateHash(
            long base,
            Map<Long, com.surprising.aeron.model.CoreFeePolicyState> policies,
            Map<Long, com.surprising.aeron.protocol.TransferFundsCommand> transfers) {
        // 费用政策映射的摘要，零表示无需混入该部分
        long feePolicyHash = computeFeePolicyHash(policies);
        if (feePolicyHash != 0) base = mix(base, feePolicyHash);
        // 未完成划转映射的摘要，零表示无需混入该部分
        long transferHash = computeTransferHash(transfers);
        return transferHash == 0 ? base : mix(base, transferHash);
    }

    /**
     * 按提供映射的值遍历顺序混入待完成划转的业务字段；空映射返回零，本方法不重新排序。
     * @param transfers 待混入摘要的未完成划转映射，调用方保证稳定遍历顺序
     */
    static long computeTransferHash(
            Map<Long, com.surprising.aeron.protocol.TransferFundsCommand> transfers) {
        if (transfers.isEmpty()) return 0;
        // 当前摘要累加值，按本方法规定的顺序混合或异或
        long digest = HASH_OFFSET_BASIS;
        for (var /* 按调用方映射顺序遍历的原始未完成划转命令 */ command : transfers.values()) {
            digest = mix(digest, command.transferId());
            digest = mix(digest, command.sourceUserId());
            digest = mix(digest, command.targetUserId());
            digest = mix(digest, com.surprising.aeron.service.snapshot.state.SnapshotEnumCodes.encode(command.sourceProductLine()));
            digest = mix(digest, com.surprising.aeron.service.snapshot.state.SnapshotEnumCodes.encode(command.targetProductLine()));
            digest = mixText(digest, command.sourceAccountType());
            digest = mixText(digest, command.targetAccountType());
            digest = mixText(digest, command.asset());
            digest = mix(digest, command.amountUnits());
            digest = mixText(digest, command.referenceId());
            digest = mixText(digest, command.reason());
        }
        return digest;
    }

    /**
     * 列举需要长期资金身份去重的余额调整、转出、转入和完成划转命令。
     * @param type 要分类的协议消息类型
     */
    static boolean isFundsIdempotencyCommand(CoreMessageType type) {
        return type == CoreMessageType.ADJUST_BALANCE || type == CoreMessageType.TRANSFER_OUT
                || type == CoreMessageType.TRANSFER_IN || type == CoreMessageType.COMPLETE_TRANSFER;
    }

    /**
     * 按提供映射的值遍历顺序混入费用政策字段；空映射返回零，本方法不重新排序。
     * @param policies 待混入摘要的费用政策映射，调用方保证稳定遍历顺序
     */
    static long computeFeePolicyHash(
            Map<Long, com.surprising.aeron.model.CoreFeePolicyState> policies) {
        if (policies.isEmpty()) return 0;
        // 当前摘要累加值，按本方法规定的顺序混合或异或
        long digest = HASH_OFFSET_BASIS;
        for (var /* 按调用方映射顺序遍历到的一条费用政策 */ policy : policies.values()) {
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

    /**
     * 按 UTF-8 字节规则把文本混入摘要，直接遍历字符而不先创建字节数组；先混入字节长度，合法代理对按四字节处理，孤立代理项按问号处理。
     * @param hash 混入当前值之前的摘要
     * @param value 要按 UTF-8 规则混入的可空文本
     */
    private static long mixText(long hash, String value) {
        if (value == null || value.isEmpty()) return mix(hash, 0);
        // 当前文本的 UTF-16 字符单元数量，不等于 UTF-8 字节数
        int length = value.length();
        // 是否所有字符都可直接编码为一个 UTF-8 字节
        boolean isAscii = true;
        for (/* 当前 UTF-16 字符下标 */ int i = 0; i < length; i++) {
            if (value.charAt(i) >= 0x80) {
                isAscii = false;
                break;
            }
        }
        if (isAscii) {
            // 逐字节混合后的当前文本或整数摘要
            long result = mix(hash, length);
            for (/* 当前 UTF-16 字符下标 */ int i = 0; i < length; i++) {
                result ^= value.charAt(i);
                result *= HASH_PRIME;
            }
            return result;
        }
        // 按既有编码规则计算的 UTF-8 字节长度，先混入摘要
        int utf8Len = 0;
        for (/* 当前 UTF-16 字符下标 */ int i = 0; i < length; i++) {
            // 当前 UTF-16 字符单元，用于选择 UTF-8 编码分支
            char c = value.charAt(i);
            if (c < 0x80) utf8Len++;
            else if (c < 0x800) utf8Len += 2;
            else if (Character.isHighSurrogate(c) && i + 1 < length && Character.isLowSurrogate(value.charAt(i + 1))) {
                utf8Len += 4;
                i++;
            } else if (!Character.isSurrogate(c)) utf8Len += 3;
            else utf8Len += 1;
        }
        // 逐字节混合后的当前文本或整数摘要
        long result = mix(hash, utf8Len);
        for (/* 当前 UTF-16 字符下标 */ int i = 0; i < length; i++) {
            // 当前 UTF-16 字符单元，用于选择 UTF-8 编码分支
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
                // 合法高低代理对组合出的 Unicode 码点
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

    /**
     * 按从低到高的八个字节混入长整数；自然溢出属于哈希算法，与资金算术无关。
     * @param hash 混入当前值之前的摘要
     * @param value 要按低字节优先规则混入的长整数
     */
    public static long mix(long hash, long value) {
        // 逐字节混合后的当前文本或整数摘要
        long result = hash;
        for (/* 按八位递增的移位量，使长整数从低字节向高字节混入 */ int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
            result ^= (value >>> shift) & 0xff;
            result *= HASH_PRIME;
        }
        return result;
    }

    /**
     * 将每个来源键及其序号的摘要异或合并，因此不依赖来源表遍历顺序；空表返回零。
     * @param sequences 要计算摘要的来源序号表
     */
    public static long sourceSequenceDigest(Map<SourceKey, Long> sequences) {
        // 当前摘要累加值，按本方法规定的顺序混合或异或
        long digest = 0;
        for (Map.Entry<SourceKey, Long> /* 来源序号表当前键值项，单项摘要随后参与异或 */ entry : sequences.entrySet()) {
            digest ^= sourceSequenceDigest(entry.getKey(), entry.getValue());
        }
        return digest;
    }

    /**
     * 对来源类型、来源身份及该来源序号计算单项摘要。
     * @param key 来源类型与来源身份组成的键
     * @param sequence 本次业务对应的 Core 序号
     */
    public static long sourceSequenceDigest(SourceKey key, long sequence) {
        // 当前摘要累加值，按本方法规定的顺序混合或异或
        long digest = HASH_OFFSET_BASIS;
        digest = mix(digest, key.source().wireCode());
        digest = mix(digest, key.sourceId());
        return mix(digest, sequence);
    }

    /**
     * 使用当前已受理计数生成业务拒绝响应，不在此推进命令水位。
     * @param resultCode 与当前业务状态配套的协议结果码
     */
    public CoreResponse rejected(CoreResultCode resultCode) {
        return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                resultCode, appliedCommandCount);
    }

    /**
     * 当前批量命令不适用独立窗口的内部信号，避免为正常分流创建栈轨迹。
     */
    public static final class PipelinedBatchNotApplicable extends RuntimeException {
        /**
         * 构造轻量窗口不适用信号，不生成栈轨迹或启用 suppressed 异常。
         */
        public PipelinedBatchNotApplicable() {
            super(null, null, false, false);
        }
    }

    /**
     * 来源类型与来源身份组成的序号跟踪键；构造时不另加合法性校验。
     * @param source 该来源的协议类型
     * @param sourceId 该类型下的来源身份
     */
    public record SourceKey(CommandSource source, long sourceId) {
    }

    /**
     * 使用默认快照身份取得完整快照字节，由快照生命周期负责等待和编码。
     */
    public byte[] snapshot() { return snapshots.snapshot(); }
    /**
     * 使用指定快照身份取得完整快照字节。
     * @param snapshotId 本次快照身份，交给既有捕获和编码流程
     */
    public byte[] snapshot(long snapshotId) { return snapshots.snapshot(snapshotId); }

    /**
     * 把该 Core 序号的撮合结果和原日志时间、位置交给有序提交处理。
     * @param sequence 本次业务对应的 Core 序号
     * @param matchingResult 对应 Core 序号的确定性撮合结果
     * @param clusterTimestamp 本命令原复制日志业务时间
     * @param clusterPosition 本命令原复制日志位置
     */
    public CoreResponse completeMatching(long sequence,
                                  com.surprising.aeron.service.matcher.MatchingResult matchingResult,
                                  long clusterTimestamp, long clusterPosition) { return commits.completeMatching(sequence, matchingResult, clusterTimestamp, clusterPosition); }

    /** Owner 上的跨 Matcher 分片撤单推进器：逐项派发、等待证据并保留原顺序，完整结果再交给已有结算路径。 构造约定：固定所属运行时；后续队列仅由其 Owner 推进。 参数 owner：拥有本跨分片撤单推进器的运行时 */
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    public static final class CrossShardCancellationCoordinator {
        /** 拥有此撤单推进器的运行时，所有队列推进遵守其 Owner 约束。 */
        private final TradingCoreRuntime owner;
        /** 按登记顺序等待派发与回执的跨分片撤单工作。 */
        private final java.util.ArrayDeque<Progress> pending = new java.util.ArrayDeque<>();

        /**
         * 同一命令只登记一次跨分片撤单，保存其稳定撤单描述并立即尝试推进。
         * @param command 本次跨分片撤单所属的命令槽
         * @param orders 本次按顺序借用的撤单描述，调用方保持列表稳定
         */
        public void start(CommandSlot command, java.util.List<com.surprising.aeron.service.matcher.DeterministicExchangeCoreAdapter.CancellationOrder> orders) {
            if (command.crossShardCancellationStarted()) return;
            command.crossShardCancellationStarted(true);
            pending.addLast(new Progress(command, orders));
            poll();
        }

        /**
         * 每轮最多推进 64 次队首操作，逐项向对应 Matcher 提交撤单并收集证据；首次拒绝后剩余项记为未提交，仍保留此前成功撤单，最后发布完整结果供后续结算。这里不生成业务终态。
         */
        public void poll() {
            for (/* 本轮跨分片撤单推进次数，最多 64 次 */ int work = 0; work < 64 && !pending.isEmpty(); work++) {
                // 当前队首撤单工作，后续命令不越过它推进
                Progress progress = pending.peekFirst();
                if (progress.token != 0) {
                    // 当前 Matcher 控制任务的可空撤单证据，未完成时返回等待
                    var result = (com.surprising.aeron.service.matcher.CoreMatchingResult) owner.matcherPipeline().pollControl(progress.shard, progress.token);
                    if (result == null) return;
                    progress.results.add(result);
                    progress.failed = !result.accepted();
                    progress.token = 0;
                    progress.index++;
                }
                if (progress.index < progress.orders.size()) {
                    if (progress.failed) {
                        progress.results.add(new com.surprising.aeron.service.matcher.CoreMatchingResult(false, "NOT_SUBMITTED"));
                        progress.index++;
                        continue;
                    }
                    // 当前准备派发或标记未提交的撤单描述
                    var order = progress.orders.get(progress.index);
                    progress.shard = owner.matchingAdapter.matcherShardId(order.instrumentId());
                    progress.token = owner.matcherPipeline().submitControl(progress.shard,
                            () -> owner.matchingAdapter.cancelControlOrder(progress.command.command().header().submittedAtEpochMillis(),
                                    order.userId(), order.orderId(), order.instrumentId()));
                    continue;
                }
                // 已经收齐本轮撤单证据的命令槽
                var command = progress.command;
                // 适配器根据完整撤单描述和证据构造的撮合完成结果
                var evidenced = owner.matchingAdapter.completeControlCancellations(
                        command.sequence(), command.command().header().commandId(),
                        command.command().header().submittedAtEpochMillis(), progress.orders, progress.results);
                pending.removeFirst();
                command.crossShardCancellationStarted(false);
                owner.matchingFlow.publishMatchingCompletion(command.sequence(), evidenced);
                command.matchingSubmitted();
                owner.matchingProgress.submissionCompleted(command);
            }
        }

        /**
         * 只清本地等待队列，依赖关闭顺序保证工作线程已结束；不在此撤销已派发任务。
         */
        void clear() { pending.clear(); }

        /**
         * 判断是否还有跨分片撤单编排等待推进。
         */
        public boolean hasPending() { return !pending.isEmpty(); }

        /**
         * 一条跨分片撤单的推进状态，保存稳定订单描述、对应结果及当前控制任务身份；只由 Owner 修改。
         */
        private static final class Progress {
            /** 本轮跨分片撤单所属命令槽，完成前保持引用有效。 */
            final CommandSlot command;
            /** 本轮按顺序借用的稳定撤单描述，列表生命周期由命令保证。 */
            final java.util.List<com.surprising.aeron.service.matcher.DeterministicExchangeCoreAdapter.CancellationOrder> orders;
            /** 与已处理撤单描述对应的证据列表，后续结算继续使用。 */
            final java.util.ArrayList<com.surprising.aeron.service.matcher.CoreMatchingResult> results;
            /**
             * index：下一个待处理的撤单描述下标。
             * shard：当前控制任务所在的 Matcher 分片。
             */
            int index, shard;
            /** 当前控制任务身份，零表示没有等待中的控制任务。 */
            long token;
            /** 是否已经出现撤单拒绝；为真时剩余描述不再派发。 */
            boolean failed;
            /**
             * 保留本命令及稳定撤单描述，建立同顺序的结果列表，供逐项串行推进。
             * @param command 本次跨分片撤单所属的命令槽
             * @param orders 本次按顺序借用的撤单描述，调用方保持列表稳定
             */
            Progress(CommandSlot command, java.util.List<com.surprising.aeron.service.matcher.DeterministicExchangeCoreAdapter.CancellationOrder> orders) {
                this.command = command; this.orders = orders;
                results = new java.util.ArrayList<>(orders.size());
            }
        }
    }

    /**
     * 按撮合命令类型准备普通下单、触发子单、撤单或改单结算事件；其他类型返回空，交给其专门路径处理。
     * @param pending 当前借用的在途命令槽，完成或释放后不能继续使用
     */
    public com.surprising.aeron.service.settlement.MatcherSettlementEvent prepareForMatching(CommandSlot pending) {
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

    /**
     * 要求下单准入已完成，用初始用户 Lane、原提交栅栏和预撤单身份准备结算事件，并保留 Matcher 发布租约。
     * @param pending 当前借用的在途命令槽，完成或释放后不能继续使用
     */
    private com.surprising.aeron.service.settlement.MatcherSettlementEvent preparePlace(CommandSlot pending) {
        // 在途命令必须具备的下单或改单准入结果
        var admission = java.util.Objects.requireNonNull(pending.placeAdmission(), "place admission receipt");
        // 为本命令准备的直接结算事件，随后保存到在途槽并保留发布租约
        com.surprising.aeron.service.settlement.MatcherSettlementEvent direct = runtimeState.prepareDirectMatcherSettlement(
                pending.sequence(), directInitialLaneMask(admission.userId()), admission,
                pending.command().header().commandId(), matchingFlow.matcherShard(pending),
                identities, pending.commitFenceTimestamp(), pending.commitFenceClusterPosition(),
                pending.preMatchingCancellationOrderIds(), pending.laneResultTarget());
        reserveMatcherPublication(pending, direct);
        return direct;
    }

    /**
     * 要求改单准入已完成，按原提交栅栏准备替换结算及发布租约；启用实时捕获时保存准入订单引用。
     * @param pending 当前借用的在途命令槽，完成或释放后不能继续使用
     */
    private com.surprising.aeron.service.settlement.MatcherSettlementEvent prepareReplacement(CommandSlot pending) {
        // 在途命令必须具备的下单或改单准入结果
        var admission = requireMatchingAdmission(pending);
        // 为本命令准备的直接结算事件，随后保存到在途槽并保留发布租约
        com.surprising.aeron.service.settlement.MatcherSettlementEvent direct = runtimeState.prepareDirectReplacement(
                pending.sequence(), pending.sequence(), directInitialLaneMask(admission.userId()), admission,
                pending.command().header().commandId(), matchingFlow.matcherShard(pending), identities,
                pending.commitFenceTimestamp(), pending.commitFenceClusterPosition(),
                pending.preMatchingCancellationOrderIds(), pending.laneResultTarget());
        reserveMatcherPublication(pending, direct);
        if (realtimeCapture != null) pending.realtimeTakerOrder(direct.admittedOrder());
        return direct;
    }

    /**
     * 解码触发执行字段，要求原触发单及子单收据存在，准备子单结算并登记触发完成信息，再保留发布租约及可选实时引用。
     * @param pending 当前借用的在途命令槽，完成或释放后不能继续使用
     */
    private com.surprising.aeron.service.settlement.MatcherSettlementEvent prepareTrigger(CommandSlot pending) {
        // 触发执行载荷的四个长整数，依次携带触发身份、序号、价格与业务时间
        long[] execute = pending.decodedCommand().trigger();
        // 在途命令保存的原触发单，缺失表示内部流程错误
        var trigger = java.util.Objects.requireNonNull(pending.sourceTrigger(),
                "admitted trigger is missing");
        // 触发准入已经产生的子单收据，准备直接结算必须存在
        com.surprising.aeron.service.order.state.OrderRuntime triggerOrder = java.util.Objects.requireNonNull(pending.triggerAdmissionOrder(),
                "trigger admission receipt is missing");
        // 为本命令准备的直接结算事件，随后保存到在途槽并保留发布租约
        com.surprising.aeron.service.settlement.MatcherSettlementEvent direct = runtimeState.prepareDirectMatcherSettlement(pending.sequence(),
                directInitialLaneMask(triggerOrder.userId()), triggerOrder, null, 1,
                pending.command().header().commandId(), matchingFlow.matcherShard(pending), identities,
                pending.commitFenceTimestamp(), pending.commitFenceClusterPosition(),
                pending.preMatchingCancellationOrderIds(), pending.laneResultTarget());
        direct.triggerCompletion(trigger, execute[3]);
        reserveMatcherPublication(pending, direct);
        if (realtimeCapture != null) pending.realtimeTakerOrder(triggerOrder);
        return direct;
    }

    /**
     * 按用户、订单身份、原提交栅栏及结果槽准备直接撤单结算事件，并保留 Matcher 发布租约。
     * @param pending 当前借用的在途命令槽，完成或释放后不能继续使用
     */
    private com.surprising.aeron.service.settlement.MatcherSettlementEvent prepareCancellation(CommandSlot pending) {
        // 为本命令准备的直接结算事件，随后保存到在途槽并保留发布租约
        com.surprising.aeron.service.settlement.MatcherSettlementEvent direct = runtimeState.prepareDirectCancellation(pending.sequence(),
                pending.command().header().userId(), pending.decodedCommand().cancelOrder().orderId(),
                pending.command().header().commandId(), matchingFlow.matcherShard(pending), identities,
                pending.commitFenceTimestamp(), pending.commitFenceClusterPosition(), pending.laneResultTarget());
        reserveMatcherPublication(pending, direct);
        return direct;
    }

    /**
     * 把结算事件与计划登记到在途命令，并取得 Matcher 发布所需的生产者租约；这不表示账户资金已经结算。
     * @param pending 当前借用的在途命令槽，完成或释放后不能继续使用
     * @param direct 已经准备的直接结算事件，负责保留 Matcher 发布租约
     */
    private void reserveMatcherPublication(CommandSlot pending, com.surprising.aeron.service.settlement.MatcherSettlementEvent direct) {
        pending.settlement(direct, direct.plan());
        direct.reserveMatcherPublication();
    }

    /**
     * 要求正用户身份，并计算直接结算的初始用户 Lane 位；实际 maker Lane 随后续撮合事实加入。
     * @param userId 本次业务所属用户身份
     */
    private long directInitialLaneMask(long userId) {
        if (userId <= 0) throw new IllegalArgumentException("direct settlement user is required");
        return runtimeState.topology().accountLaneMask(userId);
    }

    /**
     * 借出来源序号索引，写入仍由 Owner 的有序命令完成路径负责。
     */
    public SourceSequenceIndex sourceSequenceIndex() { return lastSourceSequences; }
}
