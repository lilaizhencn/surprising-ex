package com.surprising.eventstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class UserPartitionResultStoreTest {

    @Test
    void syncResultIsIdempotentAndSurvivesReopen() throws Exception {
        Path directory = Files.createTempDirectory("user-result-store-");
        try (UserPartitionResultStore store = new UserPartitionResultStore(directory)) {
            store.put("command-1", "{\"status\":\"APPLIED\"}".getBytes());
            store.put("command-1", "{\"status\":\"APPLIED\"}".getBytes());
            assertThat(store.read("command-1")).isPresent()
                    .get().satisfies(value -> assertThat(new String(value)).isEqualTo("{\"status\":\"APPLIED\"}"));
            assertThatThrownBy(() -> store.put("command-1", "{\"status\":\"REJECTED\"}".getBytes()))
                    .isInstanceOf(IllegalStateException.class);
        }
        try (UserPartitionResultStore reopened = new UserPartitionResultStore(directory)) {
            assertThat(reopened.read("command-1")).isPresent()
                    .get().satisfies(value -> assertThat(new String(value)).isEqualTo("{\"status\":\"APPLIED\"}"));
        }
    }

    @Test
    void concurrentSameResultWritesRemainIdempotent() throws Exception {
        Path directory = Files.createTempDirectory("user-result-store-race-");
        byte[] result = "{\"status\":\"APPLIED\"}".getBytes();
        try (UserPartitionResultStore store = new UserPartitionResultStore(directory);
             ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> store.put("command-race", result));
            var second = executor.submit(() -> store.put("command-race", result));
            first.get();
            second.get();
            assertThat(store.read("command-race")).contains(result);
        }
    }
}
