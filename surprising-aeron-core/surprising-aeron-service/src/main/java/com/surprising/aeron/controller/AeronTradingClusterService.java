package com.surprising.aeron.controller;

import com.surprising.aeron.config.ClusterTopology;
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
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.AgentTerminationException;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Aeron Cluster 生命周期与交易命令内联推进器，在 Aeron 容器服务线程直接驱动命令生命周期。 */
@Component
public final class AeronTradingClusterService implements ClusteredService {
    private static final Logger LOG = LoggerFactory.getLogger(AeronTradingClusterService.class);

    private static final long DEADLINE_NS = 30_000_000_000L;

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
    private volatile boolean started;

    /** 复制给 Owner 的角色、业务时间和日志位置，不把 Cluster 网络能力交给交易状态。 */
    private OwnerLogContext logContext;
    /** 本 Owner 响应所属角色代次，在角色切换动作内更新。 */
    private long ownerEpoch;

    private final CoreMessageFlyweightDecoder messageDecoder;
    private final CommandFingerprint.Calculator fingerprints = new CommandFingerprint.Calculator();

    private Cluster cluster;

    private final List<ClusterRoleChangeListener> roleChangeListeners;

    /** Spring 装配主入口，接收统一绑定的拓扑与配置。 */
    @Autowired
    public AeronTradingClusterService(
            ClusterTopology topology,
            ClusterServiceEgress egress,
            TradingCpuAffinity affinity,
            LaneTopology laneTopology,
            CoreRuntimeConfiguration configuration,
            RealtimeConfiguration realtimeConfiguration,
            @Autowired(required = false) List<ClusterRoleChangeListener> roleChangeListeners) {
        this(topology.productLine(), egress, affinity, laneTopology, configuration, realtimeConfiguration, roleChangeListeners);
    }

    public AeronTradingClusterService(
            ClusterTopology topology,
            ClusterServiceEgress egress,
            TradingCpuAffinity affinity,
            LaneTopology laneTopology,
            CoreRuntimeConfiguration configuration,
            RealtimeConfiguration realtimeConfiguration) {
        this(topology.productLine(), egress, affinity, laneTopology, configuration, realtimeConfiguration, null);
    }

    /** 独立工具与测试的完整装配入口，与 Spring 使用同一组初始化逻辑。 */
    public AeronTradingClusterService(ProductLine productLine, ClusterServiceEgress egress,
            TradingCpuAffinity affinity, LaneTopology laneTopology, CoreRuntimeConfiguration configuration,
            RealtimeConfiguration realtimeConfiguration) {
        this(productLine, egress, affinity, laneTopology, configuration, realtimeConfiguration, null);
    }

    public AeronTradingClusterService(ProductLine productLine, ClusterServiceEgress egress,
            TradingCpuAffinity affinity, LaneTopology laneTopology, CoreRuntimeConfiguration configuration,
            RealtimeConfiguration realtimeConfiguration,
            List<ClusterRoleChangeListener> roleChangeListeners) {
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
        this.roleChangeListeners = roleChangeListeners != null ? List.copyOf(roleChangeListeners) : List.of();
    }

