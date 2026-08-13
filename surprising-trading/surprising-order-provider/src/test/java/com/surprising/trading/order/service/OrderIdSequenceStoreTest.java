package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class OrderIdSequenceStoreTest {

    @Test
    void sequenceRemainsUniqueAndMonotonicAfterReopen() throws Exception {
        Path directory = Files.createTempDirectory("order-id-sequence-");
        long first;
        long second;
        try (OrderIdSequenceStore store = new OrderIdSequenceStore(directory, 7)) {
            first = store.next();
            second = store.next();
        }

        try (OrderIdSequenceStore reopened = new OrderIdSequenceStore(directory, 7)) {
            long third = reopened.next();
            assertThat(second).isGreaterThan(first);
            assertThat(third).isGreaterThan(second);
            assertThat(first & 3L).isZero();
            assertThat(second & 3L).isZero();
            assertThat(third & 3L).isZero();
        }
    }

    @Test
    void concurrentGenerationKeepsIdsUnique() throws Exception {
        Path directory = Files.createTempDirectory("order-id-concurrent-");
        try (OrderIdSequenceStore store = new OrderIdSequenceStore(directory, 9)) {
            ExecutorService executor = Executors.newFixedThreadPool(8);
            List<Future<List<Long>>> futures = new ArrayList<>();
            try {
                for (int worker = 0; worker < 8; worker++) {
                    futures.add(executor.submit(() -> {
                        List<Long> ids = new ArrayList<>(1_000);
                        for (int index = 0; index < 1_000; index++) {
                            ids.add(store.next());
                        }
                        return ids;
                    }));
                }
                Set<Long> ids = new HashSet<>();
                for (Future<List<Long>> future : futures) {
                    ids.addAll(future.get(30, TimeUnit.SECONDS));
                }
                assertThat(ids).hasSize(8_000);
            } finally {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
        }
    }
}
