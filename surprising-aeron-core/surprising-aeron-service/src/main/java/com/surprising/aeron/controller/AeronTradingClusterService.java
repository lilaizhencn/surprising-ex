package com.surprising.aeron.controller;

import com.surprising.aeron.bootstrap.TradingOwnerLoop;
import com.surprising.aeron.config.CoreRuntimeConfiguration;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.WireMessageKind;
import com.surprising.aeron.service.snapshot.SectionedCoreSnapshotCodec;
import com.surprising.aeron.support.egress.ClusterServiceEgress;
import com.surprising.aeron.support.ingress.CoreMessageFlyweightDecoder;
import com.surprising.product.api.ProductLine;
import io.aeron.ExclusivePublication;
import io.aeron.Image;
import io.aeron.Publication;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.logbuffer.Header;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.AgentTerminationException;
import org.agrona.concurrent.UnsafeBuffer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Aeron Cluster 生命周期回调适配器。
 *
 * <p>本类接收 Cluster 消息、会话、选主和快照回调，内联调用 {@link TradingOwnerLoop} 推进交易命令生命周期；
 * 响应通过 {@link ClusterServiceEgress} 发回客户端。本类不直接修改账户、订单或撮合业务状态。
 * Cluster 服务线程内联执行交易命令与快照发布，消除跨核交接开销。</p>
 */
@Component
public final class AeronTradingClusterService implements ClusteredService {
    /** 一次快照读取或发布允许等待的最长时间，转换成单调时钟使用的纳秒。 */
    private final long snapshotTimeoutNanos;
    private final int maximumSnapshotBytes;
    /** 当前服务的会话和响应发送出口，只在 Cluster 线程真正操作 Aeron 会话。 */
    private final ClusterServiceEgress egress;
    /** 交易状态所属的专用 Owner 循环，通过输入环接收已复制命令。 */
    private final TradingOwnerLoop owner;
    private final CoreMessageFlyweightDecoder messageDecoder;
    /** 复用的命令指纹计算器，用来区分正常重试与同一命令编号携带不同内容的冲突。 */
    private final CommandFingerprint.Calculator fingerprints = new CommandFingerprint.Calculator();
    /** 连续收到这么多输入后主动发送一轮响应，减少逐条输入都排水的开销。 */
    private static final int INBOUND_DRAIN_THRESHOLD = 16;
    /** 上一次响应排水之后已接收的输入数，到达阈值或后台轮询时归零。 */
    private int unflushedInboundMessages;
    /** Aeron 提供的当前集群句柄，供快照发布和空闲等待使用，终止时释放引用。 */
    private Cluster cluster;

    /**
     * Spring 使用的构造方法，让回调适配器与 Owner 共用已装配的响应出口。
     * @param owner 接收复制命令并处理交易状态的专用循环
     * @param egress 与该 Owner 配套的会话和响应出口
     */
    public AeronTradingClusterService(TradingOwnerLoop owner, ClusterServiceEgress egress) {
        this(owner, egress, CoreRuntimeConfiguration.standalone());
    }

