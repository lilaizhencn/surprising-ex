package com.surprising.aeron.service.state.account;

import com.surprising.aeron.service.state.TradingRuntimeState;

/**
 * 一个命令在各用户资产上捕获的余额前后值。连续原语数组保存金额和存在标记，用户加资产的原语索引用于定位；清空只换代次并保留容量。调用方必须按单个 Lane 或已完成交接的命令边界使用，不是第二份余额权威表。
 */
public final class LaneBalancePatches {
    /**
     * 只合入目标尚未捕获的用户资产前值，保留命令最早前值；不会从来源复制后值，缺失余额通过存在标记保存。
     * @param source 准备合入当前命令补丁的来源缓冲
     */
    public void mergeBefore(LaneBalancePatches source) {
        for (/* 当前待合并或重建索引的连续条目下标。 */ int index = 0; index < source.size; index++) {
            // 本条余额捕获对应的用户编号。
            long userId = source.userIds[index];
            // 本条余额捕获对应的资产整数编号。
            int assetId = source.assetIds[index];
            if (contains(userId, assetId)) continue;
            // 来源中已保存的不可变余额前值，null 表示不存在。
            BalanceState before = source.before(index);
            // 仅为沿用 add 前值捕获入口构造的余额值，后续保存为原语金额而不是长期持有该对象。
            BalanceRuntime value = before == null ? null
                    : new BalanceRuntime(userId, assetId, before.availableUnits(), before.lockedUnits());
            add(userId, assetId, value, before == null ? 0 : before.pendingReservedUnits());
        }
    }

    /**
     * 按命令顺序合并余额补丁，同一用户资产保留最早前值、采用来源最新后值及捕获标记；新键同时复制来源前值。
     * @param source 准备合入当前命令补丁的来源缓冲
     */
    public void mergeSequential(LaneBalancePatches source) {
        for (/* 本次来源缓冲的有效条目下标。 */ int sourceIndex = 0; sourceIndex < source.size; sourceIndex++) {
            // 本条余额捕获对应的用户编号。
            long userId = source.userIds[sourceIndex];
            // 本条余额捕获对应的资产整数编号。
            int assetId = source.assetIds[sourceIndex];
            // 当前组合键在目标连续存储中的下标，缺失时追加新项。
            int target = indexOf(userId, assetId);
            if (target < 0) {
                target = size;
                add(userId, assetId, null, 0);
                presentBefore[target] = source.presentBefore[sourceIndex];
                availableBefore[target] = source.availableBefore[sourceIndex];
                lockedBefore[target] = source.lockedBefore[sourceIndex];
                pendingBefore[target] = source.pendingBefore[sourceIndex];
            }
            presentAfter[target] = source.presentAfter[sourceIndex];
            capturedAfter[target] = source.capturedAfter[sourceIndex];
            availableAfter[target] = source.availableAfter[sourceIndex];
            lockedAfter[target] = source.lockedAfter[sourceIndex];
            pendingAfter[target] = source.pendingAfter[sourceIndex];
        }
    }

