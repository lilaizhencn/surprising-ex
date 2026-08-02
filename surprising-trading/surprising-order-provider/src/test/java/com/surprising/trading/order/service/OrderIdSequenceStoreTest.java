package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
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
}
