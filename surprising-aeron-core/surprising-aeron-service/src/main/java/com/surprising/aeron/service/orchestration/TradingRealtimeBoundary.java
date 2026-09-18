package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.client.AeronRealtimeReceiver;
import com.surprising.aeron.client.AeronRealtimeSender;
import com.surprising.aeron.client.RealtimeOutbox;
import com.surprising.aeron.protocol.RealtimeFrame;
import com.surprising.aeron.service.state.realtime.RealtimeStateCapture;
import com.surprising.product.api.ProductLine;
import io.aeron.CommonContext;
import io.aeron.cluster.service.Cluster;
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue;

/**
 * 交易状态到实时快照/盘口出口的边界。
 *
 * <p>实时读取不是撮合提交的一部分：它只能读取已经提交的状态，并且不能阻塞订单和资金主流程。
 * 本类拥有实时传输对象、请求队列、Leader 状态和已提交位置水位，TradingCoreOwner 只调用业务含义明确的方法。</p>
 */
final class TradingRealtimeBoundary implements AutoCloseable {
    /** 实时请求队列的固定容量，满载时由请求方稍后重试。 */
    private static final int REQUEST_CAPACITY = 256;
    /** 连续实时快照请求之间的最短间隔。 */
    private static final long SNAPSHOT_INTERVAL_NANOS =
            java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(10);

    /** 所属产品线，用于过滤控制面请求。 */
    private final ProductLine productLine;
    /** 实时事件的有界输出缓存。 */
    private RealtimeOutbox outbox;
    /** 实时事件发送器；只在配置了实时通道时创建。 */
    private AeronRealtimeSender sender;
    /** 实时快照/盘口请求接收器；只在配置了控制通道时创建。 */
    private AeronRealtimeReceiver controlReceiver;
    /** 交易运行时使用的实时状态捕获器。 */
    private RealtimeStateCapture capture;
    /** 来自控制通道的快照和盘口请求，生产者为接收线程、消费者为 Owner 线程。 */
    private final ManyToOneConcurrentArrayQueue<RealtimeFrame> requests =
            new ManyToOneConcurrentArrayQueue<>(REQUEST_CAPACITY);
    /** 只有 Leader 才能产生对外实时状态。 */
    private boolean leader;
    /** 最近一次已提交命令的日志位置，实时读取只能使用这个水位。 */
    private long committedPosition;
    /** 下一次允许启动实时快照工作的时间。 */
    private long nextSnapshotNanos;

    /** 创建指定产品线的实时边界。 */
    TradingRealtimeBoundary(ProductLine productLine) {
        this.productLine = java.util.Objects.requireNonNull(productLine, "product line is required");
    }

    /**
     * 根据系统配置启动实时输出。
     *
     * <p>没有配置实时通道时保持禁用，不影响交易主流程。</p>
     */
    void start(Cluster cluster, TradingCoreRuntime state) {
        leader = cluster.role() == Cluster.Role.LEADER;
        committedPosition = Math.max(0, cluster.logPosition());
        nextSnapshotNanos = 0;
        String channel = System.getProperty("surprising.realtime.channel", "");
        if (channel.isBlank() || outbox != null) return;

        outbox = new RealtimeOutbox(8192, 8 * 1024 * 1024);
        capture = state.attachRealtime(outbox);
        String directory = System.getProperty("surprising.realtime.directory", CommonContext.getAeronDirectoryName());
        sender = new AeronRealtimeSender(outbox, directory, channel,
                Integer.getInteger("surprising.realtime.stream", 2101));
        String controlChannel = System.getProperty("surprising.realtime.control-channel", "");
        if (!controlChannel.isBlank()) {
            controlReceiver = new AeronRealtimeReceiver(directory, controlChannel,
                    Integer.getInteger("surprising.realtime.control-stream", 2102), this::acceptRequest);
        }
    }

