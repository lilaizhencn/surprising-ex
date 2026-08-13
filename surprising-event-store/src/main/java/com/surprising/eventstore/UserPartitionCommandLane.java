package com.surprising.eventstore;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * JVM 内按用户分区的单写入队列。
 * Kafka 同一分区保证跨节点顺序，本地 Owner 线程保证同一节点上的批量回调不会并发修改同一用户状态。
 */
public final class UserPartitionCommandLane implements AutoCloseable {

    private final PartitionOwnerLane<UserPartitionKey> owners;

    public UserPartitionCommandLane() {
        this(new PartitionOwnerLane<>(Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), 32)),
                "user-partition-owner"));
    }

    public UserPartitionCommandLane(int shardCount) {
        this(new PartitionOwnerLane<>(shardCount, "user-partition-owner"));
    }

    public UserPartitionCommandLane(PartitionOwnerLane<UserPartitionKey> owners) {
        this.owners = Objects.requireNonNull(owners, "owners");
    }

    public <T> T execute(UserPartitionKey partition, Supplier<T> action) {
        return owners.execute(partition, action);
    }

    public boolean isOwnerThread(UserPartitionKey partition) {
        return owners.isOwnerThread(partition);
    }

    public <T> T runAsOwner(UserPartitionKey partition, Supplier<T> action) {
        return owners.runAsOwner(partition, action);
    }

    @Override
    public void close() {
        owners.close();
    }
}
