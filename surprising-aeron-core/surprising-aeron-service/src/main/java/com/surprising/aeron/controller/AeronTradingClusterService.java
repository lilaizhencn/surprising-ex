package com.surprising.aeron.controller;

import com.surprising.aeron.config.ClusterTopology;
import com.surprising.aeron.config.CoreConfiguration;
import com.surprising.aeron.config.CoreRuntimeConfiguration;
import com.surprising.aeron.config.LaneTopology;
import com.surprising.aeron.config.RealtimeConfiguration;
import com.surprising.aeron.config.TradingCpuAffinity;
import com.surprising.aeron.model.OwnerLogContext;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.WireMessageKind;
import com.surprising.aeron.service.core.TradingCoreOwner;
import com.surprising.aeron.service.snapshot.SectionedCoreSnapshotCodec;
import com.surprising.aeron.support.egress.ClusterServiceEgress;
import com.surprising.aeron.support.ingress.CoreMessageFlyweightDecoder;
import com.surprising.aeron.support.ingress.OwnerIngressRing;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Aeron Cluster 生命周期与交易命令内联推进器。
 *
 * <p>本类实现 {@link ClusteredService}，在 Aeron 容器服务线程直接驱动交易状态与命令生命周期：
 * 接收 Cluster 复制消息并解码、入队到与 {@link TradingCoreOwner} 共享的输入环并推进业务流水线，
 * 响应通过 {@link ClusterServiceEgress} 单线程零拷贝发出。
 * 彻底消除跨核交接、中间人转发类与独立后台线程。</p>
 */
@Component
public final class AeronTradingClusterService implements ClusteredService {
    private static final Logger LOG = LoggerFactory.getLogger(AeronTradingClusterService.class);

    /** 普通输入等待使用的默认三十秒预算。 */
    private static final long DEADLINE_NS = 30_000_000_000L;

    /** 一次快照读取或发布允许等待的最长时间，转换成单调时钟使用的纳秒。 */
    private final long snapshotTimeoutNanos;
    private final int maximumSnapshotBytes;

    /** 当前服务的会话和响应发送出口，单线程原位处理。 */
    private final ClusterServiceEgress egress;
    /** 持有并推进实际交易状态的 Core Owner。 */
    private final TradingCoreOwner coreOwner;
    /** 与 Core Owner 共用的输入环，内联入队与排空。 */
    private final OwnerIngressRing input;

    /** 不可变交易线程 CPU 配置，构造时要求非空。 */
    private final TradingCpuAffinity affinityConfiguration;
    /** 当前 CPU 绑定租约，在退出时恢复并清空。 */
    private TradingCpuAffinity.Binding cpuAffinity;

    /** 跨调用可见的首个故障，后续调用据此终止。 */
    private volatile Throwable failure;
    /** 是否已完成初始化启动。 */
    private volatile boolean started;

    /** 复制给 Owner 的角色、业务时间和日志位置，不把 Cluster 网络能力交给交易状态。 */
    private OwnerLogContext logContext;
    /** 本 Owner 响应所属角色代次，在角色切换动作内更新。 */
    private long ownerEpoch;

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
     * Spring 装配主入口，接收统一绑定的拓扑与配置。
     */
    @Autowired
    public AeronTradingClusterService(
            ClusterTopology topology,
            ClusterServiceEgress egress,
            TradingCpuAffinity affinity,
            LaneTopology laneTopology,
            CoreRuntimeConfiguration configuration,
            RealtimeConfiguration realtimeConfiguration) {
        this.affinityConfiguration = java.util.Objects.requireNonNull(affinity);
        this.egress = java.util.Objects.requireNonNull(egress);
        this.snapshotTimeoutNanos = TimeUnit.SECONDS.toNanos(configuration.snapshotTimeoutSeconds());
        this.maximumSnapshotBytes = configuration.snapshot().maxBytes();
        this.messageDecoder = new CoreMessageFlyweightDecoder(configuration.decoderCacheSlots());
        this.coreOwner = new TradingCoreOwner(
                topology.productLine(),
                this::publishResponse,
                laneTopology,
                configuration,
                realtimeConfiguration);
        this.input = coreOwner.ingress();
    }

