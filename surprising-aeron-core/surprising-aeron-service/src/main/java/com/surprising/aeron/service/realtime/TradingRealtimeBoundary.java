package com.surprising.aeron.service.realtime;

import com.surprising.aeron.client.AeronRealtimeReceiver;
import com.surprising.aeron.client.AeronRealtimeSender;
import com.surprising.aeron.client.RealtimeOutbox;
import com.surprising.aeron.config.RealtimeConfiguration;
import com.surprising.aeron.model.CoreLeverageKey;
import com.surprising.aeron.model.CoreTriggerOrderState;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.protocol.RealtimeFrame;
import com.surprising.aeron.service.account.AccountPartition;
import com.surprising.aeron.service.account.UserAccount;
import com.surprising.aeron.service.core.TradingCore;
import com.surprising.aeron.service.core.identity.IdIndex;
import com.surprising.aeron.service.order.*;
import com.surprising.aeron.service.order.Order;
import com.surprising.aeron.service.order.OrderFundsReservation;
import com.surprising.aeron.service.position.Position;
import com.surprising.aeron.service.risk.*;
import com.surprising.aeron.service.risk.RiskScanner;
import com.surprising.aeron.service.risk.RiskMarginCalculator;
import com.surprising.product.api.ProductLine;
import io.aeron.cluster.service.Cluster;
import java.util.*;
import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

/** Owner 交易状态与实时传输之间的边界，持有 outbox、发送/接收资源、请求队列、角色及已提交位置。 */
public final class TradingRealtimeBoundary implements AutoCloseable {
    private final RealtimeConfiguration configuration;
    private static final int REQUEST_CAPACITY = 256;
    private static final long SNAPSHOT_INTERVAL_NANOS =
            java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(10);

    private final ProductLine productLine;
    private RealtimeOutbox outbox;
    private AeronRealtimeSender sender;
    private AeronRealtimeReceiver controlReceiver;
    /** 运行时当前使用的 Owner 编码捕获器，恢复时重新绑定。 */
    private TradingRealtimeBoundary.StateCapture capture;
    /** 控制接收线程等生产者交给 Owner 的有界原请求队列。借出原有界请求队列供现有调用边界投递，不能把直接入队当成已通过接收器协议筛选。 */
    private final ManyToOneConcurrentArrayQueue<RealtimeFrame> requests =
            new ManyToOneConcurrentArrayQueue<>(REQUEST_CAPACITY);
    private boolean leader;
    private long committedPosition;
    private long nextSnapshotNanos;

    /** 要求指定所属产品线，建立固定容量请求队列，此时不启动实时传输。 */
    public TradingRealtimeBoundary(ProductLine productLine) {
        this(productLine, RealtimeConfiguration.standalone());
    }

    public TradingRealtimeBoundary(ProductLine productLine, RealtimeConfiguration configuration) {
        this.configuration = java.util.Objects.requireNonNull(configuration);
        this.productLine = java.util.Objects.requireNonNull(productLine, "product line is required");
    }

