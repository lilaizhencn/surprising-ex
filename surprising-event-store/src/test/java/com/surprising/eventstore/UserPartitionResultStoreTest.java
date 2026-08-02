package com.surprising.eventstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import com.surprising.product.api.ProductLine;

class UserPartitionResultStoreTest {

    @Test
    void syncResultIsIdempotentAndSurvivesReopen() throws Exception {
        Path directory = Files.createTempDirectory("user-result-store-");
        UserPartitionKey partition = new UserPartitionKey(ProductLine.LINEAR_PERPETUAL, 1001L);
        try (UserPartitionResultStore store = new UserPartitionResultStore(directory)) {
            store.put(partition, "command-1", "{\"status\":\"APPLIED\"}".getBytes());
            store.put(partition, "command-1", "{\"status\":\"APPLIED\"}".getBytes());
            assertThat(store.read(partition, "command-1")).isPresent()
                    .get().satisfies(value -> assertThat(new String(value)).isEqualTo("{\"status\":\"APPLIED\"}"));
            assertThatThrownBy(() -> store.put(partition, "command-1", "{\"status\":\"REJECTED\"}".getBytes()))
                    .isInstanceOf(IllegalStateException.class);
        }
        try (UserPartitionResultStore reopened = new UserPartitionResultStore(directory)) {
            assertThat(reopened.read(partition, "command-1")).isPresent()
                    .get().satisfies(value -> assertThat(new String(value)).isEqualTo("{\"status\":\"APPLIED\"}"));
        }
    }

    @Test
    void concurrentSameResultWritesRemainIdempotent() throws Exception {
        Path directory = Files.createTempDirectory("user-result-store-race-");
        byte[] result = "{\"status\":\"APPLIED\"}".getBytes();
        UserPartitionKey partition = new UserPartitionKey(ProductLine.LINEAR_PERPETUAL, 1001L);
        try (UserPartitionResultStore store = new UserPartitionResultStore(directory);
             ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> store.put(partition, "command-race", result));
            var second = executor.submit(() -> store.put(partition, "command-race", result));
            first.get();
            second.get();
            assertThat(store.read(partition, "command-race")).contains(result);
        }
    }

    @Test
    void sameCommandIdCanBeUsedByDifferentUserPartitions() throws Exception {
        Path directory = Files.createTempDirectory("user-result-store-partitions-");
        UserPartitionKey first = new UserPartitionKey(ProductLine.LINEAR_PERPETUAL, 1001L);
        UserPartitionKey second = new UserPartitionKey(ProductLine.LINEAR_PERPETUAL, 1002L);
        try (UserPartitionResultStore store = new UserPartitionResultStore(directory)) {
            store.put(first, "same-command", "{\"user\":1001}".getBytes());
            store.put(second, "same-command", "{\"user\":1002}".getBytes());
            assertThat(store.read(first, "same-command")).contains("{\"user\":1001}".getBytes());
            assertThat(store.read(second, "same-command")).contains("{\"user\":1002}".getBytes());
        }
    }
}
