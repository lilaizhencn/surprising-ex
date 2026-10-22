package com.surprising.aeron.support.metrics;

/**
 * Owner 对 Matcher、命令槽和账户 Lane 的一次运行观测。构造时复制所有数组，读取数组时再返回副本，防止外部改写本次结果；各值可能来自不同采样时刻，不是全局原子交易快照，也不能用于恢复。
 * @param matchingEngineCount 当前拓扑的 Matcher 数量，必须为正
 * @param accountLaneCount 当前拓扑的账户 Lane 数量，必须为正
 * @param matcherDispatchDepth 采样时待派发 Matcher 输入数量
 * @param matcherDispatchCapacity Matcher 输入容量，必须为正
 * @param matcherDispatchHighWaterMark 已记录的 Matcher 输入积压高水位，不能小于本次深度
 * @param matchingCompletionDepth 采样时未收集的 Matcher 完成数量
 * @param matchingCompletionCapacity Matcher 完成缓冲容量，必须为正
 * @param matchingCompletionHighWaterMark 已记录的 Matcher 完成积压高水位
 * @param commandContextDepth 采样时在途命令槽数量
 * @param commandContextCapacity 在途命令槽容量，必须为正
 * @param commandContextHighWaterMark 已记录的在途命令槽占用高水位
 * @param committedCoreSequence 当前已提交 Core 序号，必须非负
 * @param accountLaneRevisions 各 Lane 的修订号，按 laneId 索引
 * @param accountLaneAppliedSequences 各 Lane 已应用的命令序号，按 laneId 索引
 * @param accountLaneCommittedSequences 各 Lane 已提交的命令序号，按 laneId 索引
 * @param accountLaneQueueDepths 各 Lane 输入队列采样深度，按 laneId 索引
 * @param accountLaneQueueCapacities 各 Lane 工作输入的总容量，按 laneId 索引
 * @param accountLaneQueueHighWaterMarks 各 Lane 已观测到的队列深度高水位，并非每次入队都采样
 * @param accountLaneRejectedSubmissions 各 Lane 技术队列投递失败次数，不是业务拒单数
 * @param accountLaneOldestPendingSequences 各 Lane 最早待处理序号，按 laneId 索引
 * @param accountLaneCompletedOperations 各 Lane 四类操作的累计完成数，按采集协议展平排列
 * @param accountLaneLatencySamples 各 Lane 四类操作的有效延迟样本数，未采样时可为零
 * @param accountLaneTotalLatencyNanos 各 Lane 四类操作的累计样本延迟，单位纳秒
 * @param accountLaneMaxLatencyNanos 各 Lane 四类操作的最大样本延迟，单位纳秒
 */
