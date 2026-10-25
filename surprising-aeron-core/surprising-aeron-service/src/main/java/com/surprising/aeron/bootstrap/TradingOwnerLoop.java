package com.surprising.aeron.bootstrap;

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
import com.surprising.aeron.service.core.TradingCoreOwner;
import com.surprising.aeron.service.snapshot.SectionedCoreSnapshotCodec;
import com.surprising.aeron.support.egress.ClusterServiceEgress;
import com.surprising.aeron.support.ingress.OwnerIngressRing;
import com.surprising.product.api.ProductLine;
import io.aeron.cluster.service.Cluster;
import org.agrona.concurrent.AgentTerminationException;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static com.surprising.aeron.support.egress.ClusterServiceEgress.NO_SESSION_ID;

/**
 * 启动步骤 7–9、交易步骤 1：内联于 Aeron Cluster 服务线程推进交易命令生命周期，管理快照、角色切换和退出动作。
 * 业务状态由 TradingCoreOwner 推进，不再启动独立后台 Owner 线程，消除跨核交接与缓存颠簸。
 */
@Component
public final class TradingOwnerLoop {
    /** 积压诊断日志。 */
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(TradingOwnerLoop.class);
    /** 普通输入等待使用的默认三十秒预算。 */
    private static final long DEADLINE_NS = 30_000_000_000L;
    /** 与 Core Owner 共用的输入环，内联入队与排空。 */
    private final OwnerIngressRing input;
    /** Cluster 服务线程发送响应的出口，Owner 把终态交入队列。 */
    private final ClusterServiceEgress egress;
    /** 持有并推进实际交易状态的对象。 */
    private final TradingCoreOwner coreOwner;
    /** 跨调用可见的首个故障，后续调用据此终止。 */
    private volatile Throwable failure;
    /** 停止标记。 */
    private volatile boolean stopping;
    /** 是否已完成初始化启动。 */
    private volatile boolean started;
    /** 不可变交易线程 CPU 配置，构造时要求非空。 */
    private final TradingCpuAffinity affinityConfiguration;
    /** 当前 CPU 绑定租约，在退出时恢复并清空。 */
    private TradingCpuAffinity.Binding cpuAffinity;
    /** 仅供读取元数据及等待策略的 Cluster 句柄。 */
    private Cluster cluster;
    /** 复制给 Owner 的角色、业务时间和日志位置，不把 Cluster 网络能力交给交易状态。 */
    private OwnerLogContext logContext;
    /** 本 Owner 响应所属角色代次，在角色切换动作内更新。 */
    private long ownerEpoch;

    /**
     * 使用集群拓扑中的产品线创建 Owner，默认关闭交易线程 CPU 亲和性。
     * @param topology 本节点产品线和集群成员拓扑
     * @param egress Owner 终态要交入的已有响应出口
     */
    public TradingOwnerLoop(ClusterTopology topology, ClusterServiceEgress egress) {
        this(topology.productLine(), egress);
    }

    /**
     * Spring 装配入口，接收统一绑定的交易拓扑与 CPU 配置。
     * @param topology 本节点产品线和集群成员拓扑
     * @param egress Owner 终态要交入的已有响应出口
     * @param affinity 启动前已校验的 CPU 亲和性配置
     * @param laneTopology 统一配置绑定后创建的首次启动拓扑
     */
    @Autowired
    public TradingOwnerLoop(ClusterTopology topology, ClusterServiceEgress egress,
            TradingCpuAffinity affinity, LaneTopology laneTopology, CoreRuntimeConfiguration configuration,
            RealtimeConfiguration realtimeConfiguration) {
        this(topology.productLine(), egress, affinity, laneTopology, configuration, realtimeConfiguration);
    }

    /**
     * 测试或显式装配入口：指定产品线与响应出口，默认不绑定 CPU。
     * @param productLine 本 Owner 唯一所属产品线
     * @param egress Owner 终态要交入的已有响应出口
     */
    public TradingOwnerLoop(ProductLine productLine, ClusterServiceEgress egress) {
        this(productLine, egress, TradingCpuAffinity.disabled());
    }

    /**
     * 保存 CPU 配置和响应出口，创建实际 Core Owner 并复用它的输入环；不在构造时启动线程。
     * @param productLine 本 Owner 唯一所属产品线
     * @param egress Owner 终态要交入的已有响应出口
     * @param affinity 启动前已校验的 CPU 亲和性配置
     */
    public TradingOwnerLoop(ProductLine productLine, ClusterServiceEgress egress,
            TradingCpuAffinity affinity) {
        this(productLine, egress, affinity,
                CoreConfiguration.standaloneLaneTopology());
    }

    /**
     * 保存启动边界传入的配置，交给 Core Owner 创建共享同一拓扑的运行时。
     * @param productLine 本 Owner 唯一处理的产品线
     * @param egress 已装配的响应出口
     * @param affinity 工作线程的 CPU 配置
     * @param laneTopology 首次启动使用的交易拓扑
     */
    public TradingOwnerLoop(ProductLine productLine, ClusterServiceEgress egress,
            TradingCpuAffinity affinity, LaneTopology laneTopology) {
        this(productLine, egress, affinity, laneTopology, CoreRuntimeConfiguration.standalone());
    }