    /** Cluster 启动入口：保存 Cluster 句柄、启动响应出口，读取可用快照后启动 Owner。 */
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
        if (!roleChangeListeners.isEmpty()) {
            for (ClusterRoleChangeListener listener : roleChangeListeners) {
                try {
                    listener.onRoleChanged(cluster.role());
                } catch (Throwable t) {
                    LOG.warn("Cluster role change listener failed on start: {}", t.getMessage());
                }
            }
        }
    }

    /** 接收 Cluster 已提交的客户端消息，解码后连同日志顺序信息交给 Owner，再尝试发送已就绪响应。 */
    @Override
    public void onSessionMessage(ClientSession session, long timestamp, DirectBuffer buffer,
            int offset, int length, Header header) {
        // TransportSession.close() 只记下请求， 这里在 Cluster 回调线程中真正关闭 Aeron ClientSession，再处理新消息。
        egress.flushSessionClosures();

        CoreMessage command;
        try {
            command = messageDecoder.decodeBorrowed(buffer, offset, length);
        } catch (IllegalArgumentException invalid) {
            // 非法帧直接丢弃，不进入 Owner 命令队列。
            return;
        }

        // 只有业务命令才计算指纹，用于命令幂等/冲突识别。
        try {
            enqueue(command, session == null ? ClusterServiceEgress.NO_SESSION_ID : egress.transportSession(session).id(),
                    timestamp, header.position(),
                    command.header().kind() == WireMessageKind.COMMAND ? fingerprints.ofBorrowed(command) : null);
        } catch (RuntimeException | Error fail) {
            command.releaseDecoderSlot();
            throw fail;
        }

    }

    /** 交接一条已复制业务命令，保留会话、业务时间、日志位置和指纹，并在当前线程立即推进流水线准入。 */
    public void enqueue(CoreMessage command, long sessionId, long timestamp, long position,
                        CommandFingerprint fingerprint) {
        checkFailure();
        if (input.offer(command, sessionId, timestamp, position, fingerprint)) {
            if (started) {
                pollCommands();
            }
            return;
        }
        // 输入环满时，先在当前线程尝试推进排空已有就绪命令。
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

    /** 在当前线程推进一次 Core 命令生命周期及完成信号处理。 */
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

    /** 推进 Core 命令生命周期及完成信号，并消费 egress 队列实际发送响应。 */
    public int drainResponses() {
        int work = 0;
        if (started) {
            work = pollCommands();
        }
        return work + egress.drainResponses();
    }

    /** 新客户端会话建立时检查 Owner 是否发生故障，创建响应传输会话，并处理之前挂起的会话关闭请求。 */
    @Override
    public void onSessionOpen(ClientSession session, long timestamp) {
        checkFailure();
        if (session != null) egress.transportSession(session);
        egress.flushSessionClosures();
    }

    /** 客户端会话关闭时移除其响应队列和传输句柄，然后检查 Owner 故障。 */
    @Override
    public void onSessionClose(ClientSession session, long timestamp, CloseReason reason) {
        egress.closeSession(session);
        checkFailure();
    }

    /** 处理 Cluster 定时器回调。当前服务不使用旧日志定时器驱动交易状态，只检查 Owner 故障。 */
    @Override
    public void onTimerEvent(long correlationId, long timestamp) {
        checkFailure();
    }

    /** Cluster 空闲轮询时推进 Owner 命令生命周期与完成信号，并发送已就绪响应。 */
    @Override
    public int doBackgroundWork(long nowNs) {
        checkFailure();
        try {
            return drainResponses();
        } catch (RuntimeException fatal) {
            throw new AgentTerminationException(fatal);
        }
    }

    /** 在当前线程同步执行角色切换：先推进完成前面所有积压命令，再更新角色和响应 epoch。 */
    @Override
    public void onRoleChange(Cluster.Role role) {
        onRoleChange(role, egress.onRoleChange(role));
    }

    /** 允许显式指定代次的角色切换，供精准测试与受控边界调用。 */
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
        if (!roleChangeListeners.isEmpty()) {
            for (ClusterRoleChangeListener listener : roleChangeListeners) {
                try {
                    listener.onRoleChanged(role);
                } catch (Throwable t) {
                    LOG.warn("Cluster role change listener failed: {}", t.getMessage());
                }
            }
        }
    }

    /** 收到新的领导任期事件时更新响应出口使用的 term ID，并检查 Owner 是否正常。 */
    @Override
    public void onNewLeadershipTermEvent(long termId, long logPosition, long timestamp,
            long termBaseLogPosition, int leaderMemberId, int logSessionId,
            TimeUnit timeUnit, int appVersion) {
        egress.leadershipTerm(termId);
        checkFailure();
    }

    /** 将 Owner 捕获的快照 section 分块写入 Cluster 快照 Publication。 */
    @Override
    public void onTakeSnapshot(ExclusivePublication publication) {
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

    /** 固定请求的日志位置与时间，并由 Core Owner 同步完成前缀命令后返回合并快照。 */
    public byte[] captureSnapshot(long position, long timestamp) {
        checkFailure();
        if (logContext != null) {
            logContext.position(position);
            logContext.timestamp(timestamp);
        }
        return coreOwner.captureSnapshot(Math.max(1, position));
    }

    /** 按相同语义同步捕获快照，返回分块结果。 */
    public SectionedCoreSnapshotCodec.SectionedSnapshot captureSnapshotSections(long position, long timestamp) {
        checkFailure();
        if (logContext != null) {
            logContext.position(position);
            logContext.timestamp(timestamp);
        }
        return coreOwner.captureSnapshotSections(Math.max(1, position));
    }

    /** Cluster 服务终止时停止 Owner、清理响应出口，并释放本类持有的 Cluster 引用与 CPU 绑定。 */
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

    /** 读取保存的首个故障，存在时包装成 Agent 终止异常交给调用方。 */
    public void checkFailure() {
        if (failure != null) throw new AgentTerminationException(failure);
    }

    /** 把终态及当前 Owner epoch 交给已有 egress，响应缓冲继续按该出口的租约规则管理。 */
    private void publishResponse(long sessionId, CoreMessageHeader header, CoreResponse response,
                                 long committedSequence) {
        egress.publishResponse(coreOwner, sessionId, header, response, committedSequence, ownerEpoch);
    }

    private void awaitProgress(long deadline) {
        checkFailure();
        if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) {
            throw new AgentTerminationException("cluster command progress timed out");
        }
        if (started) {
            pollCommands();
        }
        egress.drainResponses();
        if (cluster != null && cluster.idleStrategy() != null) {
            cluster.idleStrategy().idle();
            return;
        }
        Thread.onSpinWait();
    }

    /** 读取 Aeron 提供的快照片段并交给恢复缓冲区。 */
    private com.surprising.aeron.service.snapshot.SectionedCoreSnapshotRecovery readSnapshot(Image image, Cluster cluster) {
        if (image == null) return null;
        var recovery = new com.surprising.aeron.service.snapshot.SectionedCoreSnapshotRecovery(maximumSnapshotBytes);
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

    /** 按 Publication 能接收的最大负载切分快照，背压时重试同一段，不能跳过未发送的字节。 */
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
