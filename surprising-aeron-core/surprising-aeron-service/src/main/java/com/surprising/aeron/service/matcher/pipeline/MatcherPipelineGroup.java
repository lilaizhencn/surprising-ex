package com.surprising.aeron.service.matcher.pipeline;
import com.surprising.aeron.config.TradingCpuAffinity;
import com.surprising.aeron.service.matcher.CoreMatchingResult;
import com.surprising.aeron.service.order.CommandSlot;
import com.surprising.aeron.service.order.CommandSlotRing;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * 按全局分片号组织 Matcher 流水线。调用方先按永久品种号决定分片，本类把请求交给该分片并把普通结果定位回 CommandSlot；分片间可以独立完成，Owner 用就绪位图回收传输槽，单分片内仍保持顺序。跨分片账户提交与成交结算由外层负责。
 */
public final class MatcherPipelineGroup implements AutoCloseable {
    /** 按全局分片号固定排列的 Matcher 流水线数组。 */
    private final MatcherCommandPipeline[] shards;
    /** Owner 现有命令槽环，Matcher 完成结果直接发布到其中，组内不另存一份业务结果表。 */
    private final CommandSlotRing contexts;
    /** Matcher 发布完成时置位、Owner 一次取清的就绪位图，用来筛选需要回收的分片。 */
    private final com.surprising.aeron.service.core.TradingRuntimeState.PaddedReadyMask completedShardMask;
    /** 是否已安装分片完成通知，置位后不再走未配置时的全分片探查路径。 */
    private volatile boolean completionSignalConfigured;

    /**
     * 为各分片安装完成回调，发布时先设置对应就绪位，只有从未就绪变为就绪且外层回调非空才唤醒；配置后关闭无回调时的全分片探查分支。
     * @param signal 有新分片就绪时执行的可空 Owner 唤醒动作
     */
    public void completionSignal(Runnable signal) {
        completionSignalConfigured = true;
        for (/* 当前遍历的全局 Matcher 分片号。 */ int shardId = 0; shardId < shards.length; shardId++) {
            // 当前回调固定捕获的分片号，避免依赖下一轮循环。
            final int currentShardId = shardId;
            shards[shardId].completionSignal(() -> {
                if (completedShardMask.setBit(currentShardId) && signal != null) signal.run();
            });
        }
    }

    /**
     * 校验正的二次幂分片数和非空命令槽环，为每个分片创建延迟启动的流水线；需要立即启动时依次启动全部独立 worker。
     * @param shardCount 正的二次幂分片数，上界还受就绪位图构造约束
     * @param capacityPerShard 每个独立 Matcher 环形队列的二次幂容量
     * @param startImmediately 是否在构造时立即启动全部独立 worker
     * @param contexts 用于匹配 Core 序号与完成结果的现有命令槽环
     */
    public MatcherPipelineGroup(int shardCount, int capacityPerShard, boolean startImmediately, CommandSlotRing contexts) {
        if (shardCount <= 0 || (shardCount & (shardCount - 1)) != 0) {
            throw new IllegalArgumentException("matcher shard count must be a power of two");
        }
        this.contexts = java.util.Objects.requireNonNull(contexts, "matching sequence contexts");
        completedShardMask = new com.surprising.aeron.service.core.TradingRuntimeState.PaddedReadyMask(shardCount);
        shards = new MatcherCommandPipeline[shardCount];
        for (/* 当前操作的分片流水线，构造循环中则是分片下标。 */ int shard = 0; shard < shardCount; shard++) {
            shards[shard] = new MatcherCommandPipeline(shard, capacityPerShard, false);
        }
        if (startImmediately) start((IntConsumer) null);
    }

    /**
     * 把全部 Matcher 分片绑定到当前交易线程，各分片在其同步启动动作中激活订单簿，不创建独立 Matcher 线程。
     * @param shardActivation 在分片拥有线程执行的可空订单簿激活动作
     */
    public void startInline(IntConsumer shardActivation) {
        for (/* 当前遍历的全局 Matcher 分片号。 */ int shardId = 0; shardId < shards.length; shardId++) {
            // 当前回调固定捕获的分片号，避免依赖下一轮循环。
            int currentShardId = shardId;
            shards[shardId].startInline(shardActivation == null ? null
                    : () -> shardActivation.accept(currentShardId));
        }
    }

    /**
     * 使用禁用 CPU 绑定的配置逐分片启动独立 worker。
     * @param shardActivation 在分片拥有线程执行的可空订单簿激活动作
     */
    public void start(IntConsumer shardActivation) {
        start(shardActivation, TradingCpuAffinity.disabled());
    }

