package com.surprising.aeron.service.realtime;

import com.surprising.aeron.config.RealtimeConfiguration;

import com.surprising.aeron.client.AeronRealtimeReceiver;
import com.surprising.aeron.client.AeronRealtimeSender;
import com.surprising.aeron.client.RealtimeOutbox;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.RealtimeFrame;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.realtime.state.RealtimeStateCapture;
import com.surprising.product.api.ProductLine;
import io.aeron.cluster.service.Cluster;
import lombok.Getter;
import lombok.experimental.Accessors;
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue;

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
    private RealtimeStateCapture capture;
    /**  控制接收线程等生产者交给 Owner 的有界原请求队列。 借出原有界请求队列供现有调用边界投递，不能把直接入队当成已通过接收器协议筛选。 */
    @Accessors(fluent = true, chain = false)
    @Getter
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
    public void attach(RealtimeOutbox externalOutbox, RealtimeStateCapture externalCapture,
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
}
