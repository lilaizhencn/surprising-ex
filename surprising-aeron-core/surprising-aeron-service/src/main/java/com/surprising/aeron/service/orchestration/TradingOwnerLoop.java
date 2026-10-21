package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.service.cluster.ClusterTopology;
import com.surprising.aeron.service.config.TradingCpuAffinity;
import com.surprising.aeron.service.orchestration.cluster.OwnerIdleStrategy;
import com.surprising.aeron.service.orchestration.egress.ClusterServiceEgress;
import com.surprising.aeron.service.orchestration.ingress.OwnerIngressRing;
import com.surprising.aeron.service.orchestration.snapshot.SectionedCoreSnapshotCodec;
import com.surprising.product.api.ProductLine;
import io.aeron.cluster.service.Cluster;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.agrona.concurrent.AgentTerminationException;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static com.surprising.aeron.service.orchestration.egress.ClusterServiceEgress.NO_SESSION_ID;

/**
 * 启动步骤 7–9、交易步骤 1：创建 trading-owner 线程，通过唯一输入环交接 Cluster 命令，并管理快照、角色切换和退出动作。业务状态由 TradingCoreOwner 推进；本类负责线程和跨线程完成边界。
 */
@Component
public final class TradingOwnerLoop implements Runnable {
    /** Owner 线程交接和积压诊断日志。 */
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(TradingOwnerLoop.class);
    /** 普通输入、动作等待和线程退出使用的默认三十秒预算。 */
    private static final long DEADLINE_NS = 30_000_000_000L;
    /** 角色切换等待预算的 JVM 属性名。 */
    private static final String ROLE_CHANGE_TIMEOUT_SECONDS_PROPERTY =
            "surprising.aeron.owner-role-change-timeout-seconds";
    /** 未配置角色切换预算时使用的六百秒。 */
    private static final long DEFAULT_ROLE_CHANGE_TIMEOUT_SECONDS = 600;
    /** 类加载时解析得到的角色切换等待纳秒预算。 */
    private static final long ROLE_CHANGE_DEADLINE_NS = configuredRoleChangeDeadlineNanos(
            System.getProperty(ROLE_CHANGE_TIMEOUT_SECONDS_PROPERTY));
    /** 与 Core Owner 共用的唯一输入环，回调线程生产、Owner 线程消费。 */
    private final OwnerIngressRing input;
    /** Cluster 回调线程发送响应的出口，Owner 只把终态交入队列。 */
    private final ClusterServiceEgress egress;
    /** 在 Owner 线程持有并推进实际交易状态的对象。 */
    private final TradingCoreOwner coreOwner;
    /** 以现有输入和完成条件为依据的 Owner 等待器。 */
    private final OwnerIdleStrategy ownerIdle = new OwnerIdleStrategy(this::ownerWorkAvailable);
    /** 跨线程可见的首个 Owner 故障，后续调用据此终止。 */
    private volatile Throwable failure;
    /** Owner 主循环停止标记，启动前重置、退出动作在 Owner 内设置。 */
    private boolean stopping;
    /** 本实例创建的 trading-owner 线程，退出时必须等待它结束。 */
    private Thread ownerThread;
    /** 不可变交易线程 CPU 配置，构造时要求非空。 */
    private final TradingCpuAffinity affinityConfiguration;
    /** Owner 线程当前 CPU 绑定租约，在线程最终退出时恢复并清空。 */
    private TradingCpuAffinity.Binding cpuAffinity;
    /** 仅供回调侧读取元数据及等待策略的 Cluster 句柄。 */
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
     * Spring 装配入口，取拓扑产品线并传入已经校验的 CPU 配置。
     * @param topology 本节点产品线和集群成员拓扑
     * @param egress Owner 终态要交入的已有响应出口
     * @param affinity 启动前已校验的 CPU 亲和性配置
     */
    @Autowired
    public TradingOwnerLoop(ClusterTopology topology, ClusterServiceEgress egress,
            TradingCpuAffinity affinity) {
        this(topology.productLine(), egress, affinity);
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
        this.affinityConfiguration = java.util.Objects.requireNonNull(affinity);
        this.egress = egress;
        coreOwner = new TradingCoreOwner(productLine, this::publishResponse);
        input = coreOwner.ingress();
    }

