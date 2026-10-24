package com.surprising.aeron.service.realtime.state;

import com.surprising.aeron.client.RealtimeOutbox;
import com.surprising.aeron.model.CoreLeverageKey;
import com.surprising.aeron.model.CoreTriggerOrderState;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.account.UserRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.order.state.OrderRuntime;
import com.surprising.aeron.service.order.state.ReservationRuntime;
import com.surprising.aeron.service.position.PositionRuntime;
import com.surprising.aeron.service.risk.RiskSnapshotRuntime;
import com.surprising.aeron.service.risk.LiquidationRuntime;
import com.surprising.product.api.ProductLine;
import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * Owner 把已交接的业务发布值编码成实时帧的边界。每轮 outbox 捕获内使用原日志位置、业务时间和递增帧序号，普通提交带开始/结束标记，用户快照带快照标记。只编码，不把可变账户对象交给发送线程；部分入口捕获编码异常并撤销，其余异常沿原调用边界传播。
 * 构造约定：保存原 outbox、产品线及身份表引用；本构造器不额外做空值校验或启动资源。
 * 参数 outbox：本轮复用的有界实时输出缓存
 * 参数 product：所有输出帧所属产品线
 * 参数 identities：资产和标的内部编号的原身份表
 */
@RequiredArgsConstructor
public final class RealtimeStateCapture {
    /** 本 Owner 使用的实时暂存与提交缓存，发送线程只消费已编码字节。 */
    private final RealtimeOutbox outbox;
    /** 输出帧唯一产品线。 */
    private final ProductLine product;
    /** 已准备内部编号到永久标的、资产文本的身份表。 */
    private final RuntimeIdentityRegistry identities;
    /**
     *  是否跳过指定账户增量类别，不影响所有帧类型。
     * 设置是否跳过余额、元数据、预留、持仓、杠杆和风险增量；订单、触发单、成交及执行回报仍按原入口处理，不是统一屏蔽全部私有数据。
     * @param orderProjectionOnly 是否跳过指定账户增量类别
     */
    @Accessors(fluent = true, chain = false)
    @Setter
    private boolean orderProjectionOnly;

    /** 独立提交回放使用的同步观察器；在线实时捕获不注册，不创建强平投影对象。 */
    @Accessors(fluent = true, chain = false)
    @Setter
    private java.util.function.Consumer<LiquidationChange> liquidationObserver;

    /** 原已提交日志位置、业务时间和不可变强平后值，不复制账户状态。 */
    public record LiquidationChange(LiquidationRuntime state, long logPosition, long occurredAt) {}

    /** Owner 收齐 Lane 回执后交出本次变化，观察异常由原捕获失败边界处理。 */
    public void liquidation(LiquidationRuntime value) {
        if (active() && value != null && liquidationObserver != null)
            liquidationObserver.accept(new LiquidationChange(value, sequence, timestamp));
    }

    /**
     * 返回 outbox 当前是否处于捕获中，与 active 相同；不单独根据 orderProjectionOnly 判断。
     */
    public boolean privateStateEnabled() {
        return active();
    }

    /**
     * sequence：本轮原复制日志位置，作为帧序列身份。
     * timestamp：本轮原业务时间。
     * snapshotId：本轮用户快照身份，零表示普通提交批次。
     */
    private long sequence, timestamp, snapshotId;
    /** 本捕获内下一帧从零开始的顺序号。 */
    private int ordinal;
    /**  明确调用 failed 所记录的捕获失败次数。 读取已记录的捕获失败次数，不是 outbox 丢批次数。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long failures;
    /** 本次收尾要输出的业务导出序号。 */
    private long exportSequence;
    /** 捕获开始前导出序号，供消费者衔接及判断空批次。 */
    private long previousExportSequence;

    /**
     * 以上次导出序号零开始本轮捕获，供没有显式序号的既有调用使用。
     * @param position 本轮复制日志位置
     * @param time 本轮原业务时间
     * @param snapshot 本轮快照整数身份，零表示普通命令批次
     */
    public void begin(long position, long time, long snapshot) {
        begin(position, time, snapshot, 0);
    }

