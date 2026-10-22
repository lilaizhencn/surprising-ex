package com.surprising.aeron.service.lane;

/**
 * 产品 Core 内的执行路由。撮合按订单簿划分，账户按用户整体划分。
 * 同一产品账户的全部资产、全仓/逐仓持仓、冻结及风险状态必须留在同一 Lane；
 * matcher 数量和币对路由不得改变保证金共享范围。六产品账户间仍通过显式划转连接。
 * 路由和种子随快照保存，恢复时不能重新按当前配置分配已有账户。
 *
 * @param matchingEngineCount Matcher 分片数量，必须是允许范围内的 2 次幂
 * @param riskEngineCount 配置中保留的风险引擎数量；本值本身不负责启动风险线程
 * @param matcherShardMask 选取 Matcher 分片用的位掩码，固定等于分片数减一
 * @param accountLaneCount 账户 Lane 数量，同一用户始终只归属其中一个 Lane
 * @param accountLaneSeed 用户编号混合运算的种子，恢复后不能随意改动
 * @param matcherWindowSize 允许登记的 Matcher 在途窗口大小
 * @param matchingCompletionCapacity Matcher 完成队列允许保留的结果数量
 * @param accountLaneQueueCapacity 单个账户 Lane 输入队列的容量
 */
public record LaneTopology(
        int matchingEngineCount,
        int riskEngineCount,
        int matcherShardMask,
        int accountLaneCount,
        long accountLaneSeed,
        int matcherWindowSize,
        int matchingCompletionCapacity,
        int accountLaneQueueCapacity) {

    /** 未显式配置时使用一个 Matcher，订单簿仍按固定分片规则寻址。 */
    public static final int DEFAULT_MATCHING_ENGINE_COUNT = 1;
    /** 风险引擎数量配置的默认值；账户风控由账户 Lane 负责。 */
    public static final int DEFAULT_RISK_ENGINE_COUNT = 0;
    /** 未显式配置时使用两个账户 Lane。 */
    public static final int DEFAULT_ACCOUNT_LANE_COUNT = 2;
    /** 默认用户路由种子；同一个编号和种子必须得到相同 Lane。 */
    public static final long DEFAULT_ACCOUNT_LANE_SEED = 0x6a09e667f3bcc909L;
    /** 默认 Matcher 在途窗口最多容纳的槽位数。 */
    public static final int DEFAULT_MATCHER_WINDOW_SIZE = 4_096;
    /** 默认队列槽位数，供完成队列和账户输入队列使用。 */
    public static final int DEFAULT_QUEUE_CAPACITY = 4_096;

    /** 创建前统一检查数量、容量和分片掩码，避免位掩码算法收到不合法的参数。 */
    public LaneTopology {
        requirePowerOfTwo(matchingEngineCount, 1, 64, "matchingEngineCount");
        requirePowerOfTwo(riskEngineCount, 0, 64, "riskEngineCount");
        requirePowerOfTwo(accountLaneCount, 1, Long.SIZE, "accountLaneCount");
        requirePowerOfTwo(matcherWindowSize, 1, 1 << 20, "matcherWindowSize");
        requirePowerOfTwo(matchingCompletionCapacity, 1, 1 << 20, "matchingCompletionCapacity");
        requirePowerOfTwo(accountLaneQueueCapacity, 1, 1 << 20, "accountLaneQueueCapacity");
        if (matcherShardMask != matchingEngineCount - 1) {
            throw new IllegalArgumentException("invalid deterministic lane topology");
        }
    }

    /** 创建固定默认拓扑；外部配置统一由 CoreConfiguration 绑定并传入。 */
    public static LaneTopology productionDefault() {
        return new LaneTopology(DEFAULT_MATCHING_ENGINE_COUNT, DEFAULT_RISK_ENGINE_COUNT,
                DEFAULT_MATCHING_ENGINE_COUNT - 1, DEFAULT_ACCOUNT_LANE_COUNT, DEFAULT_ACCOUNT_LANE_SEED,
                DEFAULT_MATCHER_WINDOW_SIZE, DEFAULT_QUEUE_CAPACITY, DEFAULT_QUEUE_CAPACITY);
    }

    /** 创建只有一个 Matcher 和一个账户 Lane 的固定测试拓扑，便于核对基础行为。 */
    public static LaneTopology characterization() {
        return new LaneTopology(1, 0, 0, 1, DEFAULT_ACCOUNT_LANE_SEED,
                DEFAULT_MATCHER_WINDOW_SIZE, DEFAULT_QUEUE_CAPACITY, DEFAULT_QUEUE_CAPACITY);
    }

    /**
     * 使用固定币对编号选择订单簿所属分片，不使用用户编号路由撮合。
     * @param stableSymbolId 已分配的正数币对编号
     * @return 从 0 开始的 Matcher 分片编号
     */
    public int matcherShardId(int stableSymbolId) {
        if (stableSymbolId <= 0) throw new IllegalArgumentException("stableSymbolId must be positive");
        return stableSymbolId & matcherShardMask;
    }

    /**
     * 查找用户账户的唯一写入 Lane；跨币对的全仓资金仍放在同一个 Lane。
     * @param userId 正数用户编号；路由不能再混入币对、保证金模式或 Matcher 分片
     * @return 从 0 开始的账户 Lane 编号
     */
    public int accountLaneId(long userId) {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        return (int) (mix64(userId ^ accountLaneSeed) & (accountLaneCount - 1L));
    }

    /**
     * 把一个用户的 Lane 编号转成位集合，方便派发任务时标记要访问哪个 Lane。
     * @param userId 需要处理账户的正数用户编号
     * @return 只有目标 Lane 对应的一位为 1 的掩码
     */
    public long accountLaneMask(long userId) {
        return 1L << accountLaneId(userId);
    }

    /**
     * 检查值在允许范围内并适合位掩码寻址；下界为 0 时也允许配置为 0。
     * @param value 要检查的数量或容量
     * @param minimum 允许的最小值，包含此值
     * @param maximum 允许的最大值，包含此值
     * @param name 配置项名称，用于指出是哪一项有误
     */
    private static void requirePowerOfTwo(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum || (value & (value - 1)) != 0) {
            throw new IllegalArgumentException(name + " must be a power of two in [" + minimum + ',' + maximum + ']');
        }
    }

    /**
     * 把用户编号与种子混合后的比特打散，减少连续用户编号造成的路由偏斜。
     * @param value 已经与路由种子组合过的用户编号
     * @return 用于截取 Lane 下标的混合值，不是新的业务编号
     */
    private static long mix64(long value) {
        // 只在本次计算中保存中间比特，不修改用户编号或账户状态。
        long mixed = value;
        mixed = (mixed ^ (mixed >>> 30)) * 0xbf58476d1ce4e5b9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94d049bb133111ebL;
        return mixed ^ (mixed >>> 31);
    }

}
