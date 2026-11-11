package com.surprising.aeron.bootstrap.account;

import com.surprising.aeron.service.order.*;

import com.surprising.aeron.config.TradingCpuAffinity;
import com.surprising.aeron.service.account.AccountLaneCommand;
import com.surprising.aeron.service.account.AccountPartition;
import com.surprising.aeron.service.settlement.TradeSettlement;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

/** 启动与账户执行步骤：为一个账户 Lane 建立实际工作线程，合并单 Owner 控制邮箱、独立准入邮箱及各 Matcher 分片独占生产的结算环。 */
@Slf4j
public final class SettlementLaneWorker implements AutoCloseable {
    private static final int BUSY_SPIN_YIELD_THRESHOLD = 1024;

    /** Owner 发布的请求轮次，是否重复请求由外层交接协议保证。 */
    private volatile long requestedHandoff;
    private volatile long completedHandoff;
    /** Owner 归还临时账户所有权后发布的恢复轮次。 */
    private volatile long resumedHandoff;

    /** 账户线程保存故障后调用的发布器，通知运行时并唤醒 Owner。 */
    private final java.util.function.Consumer<Throwable> failurePublisher;
    /** 单 Owner 到本账户线程的普通控制任务引用邮箱。 */
    private final AccountLaneCommand[] commands;
    /** 独立的单 Owner 准入邮箱，避免被普通队首尚未就绪的撮合输入挡住。 */
    private final AccountLaneCommand[] admissionCommands;
    /** 每个 Matcher 分片独占生产的结算环，由本账户线程合并消费。 */
    private final MatcherSettlementRing[] matcherSettlementRings;
    private final int indexMask;
    private final int admissionIndexMask;
    /** 本工作线程唯一拥有的账户 Lane。 */
    private final AccountPartition lane;
    /** 构造时启动的实际 LaneThread，始终带有所属账户引用。借出实际账户工作线程引用，供生命周期及诊断使用。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final Thread thread;
    /** Owner 已发布普通任务的上界，以 volatile 游标交接可见性。 */
    private final PaddedSequence producerSequence = new PaddedSequence();
    private final PaddedSequence consumerSequence = new PaddedSequence();
    private long cachedConsumerSequence;
    /** Owner 已发布准入任务的上界。 */
    private final PaddedSequence admissionProducerSequence = new PaddedSequence();
    private final PaddedSequence admissionConsumerSequence = new PaddedSequence();
    private long cachedAdmissionConsumerSequence;
    /** 线程已完成初始绑定或已发布启动故障的可见标记，供构造方等待。 */
    private volatile boolean started;
    private volatile boolean running = true;
    /** 账户线程已保存的原 Throwable，健康检查及关闭按原类型传播。读取已经发布的可空账户线程故障。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private volatile Throwable failure;
    /** 创建一个账户工作线程，默认一个 Matcher 结算环，故障回调为空操作，CPU 亲和使用禁用配置。 */
    public SettlementLaneWorker(String role, AccountPartition lane, int requestedCapacity) {
        this(role, lane, requestedCapacity, ignored -> { });
    }

    /** 使用给定故障发布器创建账户工作线程，默认一个 Matcher 结算环。 */
    public SettlementLaneWorker(String role, AccountPartition lane, int requestedCapacity,
                         java.util.function.Consumer<Throwable> failurePublisher) {
        this(role, lane, requestedCapacity, failurePublisher, 1);
    }

    /** 按 Matcher 分片数建立结算环，CPU 亲和默认禁用。 */
    public SettlementLaneWorker(String role, AccountPartition lane, int requestedCapacity,
                         java.util.function.Consumer<Throwable> failurePublisher,
                         int matcherShardCount) {
        this(role, lane, requestedCapacity, failurePublisher, matcherShardCount,
                TradingCpuAffinity.disabled());
    }