    @Autowired
    public AeronTradingClusterService(TradingOwnerLoop owner, ClusterServiceEgress egress,
            CoreRuntimeConfiguration configuration) {
        snapshotTimeoutNanos = TimeUnit.SECONDS.toNanos(configuration.snapshotTimeoutSeconds());
        maximumSnapshotBytes = configuration.snapshot().maxBytes();
        messageDecoder = new CoreMessageFlyweightDecoder(configuration.decoderCacheSlots());
        this.egress = java.util.Objects.requireNonNull(egress);
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    /**
     * 为测试、工具等独立入口创建服务，同时创建默认响应出口。
     * @param productLine 本服务唯一处理的产品线
     */
    public AeronTradingClusterService(ProductLine productLine) {
        this(productLine, new ClusterServiceEgress());
    }

    /**
     * 创建独立服务，并让新建 Owner 与指定出口共用会话状态。
     * @param productLine 本服务唯一处理的产品线
     * @param egress 调用方提供的响应出口
     */
    public AeronTradingClusterService(ProductLine productLine, ClusterServiceEgress egress) {
        this(new TradingOwnerLoop(productLine, egress), egress);
    }

    /**
     * 创建独立服务，并允许测试或工具替换每个会话的响应发送端。
     * @param productLine 本服务唯一处理的产品线
     * @param egressFactory 根据客户端会话创建响应发送端的工厂
     */
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
        messageDecoder.reset();
        egress.start(cluster);
        owner.start(cluster, readSnapshot(snapshot, cluster));
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

        // 借用的解码消息；入队成功后由 Owner 持有，入队失败时必须在本线程归还槽位。
        CoreMessage command;
        try {
            // 将 Aeron 收到的协议帧解码为 Core 消息；解码失败时没有可信的命令内容可继续处理。
            command = messageDecoder.decodeBorrowed(buffer, offset, length);
        } catch (/* 解码发现协议帧非法；此时没有可信命令可供继续处理。 */ IllegalArgumentException invalid) {
            // 非法帧直接丢弃，不进入 Owner 命令队列。
            return;
        }

        // 把已提交消息交给专属 Owner 线程：保留来源会话（服务消息可能没有 session）、
        // Cluster 时间和日志位置；只有业务命令才计算指纹，用于命令幂等/冲突识别。
        try {
            owner.enqueue(command, session == null ? ClusterServiceEgress.NO_SESSION_ID : egress.transportSession(session).id(), timestamp,
                    header.position(), command.header().kind() == WireMessageKind.COMMAND
                            ? fingerprints.ofBorrowed(command) : null);
        } catch (/* 记录输入交接失败，归还未成功转移给 Owner 的解码槽后继续抛出。 */ RuntimeException | Error failure) {
            // 输入没有成功交给 Owner，本线程仍负责归还这一解码槽。
            command.releaseDecoderSlot();
            throw failure;
        }

        // 批量阈值触发响应排水，避免每条命令被打断；剩余响应由 doBackgroundWork 兜底发送
        if (++unflushedInboundMessages >= INBOUND_DRAIN_THRESHOLD) {
            unflushedInboundMessages = 0;
            owner.drainResponses();
        }
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
     * Cluster 空闲轮询时推进 Owner 命令生命周期与完成信号，并发送已就绪响应。
     *
     * @param nowNs Cluster 提供的当前纳秒时间
     * @return 本次处理的工作数量
     */
    @Override
    public int doBackgroundWork(long nowNs) {
        owner.checkFailure();
        unflushedInboundMessages = 0;
        try {
            return owner.drainResponses();
        } catch (/* 让 Cluster 看见后台响应处理失败，不能让故障 Owner 悄悄继续服务。 */ RuntimeException fatal) {
            throw new AgentTerminationException(fatal);
        }
    }

    /**
     * 处理角色变化，并同步 Owner 任期和响应出口，防止旧 Leader 继续发送响应。
     * @param role 本成员切换后的 Leader、Follower 等集群角色
     */
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
        unflushedInboundMessages = 0;
        owner.drainResponses();
        // 在同一个复制日志边界捕获的分段快照，不能把不同完成位置的状态拼在一起。
        var snapshot = owner.captureSnapshotSections(cluster.logPosition(), cluster.time());
        writeSnapshot(snapshot, publication, this::awaitProgress);
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
     * 快照写入发生背压时执行一次有界等待：检查 Owner 和超时状态、排空响应，再让出 Cluster 线程。
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
     * 读取 Aeron 提供的快照片段并交给恢复缓冲区；这里只接收数据，还不启动交易线程。
     * @param image 本次恢复的快照流，首次启动时可能为 null
     * @param cluster 提供空闲等待策略的当前集群上下文
     * @return 已收集的恢复缓冲区；没有快照时返回 null
     */
    private SectionedCoreSnapshotCodec.RecoveryBuffer readSnapshot(Image image, Cluster cluster) {
        if (image == null) return null;
        // 按段收集和校验输入，随后交给 Owner 在启动边界恢复状态。
        var recovery = new SectionedCoreSnapshotCodec.RecoveryBuffer(maximumSnapshotBytes);
        // 一次恢复共用的截止时间，不能每收到一段就无限延长等待。
        long deadline = System.nanoTime() + snapshotTimeoutNanos;
        while (!image.isEndOfStream()) {
            // 本次已处理的片段数，空闲策略据此决定是否需要等待。
            int work = image.poll((/* 当前片段所在的 Aeron 缓冲区。 */ buffer,
                    /* 片段在缓冲区中的起始字节位置。 */ offset,
                    /* 当前片段占用的字节数。 */ length,
                    /* 当前片段的传输头；恢复数据处理不需要读取它。 */ header) ->
                    recovery.accept(buffer, offset, length), 10);
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("snapshot recovery deadline");
            }
            cluster.idleStrategy().idle(work);
        }
        return recovery;
    }

    /**
     * 按 Publication 能接收的最大负载切分快照，背压时重试同一段，不能跳过未发送的字节。
     * @param snapshot Owner 在固定提交边界生成的分段快照
     * @param publication Aeron 提供的快照写入通道
     * @param awaitProgress 背压时检查故障、让出线程并继续推进的等待动作
     */
    private void writeSnapshot(SectionedCoreSnapshotCodec.SectionedSnapshot snapshot,
                              ExclusivePublication publication, LongConsumer awaitProgress) {
        // 整次快照写入共用的截止时间，单个片段成功不会重置它。
        long deadline = System.nanoTime() + snapshotTimeoutNanos;
        for (/* 快照中的一个连续字节块，可能还要拆成多个传输负载。 */ byte[] chunk : snapshot.chunks()) {
            // 用 Aeron 接口需要的缓冲区视图包装原字节块，不再复制整份快照。
            var buffer = new UnsafeBuffer(chunk);
            for (/* 本块中下一个尚未成功发送的字节位置。 */ int offset = 0; offset < chunk.length;) {
                // 本次只发送通道允许的大小，最后一段按剩余字节数缩短。
                int length = Math.min(publication.maxPayloadLength(), chunk.length - offset);
                // 非负表示已写入；负数是背压、临时管理操作或不可继续的通道故障。
                long result = publication.offer(buffer, offset, length);
                if (result >= 0) {
                    offset += length;
                } else {
                    if (result != Publication.BACK_PRESSURED
                            && result != Publication.ADMIN_ACTION
                            && result != Publication.NOT_CONNECTED) {
                        throw new IllegalStateException("snapshot publication failed: " + result);
                    }
                    if (System.nanoTime() > deadline) {
                        throw new IllegalStateException("snapshot publication deadline");
                    }
                    awaitProgress.accept(deadline);
                }
            }
        }
    }
}