    /**
     * 依次启动各分片 worker，并把该分片号带入所属线程的激活动作；每个分片启动调用会等待启动结果，发生异常不在此方法回滚先前已启动的分片。
     * @param shardActivation 在分片拥有线程执行的可空订单簿激活动作
     * @param affinity 各分片 worker 的 CPU 绑定配置
     */
    public void start(IntConsumer shardActivation,
            TradingCpuAffinity affinity) {
        for (/* 当前遍历的全局 Matcher 分片号。 */ int shardId = 0; shardId < shards.length; shardId++) {
            // 当前回调固定捕获的分片号，避免依赖下一轮循环。
            int currentShardId = shardId;
            shards[shardId].start(shardActivation == null ? null
                    : () -> shardActivation.accept(currentShardId), affinity);
        }
    }

    /**
     * 不附带结算事件地提交目标分片的普通命令。
     * @param shardId 目标全局 Matcher 分片号
     * @param coreSequence 需要提交或收集的正 Core 命令序号
     * @param command 目标分片执行的业务或控制动作，具体形式由方法决定
     */
    public void submit(int shardId, long coreSequence, Supplier<?> command) {
        submit(shardId, coreSequence, command, null);
    }

    /**
     * 定位并占用对应 CommandSlot 的 Matcher 路由，直达事件记录完成路由后提交，普通结果直接回写命令槽；提交运行异常时只释放本次仍持有的路由，不回滚已经执行的业务。
     * @param shardId 目标全局 Matcher 分片号
     * @param coreSequence 需要提交或收集的正 Core 命令序号
     * @param command 目标分片执行的业务或控制动作，具体形式由方法决定
     * @param settlement 可空的结算事件，direct 模式通过自身完成路由交付事实
     */
    public void submit(int shardId, long coreSequence, Supplier<?> command,
                com.surprising.aeron.service.settlement.MatcherSettlementEvent settlement) {
        if (coreSequence <= 0 || command == null) throw new IllegalArgumentException("invalid matcher submission");
        // 当前操作的分片流水线，构造循环中则是分片下标。
        MatcherCommandPipeline shard = shard(shardId);
        // 当前 Core 序号已占用的命令槽，保存提交路由和普通结果。
        CommandSlot context = contexts.required(coreSequence);
        context.claimMatcherSubmission(shardId);
        try {
            if (settlement != null && settlement.direct()) {
                settlement.matcherCompletionRoute(context, shardId);
                shard.submit(coreSequence, command, settlement);
            }
            else shard.submit(coreSequence, command, settlement, context);
        } catch (/* 提交失败或关闭流程的首个运行异常，按所在方法释放路由或汇总后抛出。 */ RuntimeException failure) {
            if (context.submittedMatcherShard() == shardId) context.releaseMatcherSubmission(shardId);
            throw failure;
        }
    }

    /**
     * 对已占用的 Core 序号先回收就绪传输槽，再从它自己的 CommandSlot 取一次普通结果；无效或未占用序号返回空。
     * @param coreSequence 需要提交或收集的正 Core 命令序号
     */
    public CoreMatchingResult poll(long coreSequence) {
        if (coreSequence <= 0 || !contexts.claimed(coreSequence)) return null;
        drainMatchingCompletions();
        return contexts.required(coreSequence).takeMatchingCompletion();
    }

    /**
     * 只探查是否有就绪分片位，未配置完成通知时再探查各分片正 token 队头；不收集控制调用的结果。
     */
    public boolean hasMatchingCompletions() {
        if (completedShardMask.get() != 0) return true;
        if (!completionSignalConfigured) {
            for (MatcherCommandPipeline /* 当前操作的分片流水线，构造循环中则是分片下标。 */ shard : shards) {
                if (shard.completedMatchingSequence() != 0) return true;
            }
        }
        return false;
    }

    /**
     * 取走就绪位图，仅在这些分片连续收集普通队头以回收传输槽并传播失败；Matcher 已把结果交给命令槽，此处不再访问可能已复用的 CommandSlot。未配置通知时主动探查分片。
     */
    public void drainMatchingCompletions() {
        // 一次取清的就绪分片位图，必要时加入主动探查的普通完成分片。
        long readyMask = completedShardMask.getAndClear();
        if (!completionSignalConfigured) {
            for (/* 当前遍历的全局 Matcher 分片号。 */ int shardId = 0; shardId < shards.length; shardId++) {
                if (shards[shardId].completedMatchingSequence() != 0) readyMask |= 1L << shardId;
            }
        }
        for (/* 当前遍历的全局 Matcher 分片号。 */ int shardId = 0; shardId < shards.length; shardId++) {
            if ((readyMask & (1L << shardId)) == 0) continue;
            // 当前操作的分片流水线，构造循环中则是分片下标。
            MatcherCommandPipeline shard = shards[shardId];
            // 当前可回收的分片队头正 Core 序号。
            long sequence;
            while ((sequence = shard.completedMatchingSequence()) != 0) {
                // 收集同时传播 worker 失败，这里不读取已经可能复用的命令槽。
                if (shard.poll(sequence) == null) break;
            }
        }
    }