    /** 校验输入，将邮箱容量向上取二次幂，读取等待配置，建立各来源邮箱并绑定 LaneThread 后立即启动。等待线程完成所有权绑定或发布启动故障，再把原故障传播给创建方。 */
    public SettlementLaneWorker(String role, AccountPartition lane, int requestedCapacity,
            java.util.function.Consumer<Throwable> failurePublisher, int matcherShardCount,
            TradingCpuAffinity affinity) {
        this.failurePublisher = java.util.Objects.requireNonNull(failurePublisher);
        if (role == null || role.isBlank() || lane == null || requestedCapacity <= 0
                || matcherShardCount <= 0) {
            throw new IllegalArgumentException("invalid settlement lane worker");
        }
        int capacity = 1;
        while (capacity < requestedCapacity) capacity = Math.multiplyExact(capacity, 2);
        commands = new AccountLaneCommand[capacity];
        admissionCommands = new AccountLaneCommand[capacity];
        matcherSettlementRings = new MatcherSettlementRing[matcherShardCount];
        for (int shard = 0; shard < matcherShardCount; shard++)
            matcherSettlementRings[shard] = new MatcherSettlementRing(capacity);
        indexMask = capacity - 1;
        admissionIndexMask = capacity - 1;
        this.lane = lane;
        thread = new LaneThread("core-" + role + "-lane-" + lane.laneId(), () -> run(affinity), lane);
        lane.bindWorker(thread);
        thread.start();
        while (!started && failure == null) Thread.onSpinWait();
        assertHealthy();
    }

    /** 复用的普通控制任务，只释放账户所有权并发布交接完成轮次，不修改资金。 */
    private final AccountLaneCommand handoffCommand = lane -> {
        lane.releaseOwnerForHandoff();
        completedHandoff = requestedHandoff;
    };

    /** 先发布请求轮次，再把复用交接命令放入普通邮箱。 */
    public void requestHandoff(long epoch) {
        requestedHandoff = epoch;
        submit(handoffCommand);
    }
    /** 只比较 Lane 已发布的交接轮次，不阻塞等待或重新绑定账户。 */
    public boolean handoffReady(long epoch) { return completedHandoff == epoch; }
    /** Owner 归还账户后发布恢复轮次，并按休眠声明唤醒工作线程。 */
    public void resumeHandoff(long epoch) {
        resumedHandoff = epoch;
    }

    /** 单 Owner 向普通邮箱发布任务，检查故障、关闭、物理容量及总采样深度。 */
    public long submit(AccountLaneCommand command) {
        if (command == null) throw new IllegalArgumentException("settlement command is required");
        assertHealthy();
        if (!running) throw new RejectedExecutionException("settlement lane is closed");
        long next = producerSequence.value;
        if (next - cachedConsumerSequence >= commands.length) {
            cachedConsumerSequence = consumerSequence.value;
            if (next - cachedConsumerSequence >= commands.length) {
                throw new RejectedExecutionException("settlement lane queue is full");
            }
        }
        if (depth() >= commands.length) {
            throw new RejectedExecutionException("settlement lane queue is full");
        }
        commands[(int) next & indexMask] = command;
        producerSequence.value = next + 1;
        return next + 1;
    }

    /** 单 Owner 向独立准入邮箱发布任务，只重新检查该物理邮箱容量。 */
    public long submitAdmission(AccountLaneCommand command) {
        if (command == null) throw new IllegalArgumentException("admission command is required");
        assertHealthy();
        if (!running) throw new RejectedExecutionException("settlement lane is closed");
        long next = admissionProducerSequence.value;
        if (next - cachedAdmissionConsumerSequence >= admissionCommands.length) {
            cachedAdmissionConsumerSequence = admissionConsumerSequence.value;
            if (next - cachedAdmissionConsumerSequence >= admissionCommands.length) {
                throw new RejectedExecutionException("settlement lane admission queue is full");
            }
        }
        admissionCommands[(int) next & admissionIndexMask] = command;
        admissionProducerSequence.value = next + 1;
        return next + 1;
    }