    /**
     * 为测试、工具等独立入口创建服务，同时创建默认响应出口。
     * @param productLine 本服务唯一处理的产品线
     */
    public AeronTradingClusterService(ProductLine productLine) {
        this(productLine, new ClusterServiceEgress());
    }

    /**
     * 创建独立服务，指定响应出口，默认关闭 CPU 绑定。
     * @param productLine 本服务唯一处理的产品线
     * @param egress 调用方提供的响应出口
     */
    public AeronTradingClusterService(ProductLine productLine, ClusterServiceEgress egress) {
        this(productLine, egress, TradingCpuAffinity.disabled());
    }

    /**
     * 创建独立服务，并允许测试或工具替换每个会话的响应发送端。
     * @param productLine 本服务唯一处理的产品线
     * @param egressFactory 根据客户端会话创建响应发送端的工厂
     */
    public AeronTradingClusterService(ProductLine productLine, ClusterServiceEgress.EgressFactory egressFactory) {
        this(productLine, new ClusterServiceEgress(egressFactory));
    }

    public AeronTradingClusterService(ProductLine productLine, ClusterServiceEgress egress,
            TradingCpuAffinity affinity) {
        this(productLine, egress, affinity, CoreConfiguration.standaloneLaneTopology());
    }

    public AeronTradingClusterService(ProductLine productLine, ClusterServiceEgress egress,
            TradingCpuAffinity affinity, LaneTopology laneTopology) {
        this(productLine, egress, affinity, laneTopology, CoreRuntimeConfiguration.standalone());
    }

    public AeronTradingClusterService(ProductLine productLine, ClusterServiceEgress egress,
            TradingCpuAffinity affinity, LaneTopology laneTopology, CoreRuntimeConfiguration configuration) {
        this(productLine, egress, affinity, laneTopology, configuration, RealtimeConfiguration.standalone());
    }

    public AeronTradingClusterService(ProductLine productLine, ClusterServiceEgress egress,
            TradingCpuAffinity affinity, LaneTopology laneTopology, CoreRuntimeConfiguration configuration,
            RealtimeConfiguration realtimeConfiguration) {
        this.affinityConfiguration = java.util.Objects.requireNonNull(affinity);
        this.egress = java.util.Objects.requireNonNull(egress);
        this.snapshotTimeoutNanos = TimeUnit.SECONDS.toNanos(configuration.snapshotTimeoutSeconds());
        this.maximumSnapshotBytes = configuration.snapshot().maxBytes();
        this.messageDecoder = new CoreMessageFlyweightDecoder(configuration.decoderCacheSlots());
        this.coreOwner = new TradingCoreOwner(
                productLine,
                this::publishResponse,
                laneTopology,
                configuration,
                realtimeConfiguration);
        this.input = coreOwner.ingress();
    }

    /**
     * 测试或显式装配入口：直接组合 CoreOwner 与 Egress。
     */
    public AeronTradingClusterService(TradingCoreOwner coreOwner, ClusterServiceEgress egress) {
        this(coreOwner, egress, CoreRuntimeConfiguration.standalone());
    }