    /**
     * 复制 Cluster 元数据，启动 Owner 线程后提交启动动作；动作依次建立运行时、可选恢复、接入完成通知、激活工作资源，最后绑定 Owner CPU，防止工作线程继承收窄后的 mask。
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
        ownerThread = new Thread(this, "trading-owner-" + cluster.memberId());
        ownerThread.start();
        boundary(() -> {
            coreOwner.start(logContext);
            if (restored != null) coreOwner.restoreSnapshot(restored);
            coreOwner.ownerCompletionSignal(ownerIdle::signal);
            // 先启动所有工作资源，再收窄 Owner CPU mask，避免 Ledger 等线程继承 Owner 的单核限制。
            coreOwner.state().activate(affinityConfiguration);
            cpuAffinity = affinityConfiguration.owner();
            return null;
        }, false);
    }

    /**
     * 交接一条已复制业务命令，保留会话、业务时间、日志位置和指纹；本入口不单独记录入队纳秒时间。
     * @param command 借给 Owner 的已复制命令；框架动作入队时为 null
     * @param sessionId 原会话 ID；无网络目标时使用 NO_SESSION_ID
     * @param timestamp 来自复制日志的业务时间
     * @param position 来自复制日志的位置
     * @param fingerprint 已解码请求的内容指纹，供原幂等边界使用
     */
    public void enqueue(CoreMessage command, long sessionId, long timestamp, long position,
                        CommandFingerprint fingerprint) {
        enqueue(command, sessionId, timestamp, position, 0L, fingerprint);
    }

    /**
     * 在普通命令交接中额外保留已有入队纳秒观测值，继续使用默认输入等待期限。
     * @param command 借给 Owner 的已复制命令；框架动作入队时为 null
     * @param sessionId 原会话 ID；无网络目标时使用 NO_SESSION_ID
     * @param timestamp 来自复制日志的业务时间
     * @param position 来自复制日志的位置
     * @param enqueuedNanos 调用方已有的入队观测时间，零表示未记录
     * @param fingerprint 已解码请求的内容指纹，供原幂等边界使用
     */
    void enqueue(CoreMessage command, long sessionId, long timestamp, long position,
                 long enqueuedNanos, CommandFingerprint fingerprint) {
        enqueue(command, sessionId, timestamp, position, null, enqueuedNanos, fingerprint, DEADLINE_NS);
    }

    /**
     * 由 Cluster 回调线程消费 egress 队列，实际发送 Owner 已交出的响应。
     */
    public int drainResponses() {
        return egress.drainResponses(coreOwner);
    }

    /**
     * 将角色切换作为输入环动作边界；先完成前面的命令，再在 Owner 上更新角色和响应 epoch，并通知实时边界。
     * @param role 边界确定的新 Cluster 角色
     * @param nextEpoch 新角色代次，响应出口用它区分角色变化
     */
    public void onRoleChange(Cluster.Role role, long nextEpoch) {
        boundary(() -> {
            logContext.role = role;
            ownerEpoch = nextEpoch;
            coreOwner.roleChange(role);
            return null;
        }, true, ROLE_CHANGE_DEADLINE_NS);
    }

    /**
     * 先完成动作边界前的命令，再固定请求的日志位置与时间，返回合并成单数组的快照。
     * @param position 来自复制日志的位置
     * @param timestamp 来自复制日志的业务时间
     */
    public byte[] captureSnapshot(long position, long timestamp) {
        return boundary(() -> {
            logContext.position = position;
            logContext.timestamp = timestamp;
            return coreOwner.captureSnapshot(Math.max(1, position));
        }, true);
    }

    /**
     * 按相同边界捕获快照，但返回分块结果，避免必须合成完整字节数组。
     * @param position 来自复制日志的位置
     * @param timestamp 来自复制日志的业务时间
     */
    public SectionedCoreSnapshotCodec.SectionedSnapshot captureSnapshotSections(long position, long timestamp) {
        return boundary(() -> {
            logContext.position = position;
            logContext.timestamp = timestamp;
            return coreOwner.captureSnapshotSections(Math.max(1, position));
        }, true);
    }

