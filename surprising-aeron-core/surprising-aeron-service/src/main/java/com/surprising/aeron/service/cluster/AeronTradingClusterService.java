package com.surprising.aeron.service.cluster;

import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.WireMessageKind;
import com.surprising.aeron.service.orchestration.ClusterServiceEgress;
import com.surprising.aeron.service.orchestration.TradingOwnerLoop;
import com.surprising.aeron.service.orchestration.ingress.CoreMessageFlyweightDecoder;
import com.surprising.aeron.service.orchestration.snapshot.SectionedCoreSnapshotCodec;
import com.surprising.product.api.ProductLine;
import io.aeron.ExclusivePublication;
import io.aeron.Image;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.AgentTerminationException;
import org.agrona.concurrent.UnsafeBuffer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Aeron Cluster 生命周期回调适配器。
 *
 * <p>本类接收 Cluster 消息、会话、选主和快照回调，并把交易命令交给 {@link TradingOwnerLoop}；
 * 响应通过 {@link ClusterServiceEgress} 发回客户端。本类不直接修改账户、订单或撮合业务状态。
 * Cluster 回调线程负责 Aeron 会话及快照发布，Owner 线程负责交易状态处理，两者通过队列交接。</p>
 */
@Component
public final class AeronTradingClusterService implements ClusteredService {
    private static final long DEADLINE_NS = java.util.concurrent.TimeUnit.SECONDS.toNanos(Long.getLong(
            "surprising.aeron.snapshot-timeout-seconds", 300L));

    private final ClusterServiceEgress egress;
    private final TradingOwnerLoop owner;
    private Cluster cluster;

