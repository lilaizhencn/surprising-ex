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
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
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

/**
 * Owner 交易状态与实时传输之间的边界，持有 outbox、发送/接收资源、请求队列、角色及已提交位置。外层只在允许读取已提交状态的空闲边界调用 poll；本类不重新计算账户资金，也不把网络发送完成当作交易提交条件。
 */
public final class TradingRealtimeBoundary implements AutoCloseable {
    private final RealtimeConfiguration configuration;
    /** 实时控制请求队列固定二百五十六槽，满时不另建无界暂存。 */
    private static final int REQUEST_CAPACITY = 256;
    /** 新发起实时快照或盘口请求之间的十毫秒最短间隔。 */
    private static final long SNAPSHOT_INTERVAL_NANOS =
            java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(10);

    /** 本边界唯一产品线，用于控制请求过滤。 */
    private final ProductLine productLine;
    /** 已接入的有界实时输出缓存，未启用时为空。 */
    private RealtimeOutbox outbox;
    /** 配置实时通道后创建的传输发送资源。 */
    private AeronRealtimeSender sender;
    /** 控制通道非空时创建的请求接收资源。 */
    private AeronRealtimeReceiver controlReceiver;
    /** 运行时当前使用的 Owner 编码捕获器，恢复时重新绑定。 */
    private TradingRealtimeBoundary.StateCapture capture;
    /**  控制接收线程等生产者交给 Owner 的有界原请求队列。 借出原有界请求队列供现有调用边界投递，不能把直接入队当成已通过接收器协议筛选。 */
    private final ManyToOneConcurrentArrayQueue<RealtimeFrame> requests =
            new ManyToOneConcurrentArrayQueue<>(REQUEST_CAPACITY);
    /** 当前角色是否允许开始捕获或推进实时请求。 */
    private boolean leader;
    /** 最近一次交给本边界的已提交日志位置，实时读取沿用此水位。 */
    private long committedPosition;
    /** 下一次可发起请求的单调纳秒时间，已有读取仍可继续推进。 */
    private long nextSnapshotNanos;

    /**
     * 要求指定所属产品线，建立固定容量请求队列，此时不启动实时传输。
     * @param productLine 此实时边界所属的唯一产品线
     */
    public TradingRealtimeBoundary(ProductLine productLine) {
        this(productLine, RealtimeConfiguration.standalone());
    }

    public TradingRealtimeBoundary(ProductLine productLine, RealtimeConfiguration configuration) {
        this.configuration = java.util.Objects.requireNonNull(configuration);
        this.productLine = java.util.Objects.requireNonNull(productLine, "product line is required");
    }

