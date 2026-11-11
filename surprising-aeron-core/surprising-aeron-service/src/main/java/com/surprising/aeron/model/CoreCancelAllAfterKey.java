package com.surprising.aeron.model;

/** 断线保护或倒计时撤单的查找键，用用户编号和币对范围定位一份定时撤单状态。 */
public record CoreCancelAllAfterKey(long userId, String symbolScope) implements Comparable<CoreCancelAllAfterKey> {
    /** 要求用户编号为正且币对范围不为空，避免生成无法定位的撤单定时器。 */
    public CoreCancelAllAfterKey {
        if (userId <= 0 || symbolScope == null || symbolScope.isBlank()) {
            throw new IllegalArgumentException("invalid cancel-all-after key");
        }
    }

    /** 先按用户编号、再按币对范围字符串排序，使定时撤单记录有固定遍历顺序。 */
    @Override
    public int compareTo(CoreCancelAllAfterKey other) {
        int userOrder = Long.compare(userId, other.userId);
        return userOrder != 0 ? userOrder : symbolScope.compareTo(other.symbolScope);
    }
}