    /** Spring 使用的构造方法；注入已配置好的 Owner 与响应出口。 */
    @Autowired
    public AeronTradingClusterService(TradingOwnerLoop owner, ClusterServiceEgress egress) {
        this.egress = java.util.Objects.requireNonNull(egress);
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    /** 创建独立运行的 Cluster Service，并使用默认 Aeron 响应出口。 */
    public AeronTradingClusterService(ProductLine productLine) {
        this(productLine, new ClusterServiceEgress());
    }

    /** 创建独立运行的 Cluster Service，并让 Owner 与指定响应出口共用会话状态。 */
    public AeronTradingClusterService(ProductLine productLine, ClusterServiceEgress egress) {
        this.egress = java.util.Objects.requireNonNull(egress);
        this.owner = new TradingOwnerLoop(productLine, egress);
    }

    /** 创建独立运行的 Cluster Service，并通过工厂定制各客户端 Session 的响应 Publication。 */
    public AeronTradingClusterService(ProductLine productLine, ClusterServiceEgress.EgressFactory egressFactory) {
        this(productLine, new ClusterServiceEgress(egressFactory));
    }

    /**
     * Cluster 启动入口：保存 Cluster 句柄、启动响应出口，读取可用快照后启动 Owner。
     *
     * @param cluster Aeron Cluster 提供的服务运行上下文
     * @param snapshot Cluster 提供的恢复快照；首次启动时可能为 {@code null}
     */
    @Override
    public void onStart(Cluster cluster, Image snapshot) {
        this.cluster = cluster;
        egress.start(cluster);
        owner.start(cluster, readSnapshot(snapshot));
    }

    /**
     * 接收 Cluster 已提交的客户端消息，解码后连同日志顺序信息交给 Owner，再尝试发送已就绪响应。
     *
     * @param session 发送方客户端会话；服务之间的消息可能为 {@code null}
     * @param timestamp Cluster 为该消息记录的时间戳
     * @param buffer 包含编码消息的 Aeron 缓冲区
     * @param offset 编码消息在缓冲区中的起始位置
     * @param length 编码消息的字节数
     * @param header Aeron 消息头，提供该消息在 Cluster 日志中的位置等信息
     */
    @Override
    public void onSessionMessage(ClientSession session, long timestamp, DirectBuffer buffer,
            int offset, int length, Header header) {
        // 上一次响应发送可能因背压或超时请求关闭会话。TransportSession.close() 只记下请求，
        // 这里在 Cluster 回调线程中真正关闭 Aeron ClientSession，再处理新消息。
        egress.flushSessionClosures();

        CoreMessage command;
        try {
            // 将 Aeron 收到的协议帧解码为 Core 消息；解码失败时没有可信的命令内容可继续处理。
            command = CoreMessageFlyweightDecoder.decode(buffer, offset, length);
        } catch (IllegalArgumentException invalid) {
            // 非法帧直接丢弃，不进入 Owner 命令队列。
            return;
        }

        // 把已提交消息交给专属 Owner 线程：保留来源会话（服务消息可能没有 session）、
        // Cluster 时间和日志位置；只有业务命令才计算指纹，用于命令幂等/冲突识别。
        owner.enqueue(command, session == null ? null : egress.transportSession(session), timestamp,
                header.position(), command.header().kind() == WireMessageKind.COMMAND
                        ? CommandFingerprint.of(command) : null);

        // 立即尝试发送 Owner 已经准备好的响应；仍在异步处理中的命令稍后由后台轮询继续发送。
        owner.drainResponses();
    }

    /**
     * 新客户端会话建立时检查 Owner 是否发生故障，创建响应传输会话，并处理之前挂起的会话关闭请求。
     *
     * @param session 新建立的客户端会话
     * @param timestamp Cluster 报告的会话建立时间
     */
    @Override
    public void onSessionOpen(ClientSession session, long timestamp) {
        owner.checkFailure();
        if (session != null) egress.transportSession(session);
        egress.flushSessionClosures();
    }

    /**
     * 客户端会话关闭时移除其响应队列和传输句柄，然后检查 Owner 故障。
     *
     * @param session 已关闭的客户端会话
     * @param timestamp Cluster 报告的会话关闭时间
     * @param reason Aeron Cluster 提供的关闭原因
     */
    @Override
    public void onSessionClose(ClientSession session, long timestamp, CloseReason reason) {
        egress.closeSession(session);
        owner.checkFailure();
    }

    /**
     * 处理 Cluster 定时器回调；当前服务不使用旧日志定时器驱动交易状态，只检查 Owner 故障。
     *
     * @param correlationId 定时器关联 ID
     * @param timestamp Cluster 报告的定时器触发时间
     */
    @Override
    public void onTimerEvent(long correlationId, long timestamp) {
        owner.checkFailure();
    }

    /**
     * Cluster 空闲轮询时发送 Owner 已发布的不可变响应，不读取或推进交易状态。
     *
     * @param nowNs Cluster 提供的当前纳秒时间
     * @return 本次发送出口处理的工作数量
     */
    @Override
    public int doBackgroundWork(long nowNs) {
        owner.checkFailure();
        try {
            return owner.drainResponses();
        } catch (RuntimeException fatal) {
            throw new AgentTerminationException(fatal);
        }
    }

    /** 处理 Cluster 角色变化，并同步更新 Owner 任期和响应出口的会话状态。 */
    @Override
    public void onRoleChange(Cluster.Role role) {
        owner.onRoleChange(role, egress.onRoleChange(role));
    }

    /**
     * 收到新的领导任期事件时更新响应出口使用的 term ID，并检查 Owner 是否正常。
     *
     * @param termId 新领导任期 ID
     * @param logPosition 事件对应的 Cluster 日志位置
     * @param timestamp Cluster 报告的事件时间
     * @param termBaseLogPosition 新任期的日志起始位置
     * @param leaderMemberId 新 Leader 的成员 ID
     * @param logSessionId 日志 Session ID
     * @param timeUnit 事件时间所用单位
     * @param appVersion 集群应用版本
     */
    @Override
    public void onNewLeadershipTermEvent(long termId, long logPosition, long timestamp,
            long termBaseLogPosition, int leaderMemberId, int logSessionId,
            java.util.concurrent.TimeUnit timeUnit, int appVersion) {
        egress.leadershipTerm(termId);
        owner.checkFailure();
    }

    /**
     * 将 Owner 捕获的快照 section 分块写入 Cluster 快照 Publication；遇到可重试的背压时等待进展。
     *
     * @param publication Cluster 提供的独占快照 Publication
     */
    @Override
    public void onTakeSnapshot(ExclusivePublication publication) {
        var snapshot = owner.captureSnapshotSections(cluster.logPosition(), cluster.time());
        long deadline = System.nanoTime() + DEADLINE_NS;
        for (byte[] chunk : snapshot.chunks()) {
            UnsafeBuffer buffer = new UnsafeBuffer(chunk);
            for (int offset = 0; offset < chunk.length;) {
                int length = Math.min(publication.maxPayloadLength(), chunk.length - offset);
                long result = publication.offer(buffer, offset, length);
                if (result >= 0) {
                    offset += length;
                } else {
                    if (result != io.aeron.Publication.BACK_PRESSURED
                            && result != io.aeron.Publication.ADMIN_ACTION
                            && result != io.aeron.Publication.NOT_CONNECTED) {
                        throw new IllegalStateException("snapshot publication failed: " + result);
                    }
                    awaitProgress(deadline);
                }
            }
        }
    }

    /** 捕获当前交易运行态快照，供诊断和基准测试的恢复检查使用；Cluster 正式快照走 {@link #onTakeSnapshot(ExclusivePublication)}。 */
    public byte[] captureSnapshot() {
        return owner.captureSnapshot(cluster.logPosition(), cluster.time());
    }

    /**
     * Cluster 服务终止时停止 Owner、清理响应出口，并释放本类持有的 Cluster 引用。
     *
     * @param ignored Cluster 生命周期回调传入的上下文；当前清理逻辑使用已保存的状态
     */
    @Override
    public void onTerminate(Cluster ignored) {
        try {
            owner.terminate();
        } finally {
            egress.clear();
            cluster = null;
        }
    }

    /**
     * 快照读写发生背压时执行一次有界等待：检查 Owner 和超时状态、排空响应，再让出 Cluster 线程。
     *
     * @param deadline 本次快照操作允许继续等待到达的单调时钟时间点
     */
    private void awaitProgress(long deadline) {
        owner.checkFailure();
        if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) {
            throw new AgentTerminationException("trading owner boundary or snapshot deadline");
        }
        owner.drainResponses();
        cluster.idleStrategy().idle();
    }

    /**
     * 将 Cluster 恢复 Image 中的快照片段读入恢复缓冲区；无快照时返回 {@code null}。
     *
     * @param snapshot Cluster 提供的快照 Image
     * @return 可交给 Owner 恢复运行态的 section 缓冲区
     * @throws IllegalStateException 快照读取超过恢复期限时抛出
     */
    private SectionedCoreSnapshotCodec.RecoveryBuffer readSnapshot(Image snapshot) {
        if (snapshot == null) return null;
        var recovery = new SectionedCoreSnapshotCodec.RecoveryBuffer();
        long deadline = System.nanoTime() + DEADLINE_NS;
        while (!snapshot.isEndOfStream()) {
            int work = snapshot.poll((buffer, offset, length, header) ->
                    recovery.accept(buffer, offset, length), 10);
            if (System.nanoTime() > deadline) throw new IllegalStateException("snapshot recovery deadline");
            cluster.idleStrategy().idle(work);
        }
        return recovery;
    }
}