public record CoreLaneMetrics(
        int matchingEngineCount,
        int accountLaneCount,
        int matcherDispatchDepth,
        int matcherDispatchCapacity,
        int matcherDispatchHighWaterMark,
        int matchingCompletionDepth,
        int matchingCompletionCapacity,
        int matchingCompletionHighWaterMark,
        int commandContextDepth,
        int commandContextCapacity,
        int commandContextHighWaterMark,
        long committedCoreSequence,
        long[] accountLaneRevisions,
        long[] accountLaneAppliedSequences,
        long[] accountLaneCommittedSequences,
        int[] accountLaneQueueDepths,
        int[] accountLaneQueueCapacities,
        int[] accountLaneQueueHighWaterMarks,
        long[] accountLaneRejectedSubmissions,
        long[] accountLaneOldestPendingSequences,
        long[] accountLaneCompletedOperations,
        long[] accountLaneLatencySamples,
        long[] accountLaneTotalLatencyNanos,
        long[] accountLaneMaxLatencyNanos) {

    /** 每个账户 Lane 采集四类操作统计，四组统计数组都按 Lane 和操作类型展平。 */
    public static final int OPERATION_TYPE_COUNT = 4;

    /**
     * 检查拓扑、非负深度、正容量和数组长度；逐 Lane 校验深度及高水位在容量内，再复制输入数组。顶层 Matcher 与命令槽深度未校验容量上界，修订及延迟数组的每个值也未逐项检查。
     */
    public CoreLaneMetrics {
        if (matchingEngineCount <= 0 || accountLaneCount <= 0 || matcherDispatchDepth < 0
                || matcherDispatchCapacity <= 0 || matcherDispatchHighWaterMark < matcherDispatchDepth
                || matchingCompletionDepth < 0 || matchingCompletionCapacity <= 0
                || matchingCompletionHighWaterMark < matchingCompletionDepth || commandContextDepth < 0
                || commandContextCapacity <= 0 || commandContextHighWaterMark < commandContextDepth
                || committedCoreSequence < 0 || accountLaneRevisions == null
                || accountLaneAppliedSequences == null || accountLaneCommittedSequences == null
                || accountLaneQueueDepths == null || accountLaneQueueCapacities == null
                || accountLaneQueueHighWaterMarks == null || accountLaneRejectedSubmissions == null
                || accountLaneOldestPendingSequences == null || accountLaneCompletedOperations == null
                || accountLaneLatencySamples == null
                || accountLaneTotalLatencyNanos == null || accountLaneMaxLatencyNanos == null
                || accountLaneRevisions.length != accountLaneCount
                || accountLaneAppliedSequences.length != accountLaneCount
                || accountLaneCommittedSequences.length != accountLaneCount
                || accountLaneQueueDepths.length != accountLaneCount
                || accountLaneQueueCapacities.length != accountLaneCount
                || accountLaneQueueHighWaterMarks.length != accountLaneCount
                || accountLaneRejectedSubmissions.length != accountLaneCount
                || accountLaneOldestPendingSequences.length != accountLaneCount
                || accountLaneCompletedOperations.length != accountLaneCount * OPERATION_TYPE_COUNT
                || accountLaneLatencySamples.length != accountLaneCompletedOperations.length
                || accountLaneTotalLatencyNanos.length != accountLaneCompletedOperations.length
                || accountLaneMaxLatencyNanos.length != accountLaneCompletedOperations.length) {
            throw new IllegalArgumentException("invalid Core lane metrics");
        }
        for (/* 本轮检查的账户 Lane 索引，只校验该 Lane 的队列观测范围 */ int laneId = 0; laneId < accountLaneCount; laneId++) {
            if (accountLaneQueueDepths[laneId] < 0 || accountLaneQueueCapacities[laneId] <= 0
                    || accountLaneQueueDepths[laneId] > accountLaneQueueCapacities[laneId]
                    || accountLaneQueueHighWaterMarks[laneId] < accountLaneQueueDepths[laneId]
                    || accountLaneQueueHighWaterMarks[laneId] > accountLaneQueueCapacities[laneId]
                    || accountLaneRejectedSubmissions[laneId] < 0
                    || accountLaneOldestPendingSequences[laneId] < 0) {
                throw new IllegalArgumentException("invalid Account Lane queue metrics");
            }
        }
        accountLaneRevisions = accountLaneRevisions.clone();
        accountLaneAppliedSequences = accountLaneAppliedSequences.clone();
        accountLaneCommittedSequences = accountLaneCommittedSequences.clone();
        accountLaneQueueDepths = accountLaneQueueDepths.clone();
        accountLaneQueueCapacities = accountLaneQueueCapacities.clone();
        accountLaneQueueHighWaterMarks = accountLaneQueueHighWaterMarks.clone();
        accountLaneRejectedSubmissions = accountLaneRejectedSubmissions.clone();
        accountLaneOldestPendingSequences = accountLaneOldestPendingSequences.clone();
        accountLaneCompletedOperations = accountLaneCompletedOperations.clone();
        accountLaneLatencySamples = accountLaneLatencySamples.clone();
        accountLaneTotalLatencyNanos = accountLaneTotalLatencyNanos.clone();
        accountLaneMaxLatencyNanos = accountLaneMaxLatencyNanos.clone();
    }

    /**
     * 返回各 Lane 修订号的独立数组副本。
     */
    @Override
    public long[] accountLaneRevisions() { return accountLaneRevisions.clone(); }

    /**
     * 返回各 Lane 已应用序号的独立数组副本。
     */
    @Override
    public long[] accountLaneAppliedSequences() { return accountLaneAppliedSequences.clone(); }

    /**
     * 返回各 Lane 已提交序号的独立数组副本。
     */
    @Override
    public long[] accountLaneCommittedSequences() { return accountLaneCommittedSequences.clone(); }

    /**
     * 返回各 Lane 采样队列深度的独立数组副本。
     */
    @Override
    public int[] accountLaneQueueDepths() { return accountLaneQueueDepths.clone(); }

    /**
     * 返回各 Lane 队列总容量的独立数组副本。
     */
    @Override
    public int[] accountLaneQueueCapacities() { return accountLaneQueueCapacities.clone(); }

    /**
     * 返回各 Lane 已观测深度高水位的独立数组副本。
     */
    @Override
    public int[] accountLaneQueueHighWaterMarks() { return accountLaneQueueHighWaterMarks.clone(); }

    /**
     * 返回各 Lane 技术投递失败次数的独立数组副本。
     */
    @Override
    public long[] accountLaneRejectedSubmissions() { return accountLaneRejectedSubmissions.clone(); }

    /**
     * 返回各 Lane 最早待处理序号的独立数组副本。
     */
    @Override
    public long[] accountLaneOldestPendingSequences() { return accountLaneOldestPendingSequences.clone(); }

    /**
     * 返回各 Lane 分操作类型累计完成数的独立数组副本。
     */
    @Override
    public long[] accountLaneCompletedOperations() { return accountLaneCompletedOperations.clone(); }

    /**
     * 返回各 Lane 分操作类型延迟样本数的独立数组副本。
     */
    @Override
    public long[] accountLaneLatencySamples() { return accountLaneLatencySamples.clone(); }

    /**
     * 返回各 Lane 分操作类型累计纳秒延迟的独立数组副本。
     */
    @Override
    public long[] accountLaneTotalLatencyNanos() { return accountLaneTotalLatencyNanos.clone(); }

    /**
     * 返回各 Lane 分操作类型最大纳秒延迟的独立数组副本。
     */
    @Override
    public long[] accountLaneMaxLatencyNanos() { return accountLaneMaxLatencyNanos.clone(); }
}