    /**
     * 将查询交给指定分片的提交栅栏读信箱，读取函数在该分片拥有线程执行。
     * @param <T> 查询或控制动作返回值的类型
     * @param shardId 目标全局 Matcher 分片号
     * @param read 在一个分片或每个分片的提交栅栏执行的读取函数
     */
    public <T> java.util.concurrent.CompletableFuture<T> readAtSubmissionFence(int shardId,Supplier<T> read) {
        return shard(shardId).readAtSubmissionFence(read);
    }

    /**
     * 向指定分片提交独立负 token 控制动作，返回 token 而不等待完成。
     * @param shardId 目标全局 Matcher 分片号
     * @param command 目标分片执行的业务或控制动作，具体形式由方法决定
     */
    public long submitControl(int shardId, Supplier<?> command) { return shard(shardId).submitControl(command); }
    /**
     * 只从指定分片收集对应负 token 的控制结果，不与普通撮合结果混用。
     * @param shardId 目标全局 Matcher 分片号
     * @param token 目标分片之前生成的负控制 token
     */
    public Object pollControl(int shardId, long token) { return shard(shardId).pollControl(token); }

    /**
     * 为每个分片投递独立提交栅栏读取，全部 Future 完成后按分片顺序聚合；聚合中的 join 只读取已完成结果。它们是各自的提交栅栏，不自动构成同一个全局冻结点。
     * @param <T> 查询或控制动作返回值的类型
     * @param read 在一个分片或每个分片的提交栅栏执行的读取函数
     */
    public <T> java.util.concurrent.CompletableFuture<java.util.List<T>> readEachAsync(IntFunction<T> read) {
        // 按分片顺序保存的独立栅栏读取 Future。
        var futures = new java.util.ArrayList<java.util.concurrent.CompletableFuture<T>>(shards.length);
        for (/* 当前遍历的全局 Matcher 分片号。 */ int shardId = 0; shardId < shards.length; shardId++) {
            // 当前读取回调固定捕获的分片号。
            int current = shardId;
            futures.add(readAtSubmissionFence(shardId, () -> read.apply(current)));
        }
        return java.util.concurrent.CompletableFuture.allOf(
                futures.toArray(java.util.concurrent.CompletableFuture[]::new))
                .thenApply(/* 全部分片 Future 完成信号，无业务值，随后按顺序读取结果。 */ ignored -> futures.stream().map(java.util.concurrent.CompletableFuture::join).toList());
    }

    /**
     * 在指定分片提交控制动作并等待限时结果，超时不撤销已提交工作。
     * @param <T> 查询或控制动作返回值的类型
     * @param shardId 目标全局 Matcher 分片号
     * @param command 目标分片执行的业务或控制动作，具体形式由方法决定
     * @param timeoutNanos 单次分片控制调用的纳秒等待上限
     */
    public <T> T call(int shardId, Supplier<T> command, long timeoutNanos) {
        return shard(shardId).call(command, timeoutNanos);
    }

    /**
     * 在分片零执行控制动作并等待结果，不代表广播全部分片。
     * @param <T> 查询或控制动作返回值的类型
     * @param command 目标分片执行的业务或控制动作，具体形式由方法决定
     * @param timeoutNanos 单次分片控制调用的纳秒等待上限
     */
    public <T> T call(Supplier<T> command, long timeoutNanos) {
        return call(0, command, timeoutNanos);
    }

    /**
     * 按分片号依次提交并等待控制调用，全部成功后返回不可修改的结果列表；每个分片分别使用传入超时，不是并行读取，也没有整体统一截止。
     * @param <T> 查询或控制动作返回值的类型
     * @param command 目标分片执行的业务或控制动作，具体形式由方法决定
     * @param timeoutNanos 单次分片控制调用的纳秒等待上限
     */
    public <T> java.util.List<T> callEach(IntFunction<T> command, long timeoutNanos) {
        if (command == null) throw new IllegalArgumentException("matcher shard control is required");
        // 按分片顺序保存的串行控制调用结果。
        java.util.ArrayList<T> results = new java.util.ArrayList<>(shards.length);
        for (/* 当前遍历的全局 Matcher 分片号。 */ int shardId = 0; shardId < shards.length; shardId++) {
            // 当前回调固定捕获的分片号，避免依赖下一轮循环。
            int currentShardId = shardId;
            results.add(call(shardId, () -> command.apply(currentShardId), timeoutNanos));
        }
        return java.util.List.copyOf(results);
    }