    /**
     * 固定本轮日志位置、时间、快照身份及起始导出序号，帧序号归零并开始 outbox 批次；普通命令先写 COMMIT_BEGIN，快照由 snapshot 方法写自己的边界。
     * @param position 本轮复制日志位置
     * @param time 本轮原业务时间
     * @param snapshot 本轮快照整数身份，零表示普通命令批次
     * @param previousExportSequence 捕获开始前业务导出序号
     */
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

    /**
     * 读取 outbox 当前批次是否活跃，未活跃入口通常直接跳过编码。
     */
    public boolean active() {
        return outbox.active();
    }

    /**
     * 记录本轮最新导出序号，再走普通提交或快照的统一收尾。
     * @param exportSequence 本轮提交或用户快照完成时对应的业务导出序号
     */
    public void commit(long exportSequence) {
        this.exportSequence = exportSequence;
        commit();
    }

    /**
     * 普通活跃批次若只有开始帧且导出序号未变则撤销空批次，否则追加 COMMIT_END；快照不再追加普通标记，最后交 outbox 提交，不等待网络发送。
     */
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

    /**
     * 撤销当前 outbox 暂存批次，不增加失败次数。
     */
    public void abort() {
        outbox.abort();
    }

    /**
     * 增加失败次数并撤销本次捕获，计数沿用普通 long 自增。
     */
    public void failed() {
        failures++;
        abort();
    }

    /**
     * 只有活跃捕获才按本轮元数据编码并暂存一帧，帧序号递增；编码异常由外层入口决定是否记失败。
     * @param kind 本帧业务种类
     * @param userId 本帧或用户快照所属用户
     * @param instrumentId 永久数字标的文本，不适用的帧为空文本
     * @param key 本帧实体的原业务键文本
     * @param payload 已经构造的本帧协议载荷字节
     */
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

