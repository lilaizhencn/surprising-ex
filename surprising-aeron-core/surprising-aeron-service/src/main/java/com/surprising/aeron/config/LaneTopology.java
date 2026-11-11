package com.surprising.aeron.config;

/** 产品 Core 内的执行路由。同一产品账户的全部资产、全仓/逐仓持仓、冻结及风险状态必须留在同一 Lane。matcher 数量和币对路由不得改变保证金共享范围。路由和种子随快照保存，恢复时不能重新按当前配置分配已有账户。 */
public record LaneTopology(
        int matchingEngineCount,
        int accountLaneCount,
        long accountLaneSeed,
        int matcherWindowSize,
        int matchingCompletionCapacity,
        int accountLaneQueueCapacity) {

    public static final int DEFAULT_MATCHING_ENGINE_COUNT = 1;
    public static final int DEFAULT_ACCOUNT_LANE_COUNT = 2;
    /** 同一个编号和种子必须得到相同 Lane。 */
    public static final long DEFAULT_ACCOUNT_LANE_SEED = 0x6a09e667f3bcc909L;
    public static final int DEFAULT_MATCHER_WINDOW_SIZE = 4_096;
    public static final int DEFAULT_QUEUE_CAPACITY = 4_096;

    /** 创建前统一检查数量和容量，避免位掩码算法收到不合法的参数。 */
    public LaneTopology {
        requirePowerOfTwo(matchingEngineCount, 1, 64, "matchingEngineCount");
        requirePowerOfTwo(accountLaneCount, 1, Long.SIZE, "accountLaneCount");
        requirePowerOfTwo(matcherWindowSize, 1, 1 << 20, "matcherWindowSize");
        requirePowerOfTwo(matchingCompletionCapacity, 1, 1 << 20, "matchingCompletionCapacity");
        requirePowerOfTwo(accountLaneQueueCapacity, 1, 1 << 20, "accountLaneQueueCapacity");
    }

    /** 分片掩码完全由已校验的分片数量决定。 */
    public int matcherShardMask() { return matchingEngineCount - 1; }

    /** 使用固定币对编号选择订单簿所属分片，不使用用户编号路由撮合。 */
    public int matcherShardId(int stableSymbolId) {
        if (stableSymbolId <= 0) throw new IllegalArgumentException("stableSymbolId must be positive");
        return stableSymbolId & matcherShardMask();
    }

    /** 查找用户账户的唯一写入 Lane。 */
    public int accountLaneId(long userId) {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        return (int) (mix64(userId ^ accountLaneSeed) & (accountLaneCount - 1L));
    }

    /** 把一个用户的 Lane 编号转成位集合，方便派发任务时标记要访问哪个 Lane。 */
    public long accountLaneMask(long userId) {
        return 1L << accountLaneId(userId);
    }

    private static void requirePowerOfTwo(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum || (value & (value - 1)) != 0) {
            throw new IllegalArgumentException(name + " must be a power of two in [" + minimum + ',' + maximum + ']');
        }
    }

    private static long mix64(long value) {
        long mixed = value;
        mixed = (mixed ^ (mixed >>> 30)) * 0xbf58476d1ce4e5b9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94d049bb133111ebL;
        return mixed ^ (mixed >>> 31);
    }

}