    public AeronTradingClusterService(TradingCoreOwner coreOwner, ClusterServiceEgress egress,
            CoreRuntimeConfiguration configuration) {
        this.affinityConfiguration = TradingCpuAffinity.disabled();
        this.egress = java.util.Objects.requireNonNull(egress);
        this.snapshotTimeoutNanos = TimeUnit.SECONDS.toNanos(configuration.snapshotTimeoutSeconds());
        this.maximumSnapshotBytes = configuration.snapshot().maxBytes();
        this.messageDecoder = new CoreMessageFlyweightDecoder(configuration.decoderCacheSlots());
        this.coreOwner = java.util.Objects.requireNonNull(coreOwner);
        this.input = coreOwner.ingress();
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
        failure = null;
        ownerEpoch = 0;
        messageDecoder.reset();
        egress.start(cluster);
        logContext = new OwnerLogContext(cluster.role(), cluster.time(), cluster.logPosition());
        coreOwner.start(logContext);
        var recovery = readSnapshot(snapshot, cluster);
        if (recovery != null) {
            coreOwner.restoreSnapshot(recovery);
        }
        // 先启动所有工作资源，再收窄当前服务线程 CPU mask，避免 Ledger 等线程继承单核限制。
        coreOwner.state().activate(affinityConfiguration);
        cpuAffinity = affinityConfiguration.owner();
        started = true;
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

        // 把已提交消息直接交给输入环：保留来源会话（服务消息可能没有 session）、
        // Cluster 时间和日志位置；只有业务命令才计算指纹，用于命令幂等/冲突识别。
        try {
            enqueue(command, session == null ? ClusterServiceEgress.NO_SESSION_ID : egress.transportSession(session).id(),
                    timestamp, header.position(),
                    command.header().kind() == WireMessageKind.COMMAND ? fingerprints.ofBorrowed(command) : null);
        } catch (/* 记录输入交接失败，归还未成功转移给 Owner 的解码槽后继续抛出。 */ RuntimeException | Error fail) {
            command.releaseDecoderSlot();
            throw fail;
        }

        // 批量阈值触发响应排水，避免每条命令被打断；剩余响应由 doBackgroundWork 兜底发送
        if (++unflushedInboundMessages >= INBOUND_DRAIN_THRESHOLD) {
            unflushedInboundMessages = 0;
            drainResponses();
        }
    }

    /**
     * 交接一条已复制业务命令，保留会话、业务时间、日志位置和指纹，并在当前线程立即推进流水线准入。
     * @param command 借给 Owner 的已复制命令
     * @param sessionId 原会话 ID；无网络目标时使用 NO_SESSION_ID
     * @param timestamp 来自复制日志的业务时间
     * @param position 来自复制日志的位置
     * @param fingerprint 已解码请求的内容指纹，供原幂等边界使用
     */
    public void enqueue(CoreMessage command, long sessionId, long timestamp, long position,
                        CommandFingerprint fingerprint) {
        checkFailure();
        if (input.offer(command, sessionId, timestamp, position, fingerprint)) {
            if (started) {
                pollCommands();
            }
            return;
        }
        // 输入环满时，先在当前线程尝试推进排空已有就绪命令
        if (started) {
            pollCommands();
        }
        long startedNs = System.nanoTime();
        long deadline = startedNs + DEADLINE_NS;
        boolean reported = false;
        while (true) {
            awaitProgress(deadline);
            checkFailure();
            if (input.offer(command, sessionId, timestamp, position, fingerprint)) {
                if (started) {
                    pollCommands();
                }
                return;
            }
            if (!reported && System.nanoTime() - startedNs >= TimeUnit.SECONDS.toNanos(5)) {
                LOG.error("Core owner input has made no capacity for 5 seconds queued={} ownerFailure={}",
                        input.size(), failure);
                reported = true;
            }
        }
    }

    /**
     * 在当前线程推进一次 Core 命令生命周期及完成信号处理。
     * @return 实际产生推进的工作单元数
     */
    public int pollCommands() {
        checkFailure();
        if (!started) return 0;
        try {
            return coreOwner.pollCommands();
        } catch (Throwable fatal) {
            failure = fatal;
            throw fatal;
        }
    }

    /**
     * 推进 Core 命令生命周期及完成信号，并消费 egress 队列实际发送响应。
     * @return 产生推进与发送的工作单元总数
     */
    public int drainResponses() {
        int work = 0;
        if (started) {
            work = pollCommands();
        }
        return work + egress.drainResponses(coreOwner);
    }