    /** 连续条目各自所属的用户编号，有效范围由 size 确定。 */
    long[] userIds = new long[8];
    /** 连续条目各自的资产整数编号。 */
    int[] assetIds = new int[8];
    /** 原余额存在时的命令前可用金额。 */
    long[] availableBefore = new long[8];
    /** 原余额存在时的命令前冻结金额。 */
    long[] lockedBefore = new long[8];
    /** 命令前冻结中的待完成预留合计。 */
    long[] pendingBefore = new long[8];
    /** 原余额是否存在，区分不存在与金额为零。 */
    boolean[] presentBefore = new boolean[8];
    /** 后值已捕获且存在时的可用金额，其他槽中数字可能为旧值。 */
    long[] availableAfter = new long[8];
    /** 后值已捕获且存在时的冻结金额。 */
    long[] lockedAfter = new long[8];
    /** 后值已捕获且存在时的待完成预留合计。 */
    long[] pendingAfter = new long[8];
    /** 后值捕获时该余额是否存在。 */
    boolean[] presentAfter = new boolean[8];
    /** 是否已捕获过后值，避免把未发布结果误当成余额删除。 */
    boolean[] capturedAfter = new boolean[8];
    /** 开放寻址索引槽中的用户键。 */
    long[] indexUserIds = new long[16];
    /** 开放寻址索引槽中的资产键。 */
    int[] indexAssetIds = new int[16];
    /** 索引槽指向连续金额数组的下标。 */
    int[] indexSlots = new int[16];
    /** 各索引槽所属的使用代次，旧代次相当于空槽。 */
    int[] indexGenerations = new int[16];
    /** 当前有效代次，int 加一绕回零时清空代次标记并重设为一。 */
    int indexGeneration = 1;
    /** 本轮有效连续条目数，数组容量在跨命令复用时保留。 */
    int size;

    /**
     * 返回本轮有效捕获条目数，超过此前缀的数组容量不参与发布。
     */
    public int size() { return size; }
    /**
     * 直接读取有效下标的用户编号，调用方负责下标范围。
     * @param index 本轮连续存储前缀中的下标
     */
    public long userId(int index) { return userIds[index]; }
    /**
     * 直接读取有效下标的资产整数编号。
     * @param index 本轮连续存储前缀中的下标
     */
    public int assetId(int index) { return assetIds[index]; }
    /**
     * 把捕获的前值金额物化为不可变 BalanceState；原余额不存在时返回 null，零金额和不存在通过标记区分。
     * @param index 本轮连续存储前缀中的下标
     */
    BalanceState before(int index) {
        return !presentBefore[index] ? null : new BalanceState(
                availableBefore[index], lockedBefore[index], pendingBefore[index]);
    }

    /**
     * 要求该条目已捕获后值，再把金额物化为不可变值；后值标记不存在时返回 null。
     * @param index 本轮连续存储前缀中的下标
     */
    public BalanceState after(int index) {
        if (!capturedAfter[index]) {
            throw new IllegalStateException("balance mutation did not publish its lane after-state");
        }
        return !presentAfter[index] ? null : new BalanceState(
                availableAfter[index], lockedAfter[index], pendingAfter[index]);
    }

    /**
     * 按用户及资产组合键检查本轮是否已有前值记录。
     * @param userId 被捕获余额所属用户编号
     * @param assetId 被捕获余额的资产整数编号
     */
    boolean contains(long userId, int assetId) {
        return indexOf(userId, assetId) >= 0;
    }

    /**
     * 拒绝重复键，必要时扩容原语数组和查找索引，追加余额前值并把后值标记重置为未捕获；余额不存在时旧数字槽不会被读取。
     * @param userId 被捕获余额所属用户编号
     * @param assetId 被捕获余额的资产整数编号
     * @param value 要捕获其金额的权威余额，null 表示该资产余额不存在
     * @param pendingReservedUnits 该余额冻结金额中尚待完成准入的预留合计
     */
    public void add(long userId, int assetId, BalanceRuntime value, long pendingReservedUnits) {
        if (indexOf(userId, assetId) >= 0) {
            throw new IllegalStateException("balance capture key already exists");
        }
        ensureIndexCapacity(size + 1);
        if (size == userIds.length) {
            // 当前需要扩大的数组或索引翻倍后的容量。
            int capacity = Math.multiplyExact(size, 2);
            userIds = java.util.Arrays.copyOf(userIds, capacity);
            assetIds = java.util.Arrays.copyOf(assetIds, capacity);
            availableBefore = java.util.Arrays.copyOf(availableBefore, capacity);
            lockedBefore = java.util.Arrays.copyOf(lockedBefore, capacity);
            pendingBefore = java.util.Arrays.copyOf(pendingBefore, capacity);
            presentBefore = java.util.Arrays.copyOf(presentBefore, capacity);
            availableAfter = java.util.Arrays.copyOf(availableAfter, capacity);
            lockedAfter = java.util.Arrays.copyOf(lockedAfter, capacity);
            pendingAfter = java.util.Arrays.copyOf(pendingAfter, capacity);
            presentAfter = java.util.Arrays.copyOf(presentAfter, capacity);
            capturedAfter = java.util.Arrays.copyOf(capturedAfter, capacity);
        }
        // 新组合键在本代查找索引中选定的空槽。
        int indexPosition = emptyIndexPosition(userId, assetId);
        userIds[size] = userId;
        assetIds[size] = assetId;
        capturedAfter[size] = false;
        presentAfter[size] = false;
        presentBefore[size] = value != null;
        if (value != null) {
            availableBefore[size] = value.availableUnits();
            lockedBefore[size] = value.lockedUnits();
            pendingBefore[size] = pendingReservedUnits;
        }
        indexUserIds[indexPosition] = userId;
        indexAssetIds[indexPosition] = assetId;
        indexSlots[indexPosition] = size;
        indexGenerations[indexPosition] = indexGeneration;
        size++;
    }

