package com.surprising.eventstore;

import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.locks.ReentrantLock;

public final class PartitionOwnerLane<K> implements AutoCloseable {

    private static final int DEFAULT_QUEUE_CAPACITY = 65_536;
    private final List<Shard<K>> shards;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final ThreadLocal<Deque<K>> boundOwners = ThreadLocal.withInitial(ArrayDeque::new);
    private final List<ReentrantLock> ownershipLocks;

    public PartitionOwnerLane(int shardCount, String threadNamePrefix) {
        this(shardCount, DEFAULT_QUEUE_CAPACITY, threadNamePrefix);
    }

    public PartitionOwnerLane(int shardCount, int queueCapacity, String threadNamePrefix) {
        if (shardCount <= 0) {
            throw new IllegalArgumentException("shardCount must be positive");
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        if (threadNamePrefix == null || threadNamePrefix.isBlank()) {
            throw new IllegalArgumentException("threadNamePrefix must not be blank");
        }
        shards = new ArrayList<>(shardCount);
        ownershipLocks = new ArrayList<>(shardCount);
        for (int index = 0; index < shardCount; index++) {
            shards.add(new Shard<>(threadNamePrefix + "-" + index, queueCapacity));
            ownershipLocks.add(new ReentrantLock());
        }
        for (Shard<K> shard : shards) {
            shard.start();
        }
    }

    public PartitionOwnerLane(int shardCount) {
        this(shardCount, "partition-owner");
    }

    public PartitionOwnerLane(int shardCount, int queueCapacity) {
        this(shardCount, queueCapacity, "partition-owner");
    }

    public PartitionOwnerLane() {
        this(Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), 32)));
    }

    public <T> T execute(K key, Supplier<T> action) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(action, "action");
        if (!running.get()) {
            throw new IllegalStateException("partition owner lane is closed");
        }
        Deque<K> bound = boundOwners.get();
        if (!bound.isEmpty()) {
            K boundKey = bound.peek();
            if (Objects.equals(boundKey, key)) {
                return action.get();
            }
        }
        Shard<K> shard = shard(key);
        if (shard.isOwnerThread()) {
            return withOwnership(key, action);
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        shard.offer(new Task<>(() -> withOwnership(key, action), result, shard.pending));
        try {
            return result.join();
        } catch (CompletionException ex) {
            if (ex.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (ex.getCause() instanceof Error error) {
                throw error;
            }
            throw ex;
        }
    }

    public boolean isOwnerThread(K key) {
        Objects.requireNonNull(key, "key");
        Deque<K> bound = boundOwners.get();
        return (!bound.isEmpty() && Objects.equals(bound.peek(), key)) || shard(key).isOwnerThread();
    }

    public <T> T runAsOwner(K key, Supplier<T> action) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(action, "action");
        if (!running.get()) {
            throw new IllegalStateException("partition owner lane is closed");
        }
        Deque<K> bound = boundOwners.get();
        if (!bound.isEmpty() && !Objects.equals(bound.peek(), key)) {
            throw new IllegalStateException("nested partition owner binding is not allowed");
        }
        return withBoundOwner(key, action);
    }

    public int shardCount() {
        return shards.size();
    }

    private Shard<K> shard(K key) {
        return shards.get(Math.floorMod(key.hashCode(), shards.size()));
    }

    private <T> T withOwnership(K key, Supplier<T> action) {
        ReentrantLock lock = ownershipLocks.get(Math.floorMod(key.hashCode(), ownershipLocks.size()));
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    private <T> T withBoundOwner(K key, Supplier<T> action) {
        return withOwnership(key, () -> {
            Deque<K> bound = boundOwners.get();
            bound.push(key);
            try {
                return action.get();
            } finally {
                bound.pop();
                if (bound.isEmpty()) {
                    boundOwners.remove();
                }
            }
        });
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        for (Shard<K> shard : shards) {
            shard.wake();
        }
        for (Shard<K> shard : shards) {
            shard.awaitStop();
        }
    }

    private final class Shard<T> implements Runnable {

        private final ConcurrentLinkedQueue<Task<?>> queue = new ConcurrentLinkedQueue<>();
        private final AtomicInteger pending = new AtomicInteger();
        private final int queueCapacity;
        private final Thread thread;

        private Shard(String threadName, int queueCapacity) {
            this.queueCapacity = queueCapacity;
            thread = new Thread(this, threadName);
            thread.setDaemon(true);
        }

        private void start() {
            thread.start();
        }

        private boolean isOwnerThread() {
            return Thread.currentThread() == thread;
        }

        private void offer(Task<?> task) {
            reserve();
            queue.offer(task);
            LockSupport.unpark(thread);
        }

        private void reserve() {
            while (true) {
                if (!running.get()) {
                    throw new IllegalStateException("partition owner lane is closed");
                }
                int current = pending.get();
                if (current >= queueCapacity) {
                    throw new RejectedExecutionException("partition owner mailbox is full");
                }
                if (pending.compareAndSet(current, current + 1)) {
                    return;
                }
            }
        }

        private void wake() {
            LockSupport.unpark(thread);
        }

        private void awaitStop() {
            try {
                thread.join(5_000L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("partition owner lane shutdown interrupted", ex);
            }
        }

        @Override
        public void run() {
            while (running.get() || !queue.isEmpty()) {
                Task<?> task = queue.poll();
                if (task == null) {
                    LockSupport.parkNanos(1_000_000L);
                    continue;
                }
                task.run();
            }
        }
    }

    private static final class Task<T> {

        private final Supplier<T> action;
        private final CompletableFuture<T> result;
        private final AtomicInteger pending;

        private Task(Supplier<T> action, CompletableFuture<T> result, AtomicInteger pending) {
            this.action = action;
            this.result = result;
            this.pending = pending;
        }

        private void run() {
            try {
                result.complete(action.get());
            } catch (Throwable ex) {
                result.completeExceptionally(ex);
            } finally {
                pending.decrementAndGet();
            }
        }
    }
}