    /**
     * 把 Lane 已冻结的用户快照转为协议用户视图，余额与预留/持仓的内部编号在此还原文本；写 SNAPSHOT_BEGIN、用户、订单、触发和风险，再写带导出序号的 SNAPSHOT_END。调用方先建立本轮快照捕获，不在这里访问账户权威表。
     * @param userId 本帧或用户快照所属用户
     * @param snapshot 所属账户 Lane 已冻结的用户发布快照
     * @param exportSequence 本轮提交或用户快照完成时对应的业务导出序号
     */
    public void snapshot(long userId, RealtimeUserSnapshot snapshot, long exportSequence) {
        // Lane 快照的原余额数字列表
        var rawBalances = snapshot.balances();
        // 按永久资产文本组装的协议余额列表
        var balances = new java.util.ArrayList<CoreBalanceView>(rawBalances.size());
        for (/* 当前列表转换下标 */ int i = 0; i < rawBalances.size(); i++) {
            // 当前快照业务值，订单转换方法中为已组装协议订单视图
            var v = rawBalances.get(i);
            balances.add(new CoreBalanceView(
                    identities.asset(v.assetId()),
                    v.available(),
                    v.locked()));
        }

        // Lane 冻结的原订单预留发布值列表
        var rawReservations = snapshot.reservations();
        // 按标的及资产文本组装的协议预留列表
        var reservations = new java.util.ArrayList<CoreReservationView>(rawReservations.size());
        for (/* 当前列表转换下标 */ int i = 0; i < rawReservations.size(); i++) {
            // 当前快照业务值，订单转换方法中为已组装协议订单视图
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

        // Lane 冻结的原持仓发布值列表
        var rawPositions = snapshot.positions();
        // 本用户按文本身份组装的协议持仓列表
        var positions = new java.util.ArrayList<CorePositionView>(rawPositions.size());
        for (/* 当前列表转换下标 */ int i = 0; i < rawPositions.size(); i++) {
            // 当前快照业务值，订单转换方法中为已组装协议订单视图
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

        // Lane 快照的原杠杆键值列表
        var rawLeverages = snapshot.leverages();
        // 按原杠杆业务键组装的协议杠杆列表
        var leverages = new java.util.ArrayList<CoreLeverageView>(rawLeverages.size());
        for (/* 当前列表转换下标 */ int i = 0; i < rawLeverages.size(); i++) {
            // 当前快照业务值，订单转换方法中为已组装协议订单视图
            var v = rawLeverages.get(i);
            leverages.add(new CoreLeverageView(
                    v.key().instrumentId(), v.key().marginMode(), v.value()));
        }
        // 本用户冻结元数据，不存在时协议修订零、默认单向模式
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

    /**
     * 把已经组装的完整用户视图编码成 USER 帧，不在这里按订单投影标记跳过。
     * @param view 已经在读取边界组装好的完整用户协议视图
     */
    public void user(CoreUserStateView view) {
        emit(
                RealtimeFrame.Kind.USER,
                view.userId(),
                "",
                "user",
                CoreStateQueryCodec.encodeUserState(view));
    }

    /**
     * 非订单投影且活跃时编码单资产余额；身份查找或编码抛 RuntimeException 时记失败并撤销批次。
     * @param userId 本帧或用户快照所属用户
     * @param assetId 待编码余额的内部资产编号
     * @param available 当前可用资产单位数
     * @param locked 当前冻结资产单位数
     */
    public void balance(long userId, int assetId, long available, long locked) {
        if (orderProjectionOnly) return;
        if (!active()) return;
        try {
            // 从内部资产编号还原的永久资产文本
            String assetName = identities.asset(assetId);
            emit(
                    RealtimeFrame.Kind.BALANCE,
                    userId,
                    "",
                    assetName,
                    CoreStateQueryCodec.encodeSingleBalanceUserState(product, userId, assetName, available, locked));
        } catch (/* 当前入口捕获的 RuntimeException，随后记失败并撤销本批次 */ RuntimeException failure) {
            failed();
        }
    }

    /**
     * 非订单投影且用户存在、捕获活跃时编码用户修订及持仓模式；本入口不自行捕获编码异常。
     * @param u 本次用户元数据发布值，可空时跳过
     */
    public void metadata(UserRuntime u) {
        if (orderProjectionOnly) return;
        if (!active() || u == null) return;
        emit(
                RealtimeFrame.Kind.METADATA,
                u.userId(),
                "",
                "user",
                CoreStateQueryCodec.encodeUserMetadataState(product, u.userId(), u.revision(), u.positionMode()));
    }

    /**
     * 非订单投影且预留存在、捕获活跃时还原标的和资产，编码该订单预留总额、已释放、已消耗和数量。
     * @param r 本次订单预留发布值，可空时跳过
     */
    public void reservation(ReservationRuntime r) {
        if (orderProjectionOnly) return;
        if (!active() || r == null) return;
        // 当前发布值内部标的编号反查出的永久数字文本
        String instrumentId = identities.instrumentId(r.symbolId());
        // 当前预留或持仓内部资产编号反查出的文本
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

    /**
     * 非订单投影且活跃时按用户标的保证金模式键编码杠杆，RuntimeException 转捕获失败并撤销。
     * @param key 用户、标的及保证金模式组成的杠杆键
     * @param value 当前杠杆值
     */
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
        } catch (/* 当前入口捕获的 RuntimeException，随后记失败并撤销本批次 */ RuntimeException failure) {
            failed();
        }
    }

    /**
     * 非订单投影且有原持仓时发送归零的数量、入场及保证金字段，保留原已实现损益，让订阅方识别持仓消失。
     * @param p 当前或被删除的原持仓发布值，可空时跳过
     */
    public void removedPosition(PositionRuntime p) {
        if (orderProjectionOnly) return;
        if (!active() || p == null) return;
        // 当前发布值内部标的编号反查出的永久数字文本
        String instrumentId = identities.instrumentId(p.symbolId());
        // 当前预留或持仓内部资产编号反查出的文本
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

    /**
     * 非订单投影且有持仓、捕获活跃时编码当前数量、入场价值、已实现损益及保证金。
     * @param p 当前或被删除的原持仓发布值，可空时跳过
     */
    public void position(PositionRuntime p) {
        if (orderProjectionOnly) return;
        if (!active() || p == null) return;
        // 当前发布值内部标的编号反查出的永久数字文本
        String instrumentId = identities.instrumentId(p.symbolId());
        // 当前预留或持仓内部资产编号反查出的文本
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

    /**
     * 把原订单发布值转换成完整协议订单视图，再交订单帧入口；RuntimeException 转捕获失败并撤销，不把原对象留给网络线程。
     * @param o 当前账户订单的原发布值，可空时跳过
     */
    public void order(OrderRuntime o) {
        if (!active() || o == null) return;
        try {
            // 当前快照业务值，订单转换方法中为已组装协议订单视图
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
        } catch (/* 当前入口捕获的 RuntimeException，随后记失败并撤销本批次 */ RuntimeException failure) {
            failed();
        }
    }

    /**
     * 捕获活跃时直接编码已组装协议订单视图并暂存，不在此再次检查空值或捕获异常。
     * @param o 已经组装好的完整协议订单视图
     */
    public void order(CoreOrderStateView o) {
        if (!active()) return;
        outbox.stage(RealtimeFrameCodec.encodeOrder(o, sequence, ordinal++, timestamp, snapshotId));
    }

    /**
     * 捕获活跃且触发单存在时编码一项触发视图，以触发单业务 ID 作实体键。
     * @param t 当前不可变触发单状态，可空时跳过
     */
    public void trigger(CoreTriggerOrderState t) {
        if (!active() || t == null) return;
        emit(
                RealtimeFrame.Kind.TRIGGER,
                t.userId(),
                t.instrumentId(),
                Long.toString(t.triggerOrderId()),
                CoreTriggerOrderCodec.encodeList(List.of(t.view())));
    }

    /**
     * 捕获活跃且 taker 存在时提取其用户、订单、标的和方向，再交 primitive 字段入口，不把 taker 引用交给发送线程。
     * @param taker 本成交 taker 订单的发布值
     * @param matcherSequence 本成交事实对应的 Matcher 序号
     * @param fillIndex 该撮合事实内的成交下标，组成确定性成交 ID
     * @param price 成交价格 tick 数
     * @param quantity 成交数量步数
     * @param makerOrderId 对手 maker 订单号
     * @param makerUserId 对手 maker 用户 ID
     */
    public void trade(
            OrderRuntime taker,
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

    /**
     * 检查活跃及 taker 基本身份后，用产品线、日志位置、Matcher 序号、taker 订单和成交下标组成 ID；暂存一条公共 TRADE 及双方各一条 EXECUTION，maker 方向取 taker 相反。
     * @param takerUserId 主动成交用户 ID，必须为正才编码
     * @param takerOrderId 主动成交订单号，必须为正才编码
     * @param takerSymbolId 主动订单的内部稠密标的编号，零有效
     * @param takerSide 主动订单买卖方向
     * @param matcherSequence 本成交事实对应的 Matcher 序号
     * @param fillIndex 该撮合事实内的成交下标，组成确定性成交 ID
     * @param price 成交价格 tick 数
     * @param quantity 成交数量步数
     * @param makerOrderId 对手 maker 订单号
     * @param makerUserId 对手 maker 用户 ID
     */
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
        // 由产品线、原日志位置、Matcher 序号、taker 订单及成交下标组合的确定性成交实体 ID
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
        // 当前发布值内部标的编号反查出的永久数字文本
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

    /**
     * 在既有成交捕获范围编码单用户执行回报，使用同一成交 ID、价格数量及是否 maker 标志；本私有入口依赖调用方已检查捕获活跃。
     * @param user 本条执行回报所属用户
     * @param order 本条执行回报所属订单号
     * @param instrumentId 永久数字标的文本，不适用的帧为空文本
     * @param id 同一成交在公共及双方执行帧共用的实体 ID
     * @param price 成交价格 tick 数
     * @param quantity 成交数量步数
     * @param side 本条执行回报的买卖方向
     * @param maker 本条执行回报是否属于挂单方
     */
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

    /**
     * 非订单投影且有风险、捕获活跃时编码指定持仓方向的权益、未实现盈亏、维持保证金、比率和风险状态。
     * @param risk 指定持仓方向的不可变风险发布值，可空时跳过
     */
    public void risk(RiskSnapshotRuntime risk) {
        if (orderProjectionOnly) return;
        if (!active() || risk == null) return;
        // 当前发布值内部标的编号反查出的永久数字文本
        String instrumentId = identities.instrumentId(risk.symbolId());
        outbox.stage(RealtimeFrameCodec.encodeRiskPayload(
                product, risk.userId(), sequence, ordinal++, timestamp, snapshotId, instrumentId,
                instrumentId + ":" + risk.positionSide(),
                risk.priceSequence(), risk.equityUnits(), risk.unrealizedPnlUnits(),
                risk.maintenanceMarginUnits(), risk.marginRatioPpm(),
                (byte) risk.positionSide().ordinal(), (byte) risk.status().ordinal()));
    }
}