    public TradingOwnerLoop(ProductLine productLine, ClusterServiceEgress egress,
            TradingCpuAffinity affinity, LaneTopology laneTopology, CoreRuntimeConfiguration configuration) {
        this(productLine, egress, affinity, laneTopology, configuration, RealtimeConfiguration.standalone());
    }

    public TradingOwnerLoop(ProductLine productLine, ClusterServiceEgress egress, TradingCpuAffinity affinity,
            LaneTopology laneTopology, CoreRuntimeConfiguration configuration, RealtimeConfiguration realtimeConfiguration) {
        this.affinityConfiguration = java.util.Objects.requireNonNull(affinity);
        this.egress = egress;
        coreOwner = new TradingCoreOwner(productLine, this::publishResponse, laneTopology, configuration, realtimeConfiguration);
        input = coreOwner.ingress();
    }

    /**
     * 复制 Cluster 元数据，在当前服务线程直接建立运行时、可选恢复、激活工作资源并绑定 CPU。
     * @param cluster 回调侧 Cluster，用来取得初始元数据与等待策略
     * @param restored 可空恢复缓冲，有值时在 Owner 激活资源前恢复
     */
    public void start(Cluster cluster, SectionedCoreSnapshotCodec.RecoveryBuffer restored) {
        this.cluster = cluster;
        failure = null;
        stopping = false;
        ownerEpoch = 0;
        logContext = new OwnerLogContext(cluster.role(), cluster.time(), cluster.logPosition(),
                new BusySpinIdleStrategy());
        coreOwner.start(logContext);
        if (restored != null) {
            coreOwner.restoreSnapshot(restored);
        }
        // 先启动所有工作资源，再收窄当前服务线程 CPU mask，避免 Ledger 等线程继承单核限制。
        coreOwner.state().activate(affinityConfiguration);
        cpuAffinity = affinityConfiguration.owner();
        started = true;
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
        if (input.offer(command, sessionId, timestamp, position, null, fingerprint)) {
            if (started) {
                pollCommands();
            }
            return;
        }
        long startedNs = System.nanoTime();
        long deadline = startedNs + DEADLINE_NS;
        boolean reported = false;
        while (true) {
            awaitProgress(deadline);
            checkFailure();
            if (input.offer(command, sessionId, timestamp, position, null, fingerprint)) {
                if (started) {
                    pollCommands();
                }
                return;
            }
            if (!reported && System.nanoTime() - startedNs >= java.util.concurrent.TimeUnit.SECONDS.toNanos(5)) {
                LOG.error("Core owner input has made no capacity for 5 seconds queued={} bytes={} ownerFailure={}",
                        input.size(), input.retainedBytes(), failure);
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
     * 推进完成信号并由 Cluster 回调线程消费 egress 队列，实际发送 Owner 已交出的响应。
     */
    public int drainResponses() {
        if (started) {
            pollCommands();
        }
        return egress.drainResponses(coreOwner);
    }

    /**
     * 在当前线程同步执行角色切换：先推进完成前面所有积压命令，再更新角色和响应 epoch。
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
     * 先同步完成已有命令前缀，再固定请求的日志位置与时间，返回合并成单数组的快照。
     * @param position 来自复制日志的位置
     * @param timestamp 来自复制日志的业务时间
     */
    public byte[] captureSnapshot(long position, long timestamp) {
        checkFailure();
        if (started) {
            coreOwner.finishCommands();
        }
        if (logContext != null) {
            logContext.position(position);
            logContext.timestamp(timestamp);
        }
        return coreOwner.captureSnapshot(Math.max(1, position));
    }

    /**
     * 按相同语义同步捕获快照，返回分块结果，避免必须合成完整字节数组。
     * @param position 来自复制日志的位置
     * @param timestamp 来自复制日志的业务时间
     */
    public SectionedCoreSnapshotCodec.SectionedSnapshot captureSnapshotSections(long position, long timestamp) {
        checkFailure();
        if (started) {
            coreOwner.finishCommands();
        }
        if (logContext != null) {
            logContext.position(position);
            logContext.timestamp(timestamp);
        }
        return coreOwner.captureSnapshotSections(Math.max(1, position));
    }

    /**
     * 在当前服务线程同步关闭 Core Owner 并恢复 CPU 绑定。
     */
    public void terminate() {
        stopping = true;
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
     * 等待时检查故障、中断和截止时间，推进命令并持续排出响应，使用 Cluster 等待策略退避。
     */
    private void awaitProgress(long deadline) {
        checkFailure();
        if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) {
            throw new AgentTerminationException("trading owner boundary or ingress deadline");
        }
        if (started) {
            pollCommands();
        }
        egress.drainResponses(coreOwner);
        if (cluster != null && cluster.idleStrategy() != null) {
            cluster.idleStrategy().idle();
        } else {
            Thread.onSpinWait();
        }
    }
}
