package com.surprising.aeron.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.product.api.ProductLine;
import io.aeron.driver.MediaDriver;
import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class AeronClientPoolTest {

    @Test
    void rejectsInvalidConfigurationBeforeOpeningConnections() {
        assertThatIllegalArgumentException().isThrownBy(() -> new AeronClientPool("order", ProductLine.SPOT,
                List.of("localhost"), "localhost", Duration.ofSeconds(1), 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new AeronClientPool("order", ProductLine.SPOT,
                List.of("localhost", "localhost", "localhost"), "localhost", Duration.ZERO, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new AeronClientPool("order", ProductLine.SPOT,
                List.of("localhost", "localhost", "localhost"), "localhost", Duration.ofSeconds(1), 0));
    }

    @Test
    void rejectsMessageTypesWithTheWrongWireKindWithoutConnecting() {
        try (AeronClientPool pool = pool()) {
            assertThatIllegalArgumentException().isThrownBy(() -> pool.command(CoreMessageType.USER_STATE_QUERY,
                    UUID.randomUUID(), 1, new byte[0]));
            assertThatIllegalArgumentException().isThrownBy(() -> pool.query(CoreMessageType.ADJUST_BALANCE,
                    UUID.randomUUID(), 1, new byte[0]));
        }
    }

    @Test
    void closesOnceAndRejectsNewRequestsWithoutOpeningAeron() {
        AeronClientPool pool = pool();
        pool.close();
        pool.close();

        assertThatIllegalStateException().isThrownBy(() -> pool.query(CoreMessageType.USER_STATE_QUERY,
                UUID.randomUUID(), 1, new byte[0]));
    }

    @Test
    void initializesOneMediaDriverWhenSlotsRaceOnFirstUse() throws Exception {
        try (AeronClientPool pool = pool(4)) {
            var method = AeronClientPool.class.getDeclaredMethod("sharedMediaDriver");
            method.setAccessible(true);
            int workers = 8;
            ExecutorService executor = Executors.newFixedThreadPool(workers);
            CountDownLatch ready = new CountDownLatch(workers);
            CountDownLatch start = new CountDownLatch(1);
            try {
                List<Future<MediaDriver>> futures = java.util.stream.IntStream.range(0, workers)
                        .mapToObj(index -> executor.submit(() -> {
                            ready.countDown();
                            start.await();
                            try {
                                return (MediaDriver) method.invoke(pool);
                            } catch (InvocationTargetException exception) {
                                Throwable cause = exception.getCause();
                                if (cause instanceof RuntimeException runtimeException) {
                                    throw runtimeException;
                                }
                                throw exception;
                            }
                        }))
                        .toList();
                assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                start.countDown();
                Set<MediaDriver> drivers = futures.stream().map(future -> {
                    try {
                        return future.get();
                    } catch (Exception exception) {
                        throw new RuntimeException(exception);
                    }
                }).collect(java.util.stream.Collectors.toSet());
                assertThat(drivers).hasSize(1);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private static AeronClientPool pool() {
        return pool(1);
    }

    private static AeronClientPool pool(int clientConnections) {
        return new AeronClientPool("test", ProductLine.SPOT,
                List.of("localhost", "localhost", "localhost"), "localhost", Duration.ofSeconds(1),
                clientConnections);
    }
}