    /**
     * 先更新角色、非负初始日志位置并重置请求节流，再使用已绑定的实时通道配置；通道为空或 outbox 已接入则不建资源。否则创建 outbox、接入状态捕获与发送器，控制通道非空时再创建请求接收器。
     * @param role 当前 Cluster 角色，用来决定是否为 Leader
     * @param logPosition 启动接入的原日志位置，负值按零处理
     * @param state 本产品线当前交易运行时，提供捕获、快照及盘口读取入口
     */
    public void start(Cluster.Role role, long logPosition, TradingCoreRuntime state) {
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

    /**
     * 只接收本产品线、正快照 ID、空载荷且序号形状合法的用户快照或公共盘口请求；盘口还需合法标的。尝试非阻塞入队，队满失败在此不保留或回执该请求。
     * @param frame 控制线程解码出的原请求帧，合法后尝试入队
     */
    private void acceptRequest(RealtimeFrame frame) {
        // 是否为正用户 ID 的用户快照请求
        boolean snapshotRequest = frame.userId() > 0
                && frame.kind() == RealtimeFrame.Kind.SNAPSHOT_REQUEST;
        // 是否为用户零且标的合法的公共盘口请求
        boolean bookRequest = frame.userId() == 0
                && frame.kind() == RealtimeFrame.Kind.BOOK_REQUEST
                && com.surprising.product.api.utils.InstrumentIds.valid(frame.instrumentId());
        if (frame.productLine() == productLine && frame.snapshotId() > 0 && frame.payloadLength() == 0
                && frame.ordinal() == 0 && frame.sequence() == 0 && (snapshotRequest || bookRequest)) {
            requests.offer(frame);
        }
    }

    /**
     * 仅已启用且为 Leader 时按原日志位置、业务时间及当前导出序号开始捕获；捕获开始抛 RuntimeException 时记失败并撤销。当前不读取 header 参数。
     * @param state 本产品线当前交易运行时，提供捕获、快照及盘口读取入口
     * @param position 本次命令原日志位置，提交后成为实时可读水位
     * @param timestamp 本次复制命令原业务时间
     */
    public void beginCapture(TradingCoreRuntime state, long position, long timestamp) {
        if (capture == null || !leader) return;
        try {
            capture.begin(position, timestamp, 0, state.realtimeExportSequence());
        } catch (/* 开始捕获时的 RuntimeException，随后记失败并撤销原批次 */ RuntimeException failure) {
            capture.failed();
        }
    }

    /**
     * 推进已提交位置，存在捕获器时按运行时当前导出序号提交原 outbox 批次；不等网络发送，也不在此补做角色检查。
     * @param state 本产品线当前交易运行时，提供捕获、快照及盘口读取入口
     * @param position 本次命令原日志位置，提交后成为实时可读水位
     */
    public void commit(TradingCoreRuntime state, long position) {
        committedPosition = position;
        if (capture != null) capture.commit(state.realtimeExportSequence());
    }

    /**
     * 更新 Leader 标记并撤销当前未完成捕获，不清已排队的实时请求。
     * @param role 当前 Cluster 角色，用来决定是否为 Leader
     */
    public void roleChange(Cluster.Role role) {
        leader = role == Cluster.Role.LEADER;
        if (capture != null) capture.abort();
    }

    /**
     * 启用且为 Leader 时先推进已有账户快照和盘口读取；未完成或未到十毫秒节流点则返回。否则最多取一个请求，用已提交位置及原 Cluster 时间发起读取；工作量包括已推进项及新发起项，不代表请求全已发送。
     * @param state 本产品线当前交易运行时，提供捕获、快照及盘口读取入口
     * @param clusterTime 外层传入的 Cluster 业务时间，不用系统墙钟代替
     * @param nowNanos 本轮单调纳秒时间，只用于读取节流
     */
    public int poll(TradingCoreRuntime state, long clusterTime, long nowNanos) {
        if (capture == null || !leader) return 0;
        // 本轮推进已有快照与盘口读取的工作量，再加可选新请求
        int work = state.pollRealtimeSnapshot() + state.pollRealtimeBook();
        if (state.realtimeSnapshotPending() || state.realtimeBookPending()
                || nowNanos < nextSnapshotNanos) return work;
        // 本轮从队列取出的唯一新请求，按类型发起读取
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

    /**
     * 接入非空外部 outbox；有外部捕获器则沿用，没有则请运行时创建。这里不启动发送/接收资源，也不自动设置 Leader。
     * @param externalOutbox 必须存在的外部实时输出缓存
     * @param externalCapture 可空外部捕获器，为空时用运行时接入结果
     * @param state 本产品线当前交易运行时，提供捕获、快照及盘口读取入口
     */
    public void attach(RealtimeOutbox externalOutbox, TradingRealtimeBoundary.StateCapture externalCapture,
                TradingCoreRuntime state) {
        outbox = java.util.Objects.requireNonNull(externalOutbox, "realtime outbox is required");
        capture = externalCapture == null ? state.attachRealtime(outbox) : externalCapture;
    }

    /**
     * 恢复替换运行时后，已有 outbox 时让新运行时重新接入捕获器，不复制账户状态。
     * @param state 本产品线当前交易运行时，提供捕获、快照及盘口读取入口
     */
    public void rebindState(TradingCoreRuntime state) {
        if (outbox != null) capture = state.attachRealtime(outbox);
    }

    /**
     * 只判断是否已接入捕获器，不表示当前是 Leader 或网络资源一定存在。
     */
    public boolean enabled() {
        return capture != null;
    }

    /** 依次关闭全部已创建资源，保留首个异常和后续清理故障；所有引用最终解除。 */
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


/**
 * Owner 把已交接的业务发布值编码成实时帧的边界。每轮 outbox 捕获内使用原日志位置、业务时间和递增帧序号，普通提交带开始/结束标记，用户快照带快照标记。只编码，不把可变账户对象交给发送线程；部分入口捕获编码异常并撤销，其余异常沿原调用边界传播。
 * 构造约定：保存原 outbox、产品线及身份表引用；本构造器不额外做空值校验或启动资源。
 * 参数 outbox：本轮复用的有界实时输出缓存
 * 参数 product：所有输出帧所属产品线
 * 参数 identities：资产和标的内部编号的原身份表
 */
@RequiredArgsConstructor
public static final class StateCapture {
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
    public record LiquidationChange(RiskScanner.LiquidationOrder state, long logPosition, long occurredAt) {}

    /** Owner 收齐 Lane 回执后交出本次变化，观察异常由原捕获失败边界处理。 */
    public void liquidation(RiskScanner.LiquidationOrder value) {
        if (active() && value != null && liquidationObserver != null)
            liquidationObserver.accept(new LiquidationChange(value, sequence, timestamp));
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
    public void snapshot(long userId, TradingRealtimeBoundary.UserSnapshot snapshot, long exportSequence) {
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

    /**
     * 非订单投影且预留存在、捕获活跃时还原标的和资产，编码该订单预留总额、已释放、已消耗和数量。
     * @param r 本次订单预留发布值，可空时跳过
     */
    public void reservation(OrderFundsReservation r) {
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
    public void removedPosition(Position p) {
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
    public void position(Position p) {
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
    public void order(Order o) {
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
    public void risk(RiskMarginCalculator.Snapshot risk) {
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

/**
 * 在所属账户 Lane 的 FIFO 读取边界捕获一个用户的实时快照。capture 把可变用户、订单、预留和持仓转成发布值，再交出只读列表；记录的普通构造器本身不检查或复制外部列表，调用方仍须遵守捕获边界。
 * @param user 用户发布值，不存在用户时可空
 * @param balances 独立余额数字发布值列表
 * @param reservations 用户现有预留的独立发布值列表
 * @param positions 用户现有持仓的独立发布值列表
 * @param orders 用户非终态订单的独立发布值列表
 * @param triggers 用户仍 open 的不可变触发单值列表
 * @param leverages 用户杠杆键和值的列表
 * @param risks 按用户持仓键找到的风险快照列表
 */
public record UserSnapshot(
        UserAccount user,
        List<BalanceValue> balances,
        List<OrderFundsReservation> reservations,
        List<Position> positions,
        List<Order> orders,
        List<CoreTriggerOrderState> triggers,
        List<LeverageValue> leverages,
        List<RiskMarginCalculator.Snapshot> risks) {
    /**
     * 只保存资产内部编号、可用和冻结数字的余额发布值，不持有可变余额对象。
     * @param assetId 已准备的内部资产编号
     * @param available 捕获时可用资产单位数
     * @param locked 捕获时冻结资产单位数
     */
    public record BalanceValue(int assetId, long available, long locked) {}

    /**
     * 用户标的保证金模式键及其当时杠杆值。
     * @param key 用户、标的及保证金模式组成的杠杆键
     * @param value 该键捕获时的杠杆值
     */
    public record LeverageValue(CoreLeverageKey key, long value) {}

    /**
     * 先核对当前线程持有 Lane，读取该用户各成员索引并限制候选总数不超过四千零九十六。复制余额数字与发布值，订单只留非终态、触发单只留 open，风险按持仓键读取；所有结果列表冻结后离开 Lane。调用方保证请求路由到该用户所属 Lane。
     * @param lane 用户所属且当前线程有读取权限的账户 Lane
     * @param userId 需要捕获快照的用户
     */
    public static UserSnapshot capture(AccountPartition lane, long userId) {
        lane.assertOwner();
        // 当前 Lane 中该用户的余额表引用，仅在捕获范围内读取
        var balances = lane.userBalances(userId);
        // 该用户现有活跃订单 ID 成员集合
        var orderIds = lane.activeOrderIds(userId);
        // 该用户现有预留 ID 集合
        var reservationIds = lane.reservationIds(userId);
        // 该用户现有确定性持仓键集合，也据此读取风险
        var positionKeys = lane.positionKeys(userId);
        // 该用户触发单 ID 集合，取正文后再筛 open
        var triggerIds = lane.triggerIds(userId);
        // 该用户现有杠杆业务键集合
        var leverageKeys = lane.leverageKeys(userId);
        // 余额、订单、预留、持仓、触发单和杠杆候选数之和，不额外加用户和风险项
        long count =
                (balances == null ? 0 : balances.size())
                        + size(orderIds)
                        + size(reservationIds)
                        + size(positionKeys)
                        + size(triggerIds)
                        + (leverageKeys == null ? 0 : leverageKeys.size());
        if (count > 4096)
            throw new IllegalArgumentException("realtime user snapshot exceeds entity capacity");
        // 独立余额数字结果列表
        var b = new ArrayList<BalanceValue>();
        if (balances != null)
            balances.forEachValue(
                    /* 当前在所属 Lane 读取、准备复制或筛选的原业务值 */ v -> b.add(new BalanceValue(v.assetId(), v.availableUnits(), v.lockedUnits())));
        // 非终态订单发布值结果列表
        var o = new ArrayList<Order>();
        if (orderIds != null)
            orderIds.forEachLong(
                    /* 当前待从账户 Lane 查询的订单、预留、持仓或触发单 ID */ id -> {
                        // 当前在所属 Lane 读取、准备复制或筛选的原业务值
                        var v = lane.order(id);
                        if (v != null && !v.status().terminal()) o.add(v.publicationValue());
                    });
        // 现有预留发布值结果列表
        var re = new ArrayList<OrderFundsReservation>();
        if (reservationIds != null)
            reservationIds.forEachLong(
                    /* 当前待从账户 Lane 查询的订单、预留、持仓或触发单 ID */ id -> {
                        // 当前在所属 Lane 读取、准备复制或筛选的原业务值
                        var v = lane.reservation(id);
                        if (v != null) re.add(v.publicationValue());
                    });
        // 现有持仓发布值结果列表
        var p = new ArrayList<Position>();
        if (positionKeys != null)
            positionKeys.forEach(
                    /* 当前待从账户 Lane 查询的订单、预留、持仓或触发单 ID */ id -> {
                        // 当前在所属 Lane 读取、准备复制或筛选的原业务值
                        var v = lane.position(id);
                        if (v != null) p.add(v.publicationValue());
                    });
        // 按当前持仓键读取的不可变风险快照结果列表
        var risk = new ArrayList<RiskMarginCalculator.Snapshot>();
        if (positionKeys != null)
            positionKeys.forEach(
                    /* 当前待从账户 Lane 查询的订单、预留、持仓或触发单 ID */ id -> {
                        // 当前在所属 Lane 读取、准备复制或筛选的原业务值
                        var v = lane.riskSnapshot(id);
                        if (v != null) risk.add(v);
                    });
        // 仍 open 的触发单结果列表
        var t = new ArrayList<CoreTriggerOrderState>();
        if (triggerIds != null)
            triggerIds.forEach(
                    /* 当前待从账户 Lane 查询的订单、预留、持仓或触发单 ID */ id -> {
                        // 当前在所属 Lane 读取、准备复制或筛选的原业务值
                        var v = lane.triggerOrder(id);
                        if (v != null && v.status().open()) t.add(v);
                    });
        // 杠杆键值结果列表
        var l = new ArrayList<LeverageValue>();
        if (leverageKeys != null)
            leverageKeys.forEach(/* 当前用户杠杆业务键 */ k -> l.add(new LeverageValue(k, lane.leverage(k))));
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

    /**
     * 读取 Eclipse primitive 长整数集合大小，空引用按零计入候选预算。
     * @param values 只用来计算候选数的 primitive 长整数集合，可空
     */
    private static long size(LongHashSet values) {
        return values == null ? 0 : values.size();
    }

    /**
     * 读取 Agrona primitive 长整数集合大小，空引用按零计入候选预算。
     * @param values 只用来计算候选数的 primitive 长整数集合，可空
     */
    private static long size(org.agrona.collections.LongHashSet values) {
        return values == null ? 0 : values.size();
    }
}
}
