package com.surprising.eventstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PartitionOwnerLaneTest {

    @Test
    void sameKeyUsesOneOwnerAndPreservesExecutionOrder() throws Exception {
        try (PartitionOwnerLane<String> lane = new PartitionOwnerLane<>(2, 128, "owner-test");
             ExecutorService executor = Executors.newFixedThreadPool(8)) {
            AtomicInteger counter = new AtomicInteger();
            List<Integer> executionOrder = new ArrayList<>();
            List<Future<Integer>> futures = new ArrayList<>();
            for (int index = 0; index < 32; index++) {
                futures.add(executor.submit(() -> lane.execute("same-key", () -> {
                    int sequence = counter.incrementAndGet();
                    executionOrder.add(sequence);
                    return sequence;
                })));
            }

            for (Future<Integer> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }

            assertThat(executionOrder).containsExactlyElementsOf(
                    java.util.stream.IntStream.rangeClosed(1, 32).boxed().toList());
            assertThat(lane.isOwnerThread("same-key")).isFalse();
        }
    }

    @Test
    void differentKeysCanRunOnDifferentOwners() throws Exception {
        try (PartitionOwnerLane<String> lane = new PartitionOwnerLane<>(2, 16, "owner-test");
             ExecutorService executor = Executors.newFixedThreadPool(2)) {
            String first = "key-0";
            String second = java.util.stream.Stream.iterate(1, value -> value + 1)
                    .map(value -> "key-" + value)
                    .filter(value -> Math.floorMod(first.hashCode(), 2) != Math.floorMod(value.hashCode(), 2))
                    .findFirst()
                    .orElseThrow();
            CountDownLatch started = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            Set<String> ownerThreads = java.util.concurrent.ConcurrentHashMap.newKeySet();
            Future<Void> firstFuture = executor.submit(() -> lane.execute(first, () -> {
                ownerThreads.add(Thread.currentThread().getName());
                started.countDown();
                await(release);
                return null;
            }));
            Future<Void> secondFuture = executor.submit(() -> lane.execute(second, () -> {
                ownerThreads.add(Thread.currentThread().getName());
                started.countDown();
                await(release);
                return null;
            }));

            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            firstFuture.get(10, TimeUnit.SECONDS);
            secondFuture.get(10, TimeUnit.SECONDS);
            assertThat(ownerThreads).hasSize(2);
        }
    }

    @Test
    void preservesActionExceptionType() {
        try (PartitionOwnerLane<String> lane = new PartitionOwnerLane<>(1, 8, "owner-test")) {
            assertThatThrownBy(() -> lane.execute("key", () -> {
                throw new IllegalStateException("expected");
            })).isInstanceOf(IllegalStateException.class).hasMessage("expected");
        }
    }

    @Test
    void rejectsWhenOwnerMailboxCapacityIsReached() throws Exception {
        try (PartitionOwnerLane<String> lane = new PartitionOwnerLane<>(1, 1, "owner-test");
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Future<Void> running = executor.submit(() -> lane.execute("key", () -> {
                started.countDown();
                await(release);
                return null;
            }));

            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> lane.execute("key", () -> null))
                    .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
            release.countDown();
            running.get(10, TimeUnit.SECONDS);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("owner test did not release");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("owner test interrupted", ex);
        }
    }
}