    /**
     * 要求此前已捕获前值，把当前余额及待完成预留复制为后值；null 表示该资产余额已经不存在。
     * @param userId 被捕获余额所属用户编号
     * @param assetId 被捕获余额的资产整数编号
     * @param value 要捕获其金额的权威余额，null 表示该资产余额不存在
     * @param pendingReservedUnits 该余额冻结金额中尚待完成准入的预留合计
     */
    public void after(long userId, int assetId, BalanceRuntime value, long pendingReservedUnits) {
        // 当前待合并或重建索引的连续条目下标。
        int index = indexOf(userId, assetId);
        if (index < 0) {
            throw new IllegalStateException("balance after-state is missing its before-state");
        }
        capturedAfter[index] = true;
        presentAfter[index] = value != null;
        if (value != null) {
            availableAfter[index] = value.availableUnits();
            lockedAfter[index] = value.lockedUnits();
            pendingAfter[index] = pendingReservedUnits;
        }
    }

    /**
     * 有条目时把有效长度清零并推进索引代次，不扫描金额数组；代次绕回零时才清空代次数组并重设为一。
     */
    public void clear() {
        if (size == 0) return;
        size = 0;
        if (++indexGeneration == 0) {
            java.util.Arrays.fill(indexGenerations, 0);
            indexGeneration = 1;
        }
    }

    /**
     * 要求下标有效且后值已捕获，再向实时捕获器交出已保存的可用及冻结金额；余额已不存在时发布零，不重新读取活跃账户。
     * @param state 提供实时捕获器的运行时，不从中重新读取余额
     * @param index 本轮连续存储前缀中的下标
     */
    public void publishAvailableAt(TradingRuntimeState state, int index) {
        if (index < 0 || index >= size || !capturedAfter[index]) {
            throw new IllegalStateException("balance mutation did not publish its lane after-state");
        }
        // 本条余额捕获对应的用户编号。
        long userId = userIds[index];
        // 本条余额捕获对应的资产整数编号。
        int assetId = assetIds[index];
        if (state.realtimeCapture() != null) state.realtimeCapture().balance(userId, assetId,
                presentAfter[index] ? availableAfter[index] : 0,
                presentAfter[index] ? lockedAfter[index] : 0);
    }

    /**
     * 在线性探测索引中按用户与资产查找本代有效项，返回连续数组下标，缺失返回 -1。
     * @param userId 被捕获余额所属用户编号
     * @param assetId 被捕获余额的资产整数编号
     */
    int indexOf(long userId, int assetId) {
        // 二次幂索引容量减一，用于限制探测位置范围。
        int mask = indexSlots.length - 1;
        // 当前线性探测或重建索引使用的槽位。
        int position = pairHash(userId, assetId) & mask;
        while (indexGenerations[position] == indexGeneration) {
            if (indexUserIds[position] == userId && indexAssetIds[position] == assetId) {
                return indexSlots[position];
            }
            position = (position + 1) & mask;
        }
        return -1;
    }