    /**
     * 精确累加各分片已提交但未执行完成的深度。
     */
    public int submissionDepth() {
        // 逐分片精确累加的当前队列深度。
        int depth = 0;
        for (MatcherCommandPipeline /* 当前操作的分片流水线，构造循环中则是分片下标。 */ shard : shards) depth = Math.addExact(depth, shard.submissionDepth());
        return depth;
    }

    /**
     * 精确累加各分片已完成但未回收的传输槽深度。
     */
    public int completionDepth() {
        // 逐分片精确累加的当前队列深度。
        int depth = 0;
        for (MatcherCommandPipeline /* 当前操作的分片流水线，构造循环中则是分片下标。 */ shard : shards) depth = Math.addExact(depth, shard.completionDepth());
        return depth;
    }

    /**
     * 以精确乘法计算分片数乘首分片容量，融合模式每分片容量为一。
     */
    public int capacity() { return Math.multiplyExact(shards.length, shards[0].capacity()); }

    /**
     * 返回各分片提交窗口占用峰值中的最大值，不是所有分片同一时刻的总峰值。
     */
    public int submissionHighWaterMark() {
        // 已经扫描的分片观测峰值中的最大值。
        int maximum = 0;
        for (MatcherCommandPipeline /* 当前操作的分片流水线，构造循环中则是分片下标。 */ shard : shards) maximum = Math.max(maximum, shard.submissionHighWaterMark());
        return maximum;
    }

    /**
     * 返回各分片未回收完成峰值中的最大值，不是分片峰值之和。
     */
    public int completionHighWaterMark() {
        // 已经扫描的分片观测峰值中的最大值。
        int maximum = 0;
        for (MatcherCommandPipeline /* 当前操作的分片流水线，构造循环中则是分片下标。 */ shard : shards) maximum = Math.max(maximum, shard.completionHighWaterMark());
        return maximum;
    }

    /**
     * 返回创建时固定的 Matcher 分片数量。
     */
    public int shardCount() { return shards.length; }

    /**
     * 检查分片号在拓扑内并返回对应流水线。
     * @param shardId 目标全局 Matcher 分片号
     */
    private MatcherCommandPipeline shard(int shardId) {
        if (shardId < 0 || shardId >= shards.length) {
            throw new IllegalArgumentException("matcher shard is outside configured topology");
        }
        return shards[shardId];
    }

    /**
     * 关闭全部分片，不附带最终外层关闭动作。
     */
    @Override
    public void close() { close(null); }

    /**
     * 依次关闭分片，并让各分片按其模式执行带分片号的关闭动作；捕获运行异常后继续关闭其余分片，最后抛首个并附带后续异常。
     * @param action 关闭动作；closeShards 按分片执行，close 在全部分片后由调用线程执行一次
     */
    public void closeShards(IntConsumer action) {
        // 提交失败或关闭流程的首个运行异常，按所在方法释放路由或汇总后抛出。
        RuntimeException failure = null;
        for (/* 当前遍历的全局 Matcher 分片号。 */ int shardId = 0; shardId < shards.length; shardId++) {
            // 当前回调固定捕获的分片号，避免依赖下一轮循环。
            int currentShardId = shardId;
            try {
                shards[shardId].close(action == null ? null : () -> action.accept(currentShardId));
            } catch (/* 当前分片关闭或最终动作的运行异常，附加到首个失败。 */ RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        if (failure != null) throw failure;
    }

    /**
     * 依次关闭全部分片，再在当前调用线程执行一次外层动作；运行异常聚合后抛出，不把外层动作误当成每分片线程动作。
     * @param action 关闭动作；closeShards 按分片执行，close 在全部分片后由调用线程执行一次
     */
    public void close(Runnable action) {
        // 提交失败或关闭流程的首个运行异常，按所在方法释放路由或汇总后抛出。
        RuntimeException failure = null;
        for (MatcherCommandPipeline /* 当前操作的分片流水线，构造循环中则是分片下标。 */ shard : shards) {
            try {
                shard.close();
            } catch (/* 当前分片关闭或最终动作的运行异常，附加到首个失败。 */ RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        if (action != null) {
            try {
                action.run();
            } catch (/* 当前分片关闭或最终动作的运行异常，附加到首个失败。 */ RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        if (failure != null) throw failure;
    }
}
