package com.surprising.aeron.service.state;


final class LaneClientOrderCaptures {
    /** 当前缓冲各项对应的用户 ID。 */
    long[] userIds = new long[4];
    /** 当前缓冲各项对应的内部客户单号键。 */
    long[] clientKeys = new long[4];
    /** 变更前客户单号指向的订单 ID。 */
    long[] beforeOrderIds = new long[4];
    /** 该实体在变更前是否存在，区分不存在和零值。 */
    boolean[] presentBefore = new boolean[4];
    /** 当前有效元素数量。 */
    int size;

    int size() { return size; }
    long userId(int index) { return userIds[index]; }
    long clientKey(int index) { return clientKeys[index]; }
    Long beforeOrderId(int index) {
        return presentBefore[index] ? beforeOrderIds[index] : null;
    }

    boolean contains(long userId, long clientKey) {
        for (int index = 0; index < size; index++) {
            if (userIds[index] == userId && clientKeys[index] == clientKey) return true;
        }
        return false;
    }

    void add(long userId, long clientKey, Long beforeOrderId) {
        if (userId <= 0 || clientKey <= 0) {
            throw new IllegalArgumentException("invalid client-order capture");
        }
        if (size == userIds.length) {
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

    void clear() {
        // 全部是 primitive 槽位，add 会覆盖存在性和值，无需逐项清零。
        size = 0;
    }

}
