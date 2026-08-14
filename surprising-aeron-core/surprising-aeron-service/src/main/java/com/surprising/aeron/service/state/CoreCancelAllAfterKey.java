package com.surprising.aeron.service.state;

public record CoreCancelAllAfterKey(long userId, String symbolScope) implements Comparable<CoreCancelAllAfterKey> {
    public CoreCancelAllAfterKey {
        if (userId <= 0 || symbolScope == null || symbolScope.isBlank()) {
            throw new IllegalArgumentException("invalid cancel-all-after key");
        }
    }

    @Override
    public int compareTo(CoreCancelAllAfterKey other) {
        int userOrder = Long.compare(userId, other.userId);
        return userOrder != 0 ? userOrder : symbolScope.compareTo(other.symbolScope);
    }
}