    /**
     * 新客户端会话建立时检查 Owner 是否发生故障，创建响应传输会话，并处理之前挂起的会话关闭请求。
     *
     * @param session 新建立的客户端会话
     * @param timestamp Cluster 报告的会话建立时间
     */
    @Override
    public void onSessionOpen(ClientSession session, long timestamp) {
        checkFailure();
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
        checkFailure();
    }

    /**
     * 处理 Cluster 定时器回调；当前服务不使用旧日志定时器驱动交易状态，只检查 Owner 故障。
     *
     * @param correlationId 定时器关联 ID
     * @param timestamp Cluster 报告的定时器触发时间
     */
    @Override
    public void onTimerEvent(long correlationId, long timestamp) {
        checkFailure();
    }

    /**
     * Cluster 空闲轮询时推进 Owner 命令生命周期与完成信号，并发送已就绪响应。
     *
     * @param nowNs Cluster 提供的当前纳秒时间
     * @return 本次处理的工作数量
     */
    @Override
    public int doBackgroundWork(long nowNs) {
        checkFailure();
        unflushedInboundMessages = 0;
        try {
            return drainResponses();
        } catch (/* 让 Cluster 看见后台响应处理失败，不能让故障 Owner 悄悄继续服务。 */ RuntimeException fatal) {
            throw new AgentTerminationException(fatal);
        }
    }

    /**
     * 在当前线程同步执行角色切换：先推进完成前面所有积压命令，再更新角色和响应 epoch。
     * @param role 本成员切换后的 Leader、Follower 等集群角色
     */
    @Override
    public void onRoleChange(Cluster.Role role) {
        onRoleChange(role, egress.onRoleChange(role));
    }

    /**
     * 允许显式指定代次的角色切换，供精准测试与受控边界调用。
     * @param role 边界确定的新 Cluster 角色
     * @param nextEpoch 新角色代次，响应出口用它区分角色变化
     */
    public void onRoleChange(Cluster.Role role, long nextEpoch) {
        checkFailure();
        if (started) {
            coreOwner.finishCommands();
        }
        if (logContext != null) {
            logContext.role(role);
        }
        ownerEpoch = nextEpoch;
        coreOwner.roleChange(role);
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
            TimeUnit timeUnit, int appVersion) {
        egress.leadershipTerm(termId);
        checkFailure();
    }

    /**
     * 将 Owner 捕获的快照 section 分块写入 Cluster 快照 Publication；遇到可重试的背压时等待进展。
     *
     * @param publication Cluster 提供的独占快照 Publication
     */
    @Override
    public void onTakeSnapshot(ExclusivePublication publication) {
        unflushedInboundMessages = 0;
        drainResponses();
        checkFailure();
        if (logContext != null) {
            logContext.position(cluster.logPosition());
            logContext.timestamp(cluster.time());
        }
        var snapshot = coreOwner.captureSnapshotSections(Math.max(1, cluster.logPosition()));
        writeSnapshot(snapshot, publication, this::awaitProgress);
    }

    /** 捕获当前交易运行态快照，供诊断和基准测试的恢复检查使用。 */
    public byte[] captureSnapshot() {
        return captureSnapshot(cluster != null ? cluster.logPosition() : 1, cluster != null ? cluster.time() : 0);
    }

    /**
     * 固定请求的日志位置与时间，并由 Core Owner 同步完成前缀命令后返回合并快照。
     * @param position 来自复制日志的位置
     * @param timestamp 来自复制日志的业务时间
     */
    public byte[] captureSnapshot(long position, long timestamp) {
        checkFailure();
        if (logContext != null) {
            logContext.position(position);
            logContext.timestamp(timestamp);
        }
        return coreOwner.captureSnapshot(Math.max(1, position));
    }

