package com.surprising.eventstore;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * JVM 内按用户分区的单写入队列。
 * Kafka 同一分区保证跨节点顺序，本地锁保证同一节点上的批量回调不会并发修改同一用户状态。
 */
public final class UserPartitionCommandLane {

    private final ConcurrentHashMap<UserPartitionKey, ReentrantLock> locks = new ConcurrentHashMap<>();

    public <T> T execute(UserPartitionKey partition, Supplier<T> action) {
        Objects.requireNonNull(partition, "partition");
        Objects.requireNonNull(action, "action");
        ReentrantLock lock = locks.computeIfAbsent(partition, ignored -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