    /** 每个分片仅由其 Matcher 生产者发布已经就绪的结算事件。环满时自旋并检查故障和关闭，未另设超时或中断退出，成功后按需唤醒账户线程。 */
    public long publishMatcherSettlement(int matcherShard, TradeSettlement event) {
        if (event == null || !event.readyForLane()
                || matcherShard < 0 || matcherShard >= matcherSettlementRings.length)
            throw new IllegalArgumentException("invalid Matcher settlement publication");
        assertHealthy();
        if (!running) throw new RejectedExecutionException("settlement lane is closed");
        MatcherSettlementRing ring = matcherSettlementRings[matcherShard];
        while (ring.isFull()) {
            assertHealthy();
            if (!running) throw new RejectedExecutionException("settlement lane is closed");
            Thread.onSpinWait();
        }
        long position = ring.publish(event);
        return position + 1;
    }

    /** 汇总普通、准入及各 Matcher 邮箱的瞬时排队深度。 */
    public int depth() {
        long direct = 0;
        for (MatcherSettlementRing ring : matcherSettlementRings) direct += ring.depth();
        return Math.toIntExact((producerSequence.value - consumerSequence.value)
                + (admissionProducerSequence.value - admissionConsumerSequence.value) + direct);
    }

    /** 汇总各物理邮箱容量供观测使用，不修改任一邮箱的接纳规则。 */
    public int metricsQueueCapacity() {
        int capacity = Math.addExact(commands.length, admissionCommands.length);
        for (MatcherSettlementRing ring : matcherSettlementRings) capacity = Math.addExact(capacity, ring.events.length);
        return capacity;
    }

    /** 只返回运行、无故障且总采样深度小于普通邮箱容量的判断，不抛出已存故障或预留位置。 */
    public boolean hasCapacity() {
        return running && failure == null && depth() < commands.length;
    }

    /** 账户线程主循环：绑定亲和与所有权，先处理交接暂停，再优先推进就绪准入，按主队首序号合并 Matcher 邮件并执行普通任务。 */
    private void run(TradingCpuAffinity configuration) {
        long next = consumerSequence.value;
        long cachedProducerSeq = producerSequence.value;
        long admissionNext = admissionConsumerSequence.value;
        long cachedAdmissionProducerSeq = admissionProducerSequence.value;
        try (var affinity = configuration.accountLane(lane.laneId())) {
            lane.assertOwner();
            started = true;
            long reboundHandoff = 0;
            int payloadWaitSpins = 0;
            while (running || next < (cachedProducerSeq = producerSequence.value)
                    || admissionNext < (cachedAdmissionProducerSeq = admissionProducerSequence.value)
                    || hasMatcherSettlementWork()) {
                if (completedHandoff > reboundHandoff) {
                    if (resumedHandoff < completedHandoff) {
                        if (!running) break;
                        Thread.onSpinWait();
                        continue;
                    }
                    lane.assertOwner();
                    reboundHandoff = completedHandoff;
                }
                // 普通队首尚在等 Matcher 或账本输入时， 不能因此停止检查能够完成后续准入的消息。
                if (admissionNext >= cachedAdmissionProducerSeq) {
                    cachedAdmissionProducerSeq = admissionProducerSequence.value;
                }
                if (admissionNext < cachedAdmissionProducerSeq) {
                    int admissionIndex = (int) admissionNext & admissionIndexMask;
                    AccountLaneCommand admission = admissionCommands[admissionIndex];
                    if (admission == null) throw new IllegalStateException("admission publication gap");
                    if (ready(admission)) {
                        admissionCommands[admissionIndex] = null;
                        admissionNext++;
                        admissionConsumerSequence.value = admissionNext;
                        execute(admission);
                        payloadWaitSpins = 0;
                        continue;
                    }
                }
                // 交出账户后、Owner 归还之前， 工作线程不能继续消费 Matcher 环并修改账户。
                if (next >= cachedProducerSeq) {
                    cachedProducerSeq = producerSequence.value;
                }
                TradeSettlement direct = peekMatcherSettlement();
                AccountLaneCommand main = next < cachedProducerSeq ? commands[(int) next & indexMask] : null;
                if (direct != null && shouldRunMatcherSettlement(direct, main)) {
                    pollMatcherSettlement(direct);
                    execute(direct);
                    payloadWaitSpins = 0;
                    continue;
                }
                if (next < cachedProducerSeq) {
                    int index = (int) next & indexMask;
                    AccountLaneCommand command = commands[index];
                    if (command == null) throw new IllegalStateException("settlement lane publication gap");
                    if (ready(command)) {
                        commands[index] = null;
                        next++;
                        consumerSequence.value = next;
                        execute(command);
                        payloadWaitSpins = 0;
                        continue;
                    }
                    if (!running) throw new IllegalStateException("Lane stopped before its matcher payload arrived");

                    backoffPayloadWait(payloadWaitSpins++);
                    continue;
                }
                Thread.onSpinWait();
            }
        } catch (Throwable laneFailure) {
            failure = laneFailure;
            running = false;
            started = true;
            try { failurePublisher.accept(laneFailure); }
            catch (Throwable reportingFailure) {
                if (reportingFailure != laneFailure) laneFailure.addSuppressed(reportingFailure);
            }
        } finally {
            lane.releaseOwnerForHandoff();
        }
    }