    /** 先更新角色、非负初始日志位置并重置请求节流，再使用已绑定的实时通道配置。否则创建 outbox、接入状态捕获与发送器，控制通道非空时再创建请求接收器。 */
    public void start(Cluster.Role role, long logPosition, TradingCore state) {
        leader = role == Cluster.Role.LEADER;
        committedPosition = Math.max(0, logPosition);
        nextSnapshotNanos = 0;
        if (configuration.channel().isEmpty() || outbox != null) return;
        try {
            outbox = new RealtimeOutbox(8192, 8 * 1024 * 1024);
            capture = state.attachRealtime(outbox);
            sender = new AeronRealtimeSender(outbox, configuration.directory(),
                    configuration.channel(), configuration.stream());
            if (!configuration.controlChannel().isEmpty()) {
                controlReceiver = new AeronRealtimeReceiver(configuration.directory(), configuration.controlChannel(),
                        configuration.controlStream(), this::acceptRequest);
            }
        } catch (RuntimeException | Error failure) {
            try { close(); }
            catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private void acceptRequest(RealtimeFrame frame) {
        boolean snapshotRequest = frame.userId() > 0
                && frame.kind() == RealtimeFrame.Kind.SNAPSHOT_REQUEST;
        boolean bookRequest = frame.userId() == 0
                && frame.kind() == RealtimeFrame.Kind.BOOK_REQUEST
                && com.surprising.product.api.utils.InstrumentIds.valid(frame.instrumentId());
        if (frame.productLine() == productLine && frame.snapshotId() > 0 && frame.payloadLength() == 0
                && frame.ordinal() == 0 && frame.sequence() == 0 && (snapshotRequest || bookRequest)) {
            requests.offer(frame);
        }
    }

    /** 仅已启用且为 Leader 时按原日志位置、业务时间及当前导出序号开始捕获。 */
    public void beginCapture(TradingCore state, long position, long timestamp) {
        if (capture == null || !leader) return;
        try {
            capture.begin(position, timestamp, 0, state.realtimeExportSequence());
        } catch (RuntimeException failure) {
            capture.failed();
        }
    }

    /** 推进已提交位置，存在捕获器时按运行时当前导出序号提交原 outbox 批次。 */
    public void commit(TradingCore state, long position) {
        committedPosition = position;
        if (capture != null) capture.commit(state.realtimeExportSequence());
    }

    /** 更新 Leader 标记并撤销当前未完成捕获，不清已排队的实时请求。 */
    public void roleChange(Cluster.Role role) {
        leader = role == Cluster.Role.LEADER;
        if (capture != null) capture.abort();
    }

    /** 启用且为 Leader 时先推进已有账户快照和盘口读取。否则最多取一个请求，用已提交位置及原 Cluster 时间发起读取。 */
    public int poll(TradingCore state, long clusterTime, long nowNanos) {
        if (capture == null || !leader) return 0;
        int work = state.pollRealtimeSnapshot() + state.pollRealtimeBook();
        if (state.realtimeSnapshotPending() || state.realtimeBookPending()
                || nowNanos < nextSnapshotNanos) return work;
        RealtimeFrame request = requests.poll();
        if (request == null) return work;

        nextSnapshotNanos = nowNanos + SNAPSHOT_INTERVAL_NANOS;
        if (request.kind() == RealtimeFrame.Kind.BOOK_REQUEST) {
            state.captureRealtimeBook(request.instrumentId(), committedPosition, clusterTime);
        } else {
            state.captureRealtimeSnapshot(request.userId(), request.snapshotId(),
                    committedPosition, clusterTime);
        }
        return work + 1;
    }

    /** 接入非空外部 outbox。 */
    public void attach(RealtimeOutbox externalOutbox, TradingRealtimeBoundary.StateCapture externalCapture,
                TradingCore state) {
        outbox = java.util.Objects.requireNonNull(externalOutbox, "realtime outbox is required");
        capture = externalCapture == null ? state.attachRealtime(outbox) : externalCapture;
    }

    /** 恢复替换运行时后，已有 outbox 时让新运行时重新接入捕获器，不复制账户状态。 */
    public void rebindState(TradingCore state) {
        if (outbox != null) capture = state.attachRealtime(outbox);
    }

    /** 只判断是否已接入捕获器，不表示当前是 Leader 或网络资源一定存在。 */
    public boolean enabled() {
        return capture != null;
    }

    /** 依次关闭全部已创建资源，保留首个异常和后续清理故障。 */
    @Override
    public void close() {
        requests.clear();
        Throwable failure = null;
        try { if (controlReceiver != null) controlReceiver.close(); }
        catch (RuntimeException | Error problem) { failure = problem; }
        finally { controlReceiver = null; }
        try { if (sender != null) sender.close(); }
        catch (RuntimeException | Error problem) {
            if (failure == null) failure = problem;
            else if (failure != problem) failure.addSuppressed(problem);
        } finally {
            sender = null;
            capture = null;
            outbox = null;
            leader = false;
        }
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) throw runtime;
    }

/** Owner 把已交接的业务发布值编码成实时帧的边界。只编码，不把可变账户对象交给发送线程。 */
@RequiredArgsConstructor
public static final class StateCapture {
    /** 本 Owner 使用的实时暂存与提交缓存，发送线程只消费已编码字节。 */
    private final RealtimeOutbox outbox;
    private final ProductLine product;
    private final IdIndex identities;
    @Accessors(fluent = true, chain = false)
    @Setter
    private boolean orderProjectionOnly;

    @Accessors(fluent = true, chain = false)
    @Setter
    private java.util.function.Consumer<LiquidationChange> liquidationObserver;

    /** 原已提交日志位置、业务时间和不可变强平后值，不复制账户状态。 */
    public record LiquidationChange(RiskScanner.LiquidationOrder state, long logPosition, long occurredAt) {}

    /** Owner 收齐 Lane 回执后交出本次变化，观察异常由原捕获失败边界处理。 */
    public void liquidation(RiskScanner.LiquidationOrder value) {
        if (active() && value != null && liquidationObserver != null)
            liquidationObserver.accept(new LiquidationChange(value, sequence, timestamp));
    }

    private long sequence, timestamp, snapshotId;
    /** 本捕获内下一帧从零开始的顺序号。 */
    private int ordinal;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long failures;
    private long exportSequence;
    private long previousExportSequence;

    /** 以上次导出序号零开始本轮捕获，供没有显式序号的既有调用使用。 */
    public void begin(long position, long time, long snapshot) {
        begin(position, time, snapshot, 0);
    }

    /** 固定本轮日志位置、时间、快照身份及起始导出序号，帧序号归零并开始 outbox 批次。 */
    public void begin(long position, long time, long snapshot, long previousExportSequence) {
        this.previousExportSequence = previousExportSequence;
        this.exportSequence = previousExportSequence;
        sequence = position;
        timestamp = time;
        snapshotId = snapshot;
        ordinal = 0;
        outbox.begin();
        if (snapshot == 0) {
            outbox.stage(RealtimeFrameCodec.encodeLongPayload(
                    product, RealtimeFrame.Kind.COMMIT_BEGIN, 0, sequence, ordinal++, timestamp, snapshotId, "", "", previousExportSequence));
        }
    }

    /** 读取 outbox 当前批次是否活跃，未活跃入口通常直接跳过编码。 */
    public boolean active() {
        return outbox.active();
    }

    /** 记录本轮最新导出序号，再走普通提交或快照的统一收尾。 */
    public void commit(long exportSequence) {
        this.exportSequence = exportSequence;
        commit();
    }

    /** 普通活跃批次若只有开始帧且导出序号未变则撤销空批次，否则追加 COMMIT_END。 */
    public void commit() {
        if (snapshotId == 0 && active()) {
            if (ordinal == 1 && exportSequence == previousExportSequence) {
                abort();
                return;
            }
            outbox.stage(RealtimeFrameCodec.encodeLongPayload(
                    product, RealtimeFrame.Kind.COMMIT_END, 0, sequence, ordinal++, timestamp, snapshotId, "", "", exportSequence));
        }
        outbox.commit();
    }

    /** 撤销当前 outbox 暂存批次，不增加失败次数。 */
    public void abort() {
        outbox.abort();
    }

    /** 增加失败次数并撤销本次捕获，计数沿用普通 long 自增。 */
    public void failed() {
        failures++;
        abort();
    }

    /** 只有活跃捕获才按本轮元数据编码并暂存一帧，帧序号递增。 */
    public void emit(
            RealtimeFrame.Kind kind, long userId, String instrumentId, String key, byte[] payload) {
        if (!active()) return;
        outbox.stage(
                RealtimeFrameCodec.encode(
                                product,
                                kind,
                                userId,
                                sequence,
                                ordinal++,
                                timestamp,
                                snapshotId,
                                instrumentId,
                                key,
                                payload));
    }

    /** 把 Lane 已冻结的用户快照转为协议用户视图，余额与预留/持仓的内部编号在此还原文本。 */
    public void snapshot(long userId, TradingRealtimeBoundary.UserSnapshot snapshot, long exportSequence) {
        var rawBalances = snapshot.balances();
        var balances = new java.util.ArrayList<CoreBalanceView>(rawBalances.size());
        for (int i = 0; i < rawBalances.size(); i++) {
            var v = rawBalances.get(i);
            balances.add(new CoreBalanceView(
                    identities.asset(v.assetId()),
                    v.available(),
                    v.locked()));
        }

        var rawReservations = snapshot.reservations();
        var reservations = new java.util.ArrayList<CoreReservationView>(rawReservations.size());
        for (int i = 0; i < rawReservations.size(); i++) {
            var v = rawReservations.get(i);
            reservations.add(new CoreReservationView(
                    v.orderId(),
                    identities.instrumentId(v.symbolId()),
                    v.kind(),
                    identities.asset(v.assetId()),
                    v.totalReservedUnits(),
                    v.releasedUnits(),
                    v.consumedUnits(),
                    v.orderQuantitySteps()));
        }

        var rawPositions = snapshot.positions();
        var positions = new java.util.ArrayList<CorePositionView>(rawPositions.size());
        for (int i = 0; i < rawPositions.size(); i++) {
            var v = rawPositions.get(i);
            positions.add(new CorePositionView(
                    identities.instrumentId(v.symbolId()),
                    identities.asset(v.assetId()),
                    v.marginMode(),
                    v.positionSide(),
                    v.signedQuantitySteps(),
                    v.entryPriceTicks(),
                    v.entryValueTicks(),
                    v.realizedPnlUnits(),
                    v.positionMarginUnits()));
        }

        var rawLeverages = snapshot.leverages();
        var leverages = new java.util.ArrayList<CoreLeverageView>(rawLeverages.size());
        for (int i = 0; i < rawLeverages.size(); i++) {
            var v = rawLeverages.get(i);
            leverages.add(new CoreLeverageView(
                    v.key().instrumentId(), v.key().marginMode(), v.value()));
        }
        var u = snapshot.user();
        emit(RealtimeFrame.Kind.SNAPSHOT_BEGIN, userId, "", "", new byte[0]);
        user(
                new CoreUserStateView(
                        product,
                        userId,
                        u == null ? 0 : u.revision(),
                        u == null ? CorePositionMode.ONE_WAY : u.positionMode(),
                        balances,
                        reservations,
                        positions,
                        leverages));
        snapshot.orders().forEach(this::order);
        snapshot.triggers().forEach(this::trigger);
        snapshot.risks().forEach(this::risk);
        outbox.stage(RealtimeFrameCodec.encodeLongPayload(
                product, RealtimeFrame.Kind.SNAPSHOT_END, userId, sequence, ordinal++, timestamp, snapshotId, "", "", exportSequence));
    }

    /** 把已经组装的完整用户视图编码成 USER 帧，不在这里按订单投影标记跳过。 */
    public void user(CoreUserStateView view) {
        emit(
                RealtimeFrame.Kind.USER,
                view.userId(),
                "",
                "user",
                CoreStateQueryCodec.encodeUserState(view));
    }

    /** 非订单投影且活跃时编码单资产余额。 */
    public void balance(long userId, int assetId, long available, long locked) {
        if (orderProjectionOnly) return;
        if (!active()) return;
        try {
            String assetName = identities.asset(assetId);
            emit(
                    RealtimeFrame.Kind.BALANCE,
                    userId,
                    "",
                    assetName,
                    CoreStateQueryCodec.encodeSingleBalanceUserState(product, userId, assetName, available, locked));
        } catch (RuntimeException failure) {
            failed();
        }
    }

    /** 非订单投影且用户存在、捕获活跃时编码用户修订及持仓模式。 */
    public void metadata(UserAccount u) {
        if (orderProjectionOnly) return;
        if (!active() || u == null) return;
        emit(
                RealtimeFrame.Kind.METADATA,
                u.userId(),
                "",
                "user",
                CoreStateQueryCodec.encodeUserMetadataState(product, u.userId(), u.revision(), u.positionMode()));
    }

    /** 非订单投影且预留存在、捕获活跃时还原标的和资产，编码该订单预留总额、已释放、已消耗和数量。 */
    public void reservation(OrderFundsReservation r) {
        if (orderProjectionOnly) return;
        if (!active() || r == null) return;
        String instrumentId = identities.instrumentId(r.symbolId());
        String asset = identities.asset(r.assetId());
        emit(
                RealtimeFrame.Kind.RESERVATION,
                r.userId(),
                instrumentId,
                Long.toString(r.orderId()),
                CoreStateQueryCodec.encodeSingleReservationUserState(
                        product, r.userId(), r.orderId(), instrumentId, r.kind(), asset,
                        r.totalReservedUnits(), r.releasedUnits(), r.consumedUnits(), r.orderQuantitySteps()));
    }

    /** 非订单投影且活跃时按用户标的保证金模式键编码杠杆，RuntimeException 转捕获失败并撤销。 */
    public void leverage(CoreLeverageKey key, long value) {
        if (orderProjectionOnly) return;
        if (!active()) return;
        try {
            emit(
                    RealtimeFrame.Kind.LEVERAGE,
                    key.userId(),
                    key.instrumentId(),
                    key.instrumentId() + ":" + key.marginMode(),
                    CoreStateQueryCodec.encodeSingleLeverageUserState(
                            product, key.userId(), key.instrumentId(), key.marginMode(), value));
        } catch (RuntimeException failure) {
            failed();
        }
    }

    /** 非订单投影且有原持仓时发送归零的数量、入场及保证金字段，保留原已实现损益，让订阅方识别持仓消失。 */
    public void removedPosition(Position p) {
        if (orderProjectionOnly) return;
        if (!active() || p == null) return;
        String instrumentId = identities.instrumentId(p.symbolId());
        String asset = identities.asset(p.assetId());
        emit(
                RealtimeFrame.Kind.POSITION,
                p.userId(),
                instrumentId,
                instrumentId + ":" + p.positionSide(),
                CoreStateQueryCodec.encodeSinglePositionUserState(
                        product, p.userId(), instrumentId, asset, p.marginMode(), p.positionSide(),
                        0, 0, 0, p.realizedPnlUnits(), 0));
    }

    /** 非订单投影且有持仓、捕获活跃时编码当前数量、入场价值、已实现损益及保证金。 */
    public void position(Position p) {
        if (orderProjectionOnly) return;
        if (!active() || p == null) return;
        String instrumentId = identities.instrumentId(p.symbolId());
        String asset = identities.asset(p.assetId());
        emit(
                RealtimeFrame.Kind.POSITION,
                p.userId(),
                instrumentId,
                instrumentId + ":" + p.positionSide(),
                CoreStateQueryCodec.encodeSinglePositionUserState(
                        product, p.userId(), instrumentId, asset, p.marginMode(), p.positionSide(),
                        p.signedQuantitySteps(), p.entryPriceTicks(), p.entryValueTicks(),
                        p.realizedPnlUnits(), p.positionMarginUnits()));
    }

    /** 把原订单发布值转换成完整协议订单视图，再交订单帧入口。RuntimeException 转捕获失败并撤销，不把原对象留给网络线程。 */
    public void order(Order o) {
        if (!active() || o == null) return;
        try {
            CoreOrderStateView v =
                    new CoreOrderStateView(
                            o.orderId(),
                            o.productLine(),
                            o.userId(),
                            identities.instrumentId(o.symbolId()),
                            o.side(),
                            o.priceTicks(),
                            o.quantitySteps(),
                            o.executedQuantitySteps(),
                            o.remainingQuantitySteps(),
                            o.reduceOnly(),
                            o.marginMode(),
                            o.positionSide(),
                            o.orderType(),
                            o.timeInForce(),
                            o.postOnly(),
                            o.clientOrderId(),
                            o.commandId(),
                            o.makerFeeRatePpm(),
                            o.takerFeeRatePpm(),
                            o.cumulativeFeeUnits(), o.executedValueHigh(), o.executedValueLow(),
                            o.createdAtEpochMillis(),
                            o.updatedAtEpochMillis(),
                            o.clusterPosition(),
                            o.status().name(),
                            o.revision());
            order(v);
        } catch (RuntimeException failure) {
            failed();
        }
    }

    /** 捕获活跃时直接编码已组装协议订单视图并暂存，不在此再次检查空值或捕获异常。 */
    public void order(CoreOrderStateView o) {
        if (!active()) return;
        outbox.stage(RealtimeFrameCodec.encodeOrder(o, sequence, ordinal++, timestamp, snapshotId));
    }

    /** 捕获活跃且触发单存在时编码一项触发视图，以触发单业务 ID 作实体键。 */
    public void trigger(CoreTriggerOrderState t) {
        if (!active() || t == null) return;
        emit(
                RealtimeFrame.Kind.TRIGGER,
                t.userId(),
                t.instrumentId(),
                Long.toString(t.triggerOrderId()),
                CoreTriggerOrderCodec.encodeList(List.of(t.view())));
    }

    /** 捕获活跃且 taker 存在时提取其用户、订单、标的和方向，再交 primitive 字段入口，不把 taker 引用交给发送线程。 */
    public void trade(
            Order taker,
            long matcherSequence,
            int fillIndex,
            long price,
            long quantity,
            long makerOrderId,
            long makerUserId) {
        if (!active() || taker == null) return;
        trade(taker.userId(), taker.orderId(), taker.symbolId(), taker.side(), matcherSequence,
                fillIndex, price, quantity, makerOrderId, makerUserId);
    }

    /** 检查活跃及 taker 基本身份后，用产品线、日志位置、Matcher 序号、taker 订单和成交下标组成 ID。 */
    public void trade(
            long takerUserId,
            long takerOrderId,
            int takerSymbolId,
            CoreOrderSide takerSide,
            long matcherSequence,
            int fillIndex,
            long price,
            long quantity,
            long makerOrderId,
            long makerUserId) {
        if (!active() || takerUserId <= 0 || takerOrderId <= 0 || takerSymbolId < 0 || takerSide == null) return;
        // 由产品线、原日志位置、Matcher 序号、taker 订单及成交下标组合的确定性成交实体 ID。
        String id =
                product
                        + ":"
                        + sequence
                        + ":"
                        + matcherSequence
                        + ":"
                        + takerOrderId
                        + ":"
                        + fillIndex;
        String instrumentId = identities.instrumentId(takerSymbolId);
        outbox.stage(RealtimeFrameCodec.encodeTradePayload(
                product, sequence, ordinal++, timestamp, snapshotId, instrumentId, id,
                price, quantity, matcherSequence, (byte) takerSide.ordinal()));
        execution(
                takerUserId,
                takerOrderId,
                instrumentId,
                id,
                price,
                quantity,
                takerSide,
                false);
        execution(
                makerUserId,
                makerOrderId,
                instrumentId,
                id,
                price,
                quantity,
                takerSide == CoreOrderSide.BUY ? CoreOrderSide.SELL : CoreOrderSide.BUY,
                true);
    }

    private void execution(
            long user,
            long order,
            String instrumentId,
            String id,
            long price,
            long quantity,
            CoreOrderSide side,
            boolean maker) {
        outbox.stage(RealtimeFrameCodec.encodeExecutionPayload(
                product, user, sequence, ordinal++, timestamp, snapshotId, instrumentId, id,
                order, price, quantity, (byte) side.ordinal(), maker));
    }

    /** 非订单投影且有风险、捕获活跃时编码指定持仓方向的权益、未实现盈亏、维持保证金、比率和风险状态。 */
    public void risk(RiskMarginCalculator.Snapshot risk) {
        if (orderProjectionOnly) return;
        if (!active() || risk == null) return;
        String instrumentId = identities.instrumentId(risk.symbolId());
        outbox.stage(RealtimeFrameCodec.encodeRiskPayload(
                product, risk.userId(), sequence, ordinal++, timestamp, snapshotId, instrumentId,
                instrumentId + ":" + risk.positionSide(),
                risk.priceSequence(), risk.equityUnits(), risk.unrealizedPnlUnits(),
                risk.maintenanceMarginUnits(), risk.marginRatioPpm(),
                (byte) risk.positionSide().ordinal(), (byte) risk.status().ordinal()));
    }
}

/** 在所属账户 Lane 的 FIFO 读取边界捕获一个用户的实时快照。 */
public record UserSnapshot(
        UserAccount user,
        List<BalanceValue> balances,
        List<OrderFundsReservation> reservations,
        List<Position> positions,
        List<Order> orders,
        List<CoreTriggerOrderState> triggers,
        List<LeverageValue> leverages,
        List<RiskMarginCalculator.Snapshot> risks) {
    /** 只保存资产内部编号、可用和冻结数字的余额发布值，不持有可变余额对象。 */
    public record BalanceValue(int assetId, long available, long locked) {}

    /** 用户标的保证金模式键及其当时杠杆值。 */
    public record LeverageValue(CoreLeverageKey key, long value) {}

    /** 先核对当前线程持有 Lane，读取该用户各成员索引并限制候选总数不超过四千零九十六。 */
    public static UserSnapshot capture(AccountPartition lane, long userId) {
        lane.assertOwner();
        var balances = lane.userBalances(userId);
        var orderIds = lane.activeOrderIds(userId);
        var reservationIds = lane.reservationIds(userId);
        var positionKeys = lane.positionKeys(userId);
        var triggerIds = lane.triggerIds(userId);
        var leverageKeys = lane.leverageKeys(userId);
        long count =
                (balances == null ? 0 : balances.size())
                        + size(orderIds)
                        + size(reservationIds)
                        + size(positionKeys)
                        + size(triggerIds)
                        + (leverageKeys == null ? 0 : leverageKeys.size());
        if (count > 4096)
            throw new IllegalArgumentException("realtime user snapshot exceeds entity capacity");
        var b = new ArrayList<BalanceValue>();
        if (balances != null)
            balances.forEachValue(
                    v -> b.add(new BalanceValue(v.assetId(), v.availableUnits(), v.lockedUnits())));
        var o = new ArrayList<Order>();
        if (orderIds != null)
            orderIds.forEachLong(
                    id -> {
                        var v = lane.order(id);
                        if (v != null && !v.status().terminal()) o.add(v.publicationValue());
                    });
        var re = new ArrayList<OrderFundsReservation>();
        if (reservationIds != null)
            reservationIds.forEachLong(
                    id -> {
                        var v = lane.reservation(id);
                        if (v != null) re.add(v.publicationValue());
                    });
        var p = new ArrayList<Position>();
        if (positionKeys != null)
            positionKeys.forEach(
                    id -> {
                        var v = lane.position(id);
                        if (v != null) p.add(v.publicationValue());
                    });
        var risk = new ArrayList<RiskMarginCalculator.Snapshot>();
        if (positionKeys != null)
            positionKeys.forEach(
                    id -> {
                        var v = lane.riskSnapshot(id);
                        if (v != null) risk.add(v);
                    });
        var t = new ArrayList<CoreTriggerOrderState>();
        if (triggerIds != null)
            triggerIds.forEach(
                    id -> {
                        var v = lane.triggerOrder(id);
                        if (v != null && v.status().open()) t.add(v);
                    });
        var l = new ArrayList<LeverageValue>();
        if (leverageKeys != null)
            leverageKeys.forEach(k -> l.add(new LeverageValue(k, lane.leverage(k))));
        return new UserSnapshot(
                lane.user(userId) == null ? null : lane.user(userId).publicationValue(),
                List.copyOf(b),
                List.copyOf(re),
                List.copyOf(p),
                List.copyOf(o),
                List.copyOf(t),
                List.copyOf(l),
                List.copyOf(risk));
    }

    private static long size(LongHashSet values) {
        return values == null ? 0 : values.size();
    }

    private static long size(org.agrona.collections.LongHashSet values) {
        return values == null ? 0 : values.size();
    }
}
}
