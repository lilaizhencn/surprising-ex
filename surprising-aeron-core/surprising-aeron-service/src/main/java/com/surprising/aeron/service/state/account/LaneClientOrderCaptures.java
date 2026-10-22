package com.surprising.aeron.service.state.account;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.Accessors;
/**
 * 一个命令中用户加客户身份键的原订单指向捕获。用连续原语数组保存前值及存在标记，供回滚恢复正反向索引；此缓冲要求客户键为正，与允许零别名的其他索引契约不同。
 */
public final class LaneClientOrderCaptures {
    /** 各捕获项对应的用户编号。 */
    long[] userIds = new long[4];
    /** 各捕获项对应的正数客户身份键。 */
    long[] clientKeys = new long[4];
    /** 原映射存在时的订单编号，缺失时写零并以 presentBefore 区分。 */
    long[] beforeOrderIds = new long[4];
    /** 原映射是否存在，不把零数字槽误当成有效关联。 */
    boolean[] presentBefore = new boolean[4];
    /**  当前命令有效捕获项数量。 返回当前命令捕获的有效客户映射条数。 */
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    int size;

    /**
     * 读取有效捕获项所属用户编号。
     * @param index 当前有效捕获项下标
     */
    long userId(int index) { return userIds[index]; }
    /**
     * 读取有效捕获项的正数客户身份键。
     * @param index 当前有效捕获项下标
     */
    long clientKey(int index) { return clientKeys[index]; }
    /**
     * 原映射存在时返回原订单编号，否则返回 null，区分不存在与数字槽旧内容。
     * @param index 当前有效捕获项下标
     */
    Long beforeOrderId(int index) {
        return presentBefore[index] ? beforeOrderIds[index] : null;
    }

    /**
     * 只遍历有效前缀，检查该用户客户键组合是否已捕获。
     * @param userId 原客户映射所属的正数用户编号
     * @param clientKey 需要记录前值的正数客户身份键
     */
    boolean contains(long userId, long clientKey) {
        for (/* 当前顺序检查的有效捕获下标。 */ int index = 0; index < size; index++) {
            if (userIds[index] == userId && clientKeys[index] == clientKey) return true;
        }
        return false;
    }

    /**
     * 校验正数用户和客户键，必要时翻倍数组并追加原订单指向；本方法不去重，首次捕获检查由外层负责。
     * @param userId 原客户映射所属的正数用户编号
     * @param clientKey 需要记录前值的正数客户身份键
     * @param beforeOrderId 原来指向的订单编号，null 表示该映射不存在
     */
    void add(long userId, long clientKey, Long beforeOrderId) {
        if (userId <= 0 || clientKey <= 0) {
            throw new IllegalArgumentException("invalid client-order capture");
        }
        if (size == userIds.length) {
            // 当前连续数组长度精确翻倍后的容量。
            int capacity = Math.multiplyExact(size, 2);
            userIds = java.util.Arrays.copyOf(userIds, capacity);
            clientKeys = java.util.Arrays.copyOf(clientKeys, capacity);
            beforeOrderIds = java.util.Arrays.copyOf(beforeOrderIds, capacity);
            presentBefore = java.util.Arrays.copyOf(presentBefore, capacity);
        }
        userIds[size] = userId;
        clientKeys[size] = clientKey;
        presentBefore[size] = beforeOrderId != null;
        beforeOrderIds[size] = beforeOrderId == null ? 0 : beforeOrderId;
        size++;
    }

    /**
     * 把有效长度归零，保留原语数组容量及无效前缀旧数字，后续依 size 和存在标记读取。
     */
    void clear() {
        size = 0;
    }
}