    /** 接收控制线程投递的实时请求，只接受当前产品线和合法协议形状。 */
    private void acceptRequest(RealtimeFrame frame) {
        boolean snapshotRequest = frame.userId() > 0
                && frame.kind() == RealtimeFrame.Kind.SNAPSHOT_REQUEST;
        boolean bookRequest = frame.userId() == 0
                && frame.kind() == RealtimeFrame.Kind.BOOK_REQUEST
                && frame.symbol().matches("[A-Z0-9][A-Z0-9_-]{1,63}");
        if (frame.productLine() == productLine && frame.snapshotId() > 0 && frame.payloadLength() == 0
                && frame.ordinal() == 0 && frame.sequence() == 0 && (snapshotRequest || bookRequest)) {
            requests.offer(frame);
        }
    }

    /** 在一条命令开始修改实时导出状态时建立捕获边界。 */
    void beginCapture(TradingCoreRuntime state, long position, long timestamp) {
        if (capture == null || !leader) return;
        try {
            capture.begin(position, timestamp, 0, state.realtimeExportSequence());
        } catch (RuntimeException failure) {
            capture.failed();
        }
    }

    /** 在交易命令提交后发布本次实时状态变化，并推进可读位置水位。 */
    void commit(TradingCoreRuntime state, long position) {
        committedPosition = position;
        if (capture != null) capture.commit(state.realtimeExportSequence());
    }

    /** 角色切换时撤销未完成的实时捕获，Follower 不产生实时输出。 */
    void roleChange(Cluster.Role role) {
        leader = role == Cluster.Role.LEADER;
        if (capture != null) capture.abort();
    }

    /**
     * 在没有待处理交易命令时推进实时快照和盘口读取。
     *
     * @return 本轮完成的实时工作数量
     */
    int poll(TradingCoreRuntime state, Cluster cluster, long nowNanos) {
        if (capture == null || !leader) return 0;
        int work = state.pollRealtimeSnapshot() + state.pollRealtimeBook();
        if (state.realtimeSnapshotPending() || state.realtimeBookPending()
                || nowNanos < nextSnapshotNanos) return work;
        RealtimeFrame request = requests.poll();
        if (request == null) return work;

        nextSnapshotNanos = nowNanos + SNAPSHOT_INTERVAL_NANOS;
        if (request.kind() == RealtimeFrame.Kind.BOOK_REQUEST) {
            state.captureRealtimeBook(request.symbol(), committedPosition, cluster.time());
        } else {
            state.captureRealtimeSnapshot(request.userId(), request.snapshotId(),
                    committedPosition, cluster.time());
        }
        return work + 1;
    }

    /** 为测试和兼容回放入口提供实时请求队列。 */
    ManyToOneConcurrentArrayQueue<RealtimeFrame> requests() {
        return requests;
    }

    /** 绑定外部创建的实时输出，供独立测试和回放工具使用。 */
    void attach(RealtimeOutbox externalOutbox, RealtimeStateCapture externalCapture,
                TradingCoreRuntime state) {
        outbox = java.util.Objects.requireNonNull(externalOutbox, "realtime outbox is required");
        capture = externalCapture == null ? state.attachRealtime(outbox) : externalCapture;
    }

    /** 交易状态从快照恢复后重新绑定实时捕获器，不复制业务状态。 */
    void rebindState(TradingCoreRuntime state) {
        if (outbox != null) capture = state.attachRealtime(outbox);
    }

    /** 返回是否已经建立实时捕获边界。 */
    boolean enabled() {
        return capture != null;
    }

    /** 关闭实时接收和发送资源，并清空本轮请求。 */
    @Override
    public void close() {
        requests.clear();
        if (controlReceiver != null) {
            controlReceiver.close();
            controlReceiver = null;
        }
        if (sender != null) {
            sender.close();
            sender = null;
        }
        capture = null;
        outbox = null;
        leader = false;
    }
}