    private void backoffPayloadWait(int spins) {
        if (spins < BUSY_SPIN_YIELD_THRESHOLD) {
            Thread.onSpinWait();
        } else {
            Thread.yield();
        }
    }

    private void execute(AccountLaneCommand command) {
        command.execute(lane);
    }

    /** 保留已存 RuntimeException 或 Error 的原对象抛出，其他 Throwable 包装为账户线程故障。 */
    public void assertHealthy() {
        Throwable laneFailure = failure;
        if (laneFailure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
        if (laneFailure instanceof Error error) throw error;
        if (laneFailure != null) throw new IllegalStateException("account lane failed", laneFailure);
    }

    private static boolean ready(Object command) {
        return command instanceof TradeSettlement event ? event.readyForLane()
                : ((AccountLaneCommand) command).accountReady();
    }

    /** 检查原故障后，只判断独立准入物理邮箱是否仍有位置，不检查总深度预算。 */
    public boolean hasAdmissionCapacity() {
        assertHealthy();
        return running && admissionProducerSequence.value - admissionConsumerSequence.value < admissionCommands.length;
    }

    private TradeSettlement peekMatcherSettlement() {
        TradeSettlement selected = null;
        long selectedSequence = Long.MAX_VALUE;
        for (MatcherSettlementRing ring : matcherSettlementRings) {
            TradeSettlement candidate = ring.peek();
            if (candidate == null) continue;
            long sequence = candidate.coreSequence();
            if (selected == null || sequence < selectedSequence) {
                selected = candidate;
                selectedSequence = sequence;
            }
        }
        return selected;
    }

    private boolean hasMatcherSettlementWork() {
        for (MatcherSettlementRing ring : matcherSettlementRings) {
            if (ring.depth() != 0) return true;
        }
        return false;
    }

    private void pollMatcherSettlement(TradeSettlement expected) {
        for (MatcherSettlementRing ring : matcherSettlementRings) {
            if (ring.peek() == expected) {
                ring.poll();
                return;
            }
        }
        throw new IllegalStateException("Matcher settlement publication disappeared");
    }

    private static boolean shouldRunMatcherSettlement(TradeSettlement direct, AccountLaneCommand main) {
        if (main == null) return true;
        long mainSequence = commandSequence(main);
        if (mainSequence == 0) return false;
        if (!ready(main)) return false;
        return direct.coreSequence() < mainSequence;
    }

    private static long commandSequence(AccountLaneCommand command) {
        if (command instanceof TradeSettlement event) return event.coreSequence();
        if (command instanceof com.surprising.aeron.service.core.change.AccountPartitionCommit event)
            return event.coreSequence();
        if (command instanceof com.surprising.aeron.service.order.OrderBatchProcessor.PlaceBatchAdmissionEvent event)
            return event.coreSequence();
        if (command instanceof com.surprising.aeron.service.order.OrderCommand.ReplacementAdmissionEvent event)
            return event.coreSequence();
        return 0;
    }

    /** 清运行标记后最多等待五秒，每次 join 十毫秒，记录并最终恢复调用者中断。仍存活则报错，否则传播线程原故障。此处不强制中断工作线程，也不承诺未就绪任务全部完成。 */
    @Override
    public void close() {
        running = false;
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.isAlive() && System.nanoTime() < deadline) {
            try {
                thread.join(10);
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (thread.isAlive()) throw new IllegalStateException("settlement lane did not stop");
        assertHealthy();
    }

    /** 保存邮箱跨线程可见的单调位置值游标槽。 */
    private static final class PaddedSequence {
        /** 该邮箱已发布或已消费的位置，volatile 写入向另一方交接可见性。 */
        protected volatile long value;
    }

    /** 一个 Matcher 到一个账户 Lane 的固定引用环，双方各自推进游标。 */
    private static final class MatcherSettlementRing {
        /** 本分片环已发布位置，单 Matcher 写入并向账户线程可见。 */
        protected volatile long producerPosition;
        protected long cachedConsumerPosition;
        /** 账户已取走的环位置，清引用后用 release 发布。 */
        protected volatile long consumerPosition;
        protected long cachedProducerPosition;

        /** 消费位置字段访问器，归还环槽时执行 release 写入。 */
        private static final java.lang.invoke.VarHandle CONSUMER_POSITION_VH;
        static {
            try {
                CONSUMER_POSITION_VH = java.lang.invoke.MethodHandles.lookup()
                        .findVarHandle(MatcherSettlementRing.class, "consumerPosition", long.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private final TradeSettlement[] events;
        private final int mask;

        /** 按外层已经核对的二次幂容量建立固定事件引用环与下标掩码。 */
        MatcherSettlementRing(int capacity) {
            events = new TradeSettlement[capacity];
            mask = capacity - 1;
        }

        /** 生产者先用缓存的消费水位判满，必要时读取最新消费位置，不预留槽。 */
        boolean isFull() {
            long position = producerPosition;
            if (position - cachedConsumerPosition >= events.length) {
                cachedConsumerPosition = consumerPosition;
                return position - cachedConsumerPosition >= events.length;
            }
            return false;
        }

        /** 单 Matcher 生产者检查容量及槽已释放，先放入事件再发布生产位置，返回零起始发布位置。 */
        long publish(TradeSettlement event) {
            long position = producerPosition;
            if (position - cachedConsumerPosition >= events.length) {
                cachedConsumerPosition = consumerPosition;
                if (position - cachedConsumerPosition >= events.length) {
                    throw new RejectedExecutionException("Matcher settlement ring is full");
                }
            }
            int index = (int) position & mask;
            if (events[index] != null) {
                throw new IllegalStateException("Matcher settlement ring slot was not released");
            }
            events[index] = event;
            producerPosition = position + 1;
            return position;
        }

        /** 账户消费者优先使用已见生产上界，必要时刷新后借出队首引用。 */
        TradeSettlement peek() {
            long position = consumerPosition;
            if (position >= cachedProducerPosition) {
                cachedProducerPosition = producerPosition;
                if (position >= cachedProducerPosition) {
                    return null;
                }
            }
            return events[(int) position & mask];
        }

        /** 账户消费者核对队首存在，清引用后以 release 推进消费位置，使生产者可以复用原环槽。 */
        void poll() {
            long position = consumerPosition;
            int index = (int) position & mask;
            if (position >= cachedProducerPosition) {
                cachedProducerPosition = producerPosition;
                if (position >= cachedProducerPosition) {
                    throw new IllegalStateException("Matcher settlement ring is empty");
                }
            }
            if (events[index] == null) {
                throw new IllegalStateException("Matcher settlement ring is empty");
            }
            events[index] = null;
            CONSUMER_POSITION_VH.setRelease(this, position + 1);
        }

        /** 采样分片生产与消费位置差，并把瞬时负差截为零，仅用于排队观测。 */
        long depth() {
            return Math.max(0L, producerPosition - consumerPosition);
        }
    }
}
