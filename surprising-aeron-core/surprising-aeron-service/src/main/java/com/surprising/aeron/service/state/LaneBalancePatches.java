package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.account.BalanceRuntime;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;

record BalanceState(long availableUnits, long lockedUnits, long pendingReservedUnits) {
    BalanceState {
        if (availableUnits < 0 || lockedUnits < 0 || pendingReservedUnits < 0
                || pendingReservedUnits > lockedUnits) {
            throw new IllegalArgumentException("invalid runtime balance state");
        }
    }
}

final class LaneBalancePatches {
    void mergeBefore(LaneBalancePatches source) {
        for (int index = 0; index < source.size; index++) {
            long userId = source.userIds[index];
            int assetId = source.assetIds[index];
            if (contains(userId, assetId)) continue;
            BalanceState before = source.before(index);
            BalanceRuntime value = before == null ? null
                    : new BalanceRuntime(userId, assetId, before.availableUnits(), before.lockedUnits());
            add(userId, assetId, value, before == null ? 0 : before.pendingReservedUnits());
        }
    }

    void mergeSequential(LaneBalancePatches source) {
        for (int sourceIndex = 0; sourceIndex < source.size; sourceIndex++) {
            long userId = source.userIds[sourceIndex];
            int assetId = source.assetIds[sourceIndex];
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

    /** 当前缓冲各项对应的用户 ID。 */
    long[] userIds = new long[8];
    /** 余额缓冲各项对应的资产内部 ID。 */
    int[] assetIds = new int[8];
    /** 变更前可用余额。 */
    long[] availableBefore = new long[8];
    /** 变更前冻结余额。 */
    long[] lockedBefore = new long[8];
    /** 变更前尚未完成的预留金额。 */
    long[] pendingBefore = new long[8];
    /** 该实体在变更前是否存在，区分不存在和零值。 */
    boolean[] presentBefore = new boolean[8];
    /** 变更后可用余额。 */
    long[] availableAfter = new long[8];
    /** 变更后冻结余额。 */
    long[] lockedAfter = new long[8];
    /** 变更后尚未完成的预留金额。 */
    long[] pendingAfter = new long[8];
    /** 该实体在变更后是否存在。 */
    boolean[] presentAfter = new boolean[8];
    /** 该实体是否已捕获变更后值。 */
    boolean[] capturedAfter = new boolean[8];
    /** 余额索引槽对应的用户 ID。 */
    long[] indexUserIds = new long[16];
    /** 余额索引槽对应的资产 ID。 */
    int[] indexAssetIds = new int[16];
    /** 索引槽对应的连续存储下标。 */
    int[] indexSlots = new int[16];
    /** 每个索引槽所属的清空代次，避免每次清零整张索引。 */
    int[] indexGenerations = new int[16];
    /** 当前有效代次；溢出时清空代次数组。 */
    int indexGeneration = 1;
    /** 当前有效元素数量。 */
    int size;

    int size() { return size; }
    long userId(int index) { return userIds[index]; }
    int assetId(int index) { return assetIds[index]; }
    BalanceState before(int index) {
        return !presentBefore[index] ? null : new BalanceState(
                availableBefore[index], lockedBefore[index], pendingBefore[index]);
    }

    BalanceState after(int index) {
        if (!capturedAfter[index]) {
            throw new IllegalStateException("balance mutation did not publish its lane after-state");
        }
        return !presentAfter[index] ? null : new BalanceState(
                availableAfter[index], lockedAfter[index], pendingAfter[index]);
    }

    boolean contains(long userId, int assetId) {
        return indexOf(userId, assetId) >= 0;
    }

    void add(long userId, int assetId, BalanceRuntime value, long pendingReservedUnits) {
        if (indexOf(userId, assetId) >= 0) {
            throw new IllegalStateException("balance capture key already exists");
        }
        ensureIndexCapacity(size + 1);
        if (size == userIds.length) {
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

    void after(long userId, int assetId, BalanceRuntime value, long pendingReservedUnits) {
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

    void clear() {
        if (size == 0) return;
        size = 0;
        if (++indexGeneration == 0) {
            java.util.Arrays.fill(indexGenerations, 0);
            indexGeneration = 1;
        }
    }

    void publishAvailableAt(TradingRuntimeState state, int index) {
        if (index < 0 || index >= size || !capturedAfter[index]) {
            throw new IllegalStateException("balance mutation did not publish its lane after-state");
        }
        long userId = userIds[index];
        int assetId = assetIds[index];
        if (state.realtimeCapture != null) state.realtimeCapture.balance(userId, assetId,
                presentAfter[index] ? availableAfter[index] : 0,
                presentAfter[index] ? lockedAfter[index] : 0);
        IntLongHashMap balances = state.publishedAvailableBalances.get(userId);
        if (!presentAfter[index]) {
            if (balances != null) {
                balances.removeKey(assetId);
                if (balances.isEmpty()) state.publishedAvailableBalances.remove(userId);
            }
            return;
        }
        if (balances == null) {
            balances = new IntLongHashMap();
            state.publishedAvailableBalances.put(userId, balances);
        }
        balances.put(assetId, availableAfter[index]);
    }

    int indexOf(long userId, int assetId) {
        int mask = indexSlots.length - 1;
        int position = pairHash(userId, assetId) & mask;
        while (indexGenerations[position] == indexGeneration) {
            if (indexUserIds[position] == userId && indexAssetIds[position] == assetId) {
                return indexSlots[position];
            }
            position = (position + 1) & mask;
        }
        return -1;
    }

    int emptyIndexPosition(long userId, int assetId) {
        int mask = indexSlots.length - 1;
        int position = pairHash(userId, assetId) & mask;
        while (indexGenerations[position] == indexGeneration) position = (position + 1) & mask;
        return position;
    }

    void ensureIndexCapacity(int requiredSize) {
        if (requiredSize <= indexSlots.length / 2) return;
        int capacity = Math.multiplyExact(indexSlots.length, 2);
        indexUserIds = new long[capacity];
        indexAssetIds = new int[capacity];
        indexSlots = new int[capacity];
        indexGenerations = new int[capacity];
        indexGeneration = 1;
        for (int index = 0; index < size; index++) {
            int position = emptyIndexPosition(userIds[index], assetIds[index]);
            indexUserIds[position] = userIds[index];
            indexAssetIds[position] = assetIds[index];
            indexSlots[position] = index;
            indexGenerations[position] = indexGeneration;
        }
    }

    static int pairHash(long userId, int assetId) {
        long value = userId ^ Integer.toUnsignedLong(assetId) * 0x9e3779b97f4a7c15L;
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        return (int) value;
    }
}