    /**
     * 从组合键哈希位置开始，寻找不属于当前代次的可写索引槽。
     * @param userId 被捕获余额所属用户编号
     * @param assetId 被捕获余额的资产整数编号
     */
    int emptyIndexPosition(long userId, int assetId) {
        // 二次幂索引容量减一，用于限制探测位置范围。
        int mask = indexSlots.length - 1;
        // 当前线性探测或重建索引使用的槽位。
        int position = pairHash(userId, assetId) & mask;
        while (indexGenerations[position] == indexGeneration) position = (position + 1) & mask;
        return position;
    }

    /**
     * 保持索引使用量不超过容量一半；需要时翻倍并只为当前有效连续条目重建查找索引。
     * @param requiredSize 索引需要容纳的有效条目数量
     */
    void ensureIndexCapacity(int requiredSize) {
        if (requiredSize <= indexSlots.length / 2) return;
        // 当前需要扩大的数组或索引翻倍后的容量。
        int capacity = Math.multiplyExact(indexSlots.length, 2);
        indexUserIds = new long[capacity];
        indexAssetIds = new int[capacity];
        indexSlots = new int[capacity];
        indexGenerations = new int[capacity];
        indexGeneration = 1;
        for (/* 当前待合并或重建索引的连续条目下标。 */ int index = 0; index < size; index++) {
            // 当前线性探测或重建索引使用的槽位。
            int position = emptyIndexPosition(userIds[index], assetIds[index]);
            indexUserIds[position] = userIds[index];
            indexAssetIds[position] = assetIds[index];
            indexSlots[position] = index;
            indexGenerations[position] = indexGeneration;
        }
    }

    /**
     * 把用户编号及无符号资产编号混合为查找哈希，只用于槽定位，不用于资金计算。
     * @param userId 被捕获余额所属用户编号
     * @param assetId 被捕获余额的资产整数编号
     */
    static int pairHash(long userId, int assetId) {
        // 由用户和资产混合得到的临时哈希累积值，乘法自然溢出属于哈希算法。
        long value = userId ^ Integer.toUnsignedLong(assetId) * 0x9e3779b97f4a7c15L;
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        return (int) value;
    }

    /**
     * 直接返回后值可用金额槽，调用方先检查 capturedAfter 和 presentAfter，避免读取复用槽的旧数字。
     * @param index 本轮连续存储前缀中的下标
     */
    public long availableAfter(int index) { return availableAfter[index]; }

    /**
     * 直接返回前值可用金额槽，调用方先检查 presentBefore。
     * @param index 本轮连续存储前缀中的下标
     */
    public long availableBefore(int index) { return availableBefore[index]; }

    /**
     * 检查本条目是否已经明确捕获后值，和余额后值是否存在是两件事。
     * @param index 本轮连续存储前缀中的下标
     */
    public boolean capturedAfter(int index) { return capturedAfter[index]; }

    /**
     * 直接返回后值冻结金额槽，读取前应确认后值已捕获且存在。
     * @param index 本轮连续存储前缀中的下标
     */
    public long lockedAfter(int index) { return lockedAfter[index]; }

    /**
     * 直接返回前值冻结金额槽，读取前应确认原余额存在。
     * @param index 本轮连续存储前缀中的下标
     */
    public long lockedBefore(int index) { return lockedBefore[index]; }

    /**
     * 返回捕获后值时该资产余额是否存在，不能单靠金额为零判断删除。
     * @param index 本轮连续存储前缀中的下标
     */
    public boolean presentAfter(int index) { return presentAfter[index]; }

    /**
     * 返回命令前该资产余额是否存在。
     * @param index 本轮连续存储前缀中的下标
     */
    public boolean presentBefore(int index) { return presentBefore[index]; }

}