    /**
     * 按相同语义同步捕获快照，返回分块结果。
     * @param position 来自复制日志的位置
     * @param timestamp 来自复制日志的业务时间
     */
    public SectionedCoreSnapshotCodec.SectionedSnapshot captureSnapshotSections(long position, long timestamp) {
        checkFailure();
        if (logContext != null) {
            logContext.position(position);
            logContext.timestamp(timestamp);
        }
        return coreOwner.captureSnapshotSections(Math.max(1, position));
    }

    /**
     * Cluster 服务终止时停止 Owner、清理响应出口，并释放本类持有的 Cluster 引用与 CPU 绑定。
     *
     * @param ignored Cluster 生命周期回调传入的上下文；当前清理逻辑使用已保存的状态
     */
    @Override
    public void onTerminate(Cluster ignored) {
        try {
            coreOwner.terminate();
        } catch (Throwable fatal) {
            if (failure == null) failure = fatal;
        } finally {
            if (cpuAffinity != null) {
                try {
                    cpuAffinity.close();
                } catch (Throwable fatal) {
                    if (failure == null) failure = fatal;
                }
                cpuAffinity = null;
            }
            input.discard();
            egress.clear();
            cluster = null;
        }
    }

    /**
     * 读取保存的首个故障，存在时包装成 Agent 终止异常交给调用方。
     */
    public void checkFailure() {
        if (failure != null) throw new AgentTerminationException(failure);
    }

    /**
     * 把终态及当前 Owner epoch 交给已有 egress，响应缓冲继续按该出口的租约规则管理。
     */
    private void publishResponse(long sessionId, CoreMessageHeader header, CoreResponse response,
                                 long committedSequence) {
        egress.publishResponse(coreOwner, sessionId, header, response, committedSequence, ownerEpoch);
    }

    /**
     * 等待时检查故障、中断和截止时间，推进命令并持续排出响应，使用自旋等待。
     */
    private void awaitProgress(long deadline) {
        checkFailure();
        if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) {
            throw new AgentTerminationException("cluster command progress timed out");
        }
        if (started) {
            pollCommands();
        }
        egress.drainResponses(coreOwner);
        if (cluster != null && cluster.idleStrategy() != null) {
            cluster.idleStrategy().idle();
            return;
        }
        Thread.onSpinWait();
    }

    /**
     * 读取 Aeron 提供的快照片段并交给恢复缓冲区；这里只接收数据。
     * @param image 本次恢复的快照流，首次启动时可能为 null
     * @param cluster 提供空闲等待策略的当前集群上下文
     * @return 已收集的恢复缓冲区；没有快照时返回 null
     */
    private SectionedCoreSnapshotCodec.RecoveryBuffer readSnapshot(Image image, Cluster cluster) {
        if (image == null) return null;
        var recovery = new SectionedCoreSnapshotCodec.RecoveryBuffer(maximumSnapshotBytes);
        long deadline = System.nanoTime() + snapshotTimeoutNanos;
        while (!image.isEndOfStream()) {
            int work = image.poll((buffer, offset, length, header) ->
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
        long deadline = System.nanoTime() + snapshotTimeoutNanos;
        for (byte[] chunk : snapshot.chunks()) {
            var buffer = new UnsafeBuffer(chunk);
            for (int offset = 0; offset < chunk.length;) {
                int length = Math.min(publication.maxPayloadLength(), chunk.length - offset);
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

    /** 返回当前持有的 CoreOwner，供诊断与测试断言使用。 */
    public TradingCoreOwner coreOwner() {
        return coreOwner;
    }

    /** 返回当前使用的 Egress。 */
    public ClusterServiceEgress egress() {
        return egress;
    }

    /** 返回当前使用的输入环。 */
    public OwnerIngressRing ingress() {
        return input;
    }

    /** 返回当前的日志上下文。 */
    public OwnerLogContext logContext() {
        return logContext;
    }
}