    /**
     * 没有线程则直接返回；正常线程通过动作边界完成前缀并停止。确认线程退出后才丢弃剩余输入；等待超时则中断并报错，不在活线程仍可能访问时释放输入。
     */
    public void terminate() {
        if (ownerThread == null) return;
        try {
            if (failure == null && ownerThread.isAlive()) {
                boundary(() -> {
                    stopping = true;
                    return null;
                }, true);
            }
        } finally {
            try {
                ownerThread.join(30_000);
            } catch (/* 等待 Owner 退出时收到的中断，恢复调用线程中断标记 */ InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            if (ownerThread.isAlive()) {
                ownerThread.interrupt();
                throw new IllegalStateException("trading owner did not terminate");
            }
            // 确认 Owner 已退出后由回调生产者清理输入，覆盖最后一次入队与 Owner 失败并发的情况。
            input.discard();
        }
    }

    /**
     * 读取 Owner 保存的首个故障，存在时包装成 Agent 终止异常交给调用方。
     */
    public void checkFailure() {
        if (failure != null) throw new AgentTerminationException(failure);
    }

    /**
     * Owner 线程循环：优先执行输入环动作，启动动作成功后才推进业务；按实际进展退避。记录首个故障，最终关闭 Core Owner 并恢复 CPU 绑定。
     */
    @Override
    public void run() {
        ownerIdle.bindOwner();
        // 启动动作是否已正常返回，未完成时不推进业务
        boolean initialized = false;
        try {
            while (!stopping) {
                // 本轮动作或业务的实际进展指标，供等待器退避
                int work = 0;
                // 输入环当前借出的队首，动作执行后在 finally 移除
                var next = input.first();
                if (next != null && next.action() != null) {
                    try {
                        next.action().run();
                        initialized = true;
                    } finally { input.remove(); }
                    work++;
                } else if (initialized && !stopping && coreOwner.ownerWorkAvailable()) {
                    work += coreOwner.pollCommands();
                }
                ownerIdle.idle(work, coreOwner.pendingCommandCount() != 0);
            }
        } catch (/* 线程执行、资源关闭或动作失败的故障；只把首个故障保存为根因 */ Throwable fatal) {
            failure = fatal;
        } finally {
            try {
                coreOwner.terminate();
            } catch (/* 线程执行、资源关闭或动作失败的故障；只把首个故障保存为根因 */ Throwable fatal) {
                if (failure == null) failure = fatal;
            } finally {
                if (cpuAffinity != null) {
                    try { cpuAffinity.close(); }
                    catch (/* 线程执行、资源关闭或动作失败的故障；只把首个故障保存为根因 */ Throwable fatal) { if (failure == null) failure = fatal; }
                    cpuAffinity = null;
                }
            }
        }
    }

    /**
     * 检查线程故障后尝试写输入环并通知 Owner；环满时一边推动响应出口一边等待空位，五秒后只记录一次积压诊断。成功入队不表示业务已经完成。
     * @param command 借给 Owner 的已复制命令；框架动作入队时为 null
     * @param sessionId 原会话 ID；无网络目标时使用 NO_SESSION_ID
     * @param timestamp 来自复制日志的业务时间
     * @param position 来自复制日志的位置
     * @param action 要在 Owner 线程执行的框架动作
     * @param enqueuedNanos 调用方已有的入队观测时间，零表示未记录
     * @param fingerprint 已解码请求的内容指纹，供原幂等边界使用
     * @param timeoutNanos 当前入队或动作等待阶段的纳秒预算
     */
    private void enqueue(CoreMessage command, long sessionId, long timestamp, long position,
                         Runnable action, long enqueuedNanos, CommandFingerprint fingerprint, long timeoutNanos) {
        checkFailure();
        if (input.offer(command, sessionId, timestamp, position, action, enqueuedNanos, fingerprint)) {
            ownerIdle.signal();
            return;
        }
        // 开始等待输入空位的单调纳秒时间
        long started = System.nanoTime();
        // 当前阶段单独计算的等待截止时间
        long deadline = started + timeoutNanos;
        // 是否已记录一次超过五秒的输入环积压诊断
        boolean reported = false;
        while (true) {
            awaitProgress(deadline);
            checkFailure();
            if (input.offer(command, sessionId, timestamp, position, action, enqueuedNanos, fingerprint)) {
                ownerIdle.signal();
                return;
            }
            if (!reported && System.nanoTime() - started >= java.util.concurrent.TimeUnit.SECONDS.toNanos(5)) {
                LOG.error("Core owner input has made no capacity for 5 seconds queued={} bytes={} ownerFailure={}",
                        input.size(), input.retainedBytes(), failure);
                reported = true;
            }
        }
    }

    /**
     * 框架动作可直接推进；业务命令则交 Core Owner 判断是否有输入、完成通知或本地续步可做。
     */
    private boolean ownerWorkAvailable() {
        // 只借看当前队首，动作执行与移除仍由主循环承担
        var next = input.first();
        return next != null && next.action() != null || coreOwner.ownerWorkAvailable();
    }

    /**
     * 使用默认等待预算把一个动作交给 Owner，并等待其完成；可要求动作前先完成业务前缀。
     * @param <T> 动作返回值类型，不改变业务状态模型
     * @param action 要在 Owner 线程执行的框架动作
     * @param finish 是否在动作执行前先完成已有业务前缀
     */
    private <T> T boundary(Supplier<T> action, boolean finish) {
        return boundary(action, finish, DEADLINE_NS);
    }

    /**
     * 通过输入环和 Future 执行动作；先等入队，再重新计算等待动作完成的期限。动作异常既通知等待方也使 Owner 线程失败，两个等待阶段各有自己的预算。
     * @param <T> 动作返回值类型，不改变业务状态模型
     * @param action 要在 Owner 线程执行的框架动作
     * @param finish 是否在动作执行前先完成已有业务前缀
     * @param timeoutNanos 当前入队或动作等待阶段的纳秒预算
     */
    private <T> T boundary(Supplier<T> action, boolean finish, long timeoutNanos) {
        // 把 Owner 动作结果或异常交回等待方的 Future
        var completion = new CompletableFuture<T>();
        enqueue(null, NO_SESSION_ID, 0, 0, () -> {
            try {
                if (finish) coreOwner.finishCommands();
                completion.complete(action.get());
            } catch (/* 动作或前缀完成失败，先通知等待方，再抛给 Owner 主循环 */ Throwable fatal) {
                completion.completeExceptionally(fatal);
                throw fatal;
            }
        }, 0, null, timeoutNanos);
        // 当前阶段单独计算的等待截止时间
        long deadline = System.nanoTime() + timeoutNanos;
        while (!completion.isDone()) awaitProgress(deadline);
        return completion.join();
    }

    /**
     * 解析角色切换秒数，空值默认十分钟，允许 30 至 1800 秒；数字格式错误保留原解析异常。
     * @param configuredSeconds 可空角色切换秒数字符串，允许首尾空白
     */
    static long configuredRoleChangeDeadlineNanos(String configuredSeconds) {
        // 解析并校验后的角色切换秒数
        long seconds = configuredSeconds == null || configuredSeconds.isBlank()
                ? DEFAULT_ROLE_CHANGE_TIMEOUT_SECONDS
                : Long.parseLong(configuredSeconds.trim());
        if (seconds < 30 || seconds > 1_800) {
            throw new IllegalArgumentException(ROLE_CHANGE_TIMEOUT_SECONDS_PROPERTY + " must be in [30,1800]");
        }
        return java.util.concurrent.TimeUnit.SECONDS.toNanos(seconds);
    }

    /**
     * 把终态及当前 Owner epoch 交给已有 egress，响应缓冲继续按该出口的租约规则管理。
     * @param sessionId 原会话 ID；无网络目标时使用 NO_SESSION_ID
     * @param header 需保留至响应编码完成的头部租约
     * @param response 已完成的业务响应及其缓冲租约
     * @param committedSequence 该终态的已提交 Core 序号
     */
    private void publishResponse(long sessionId, CoreMessageHeader header, CoreResponse response,
                                 long committedSequence) {
        egress.publishResponse(coreOwner, sessionId, header, response, committedSequence, ownerEpoch);
    }

    /**
     * 等待时检查线程故障、中断和截止时间，持续排出响应，并用 Cluster 回调侧的等待策略退避。
     * @param deadline 回调侧单调纳秒时钟上的等待截止时间
     */
    private void awaitProgress(long deadline) {
        checkFailure();
        if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) {
            throw new AgentTerminationException("trading owner boundary or ingress deadline");
        }
        egress.drainResponses(coreOwner);
        cluster.idleStrategy().idle();
    }

}
