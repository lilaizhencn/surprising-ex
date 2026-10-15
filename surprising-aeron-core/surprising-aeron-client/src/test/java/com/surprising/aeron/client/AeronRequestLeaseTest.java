package com.surprising.aeron.client;

import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.product.api.ProductLine;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AeronRequestLeaseTest {
    @Test void cancellationAndOfferHaveOneWinner() throws Exception {
        try (var slots = new Slots(); var threads = Executors.newFixedThreadPool(2)) {
            Object request = slots.acquire();
            slots.holdOtherSlots();
            var start = new CyclicBarrier(3);
            var finish = new CyclicBarrier(3);
            boolean[] results = new boolean[2];
            long[] generation = new long[1];
            var offer = threads.submit(() -> {
                for (int round = 0; round < 300; round++) {
                    start.await(3, TimeUnit.SECONDS);
                    results[0] = (boolean) call(request, "beginOffer");
                    finish.await(3, TimeUnit.SECONDS);
                }
                return null;
            });
            var cancel = threads.submit(() -> {
                for (int round = 0; round < 300; round++) {
                    start.await(3, TimeUnit.SECONDS);
                    results[1] = (boolean) call(request, "cancelBeforeOffer", generation[0]);
                    finish.await(3, TimeUnit.SECONDS);
                }
                return null;
            });
            for (int round = 0; round < 300; round++) {
                generation[0] = (long) call(request, "generation");
                start.await(3, TimeUnit.SECONDS);
                finish.await(3, TimeUnit.SECONDS);
                assertThat(results[0] ^ results[1]).as("one admission decision in round %s", round).isTrue();
                call(request, "recycle");
                assertThat(slots.acquire()).isSameAs(request);
            }
            offer.get(3, TimeUnit.SECONDS);
            cancel.get(3, TimeUnit.SECONDS);
            call(request, "recycle");
        }
    }

    @Test void staleCancellationCannotTouchHundredsOfReusedGenerations() throws Exception {
        try (var slots = new Slots()) {
            Object request = slots.acquire(); slots.holdOtherSlots();
            long old = (long) call(request, "generation");
            assertThat(call(request, "beginOffer")).isEqualTo(true);
            assertThat(call(request, "cancelBeforeOffer", old)).isEqualTo(false);
            call(request, "recycle");
            for (int round = 0; round < 1_000; round++) {
                assertThat(slots.acquire()).isSameAs(request);
                assertThat(call(request, "cancelBeforeOffer", old)).isEqualTo(false);
                assertThat(call(request, "beginOffer")).isEqualTo(true);
                call(request, "recycle");
            }
        }
    }

    @Test void queryRetryRetainsGenerationAndCompletionCanOnlyBeClaimedOnce() throws Exception {
        try (var slots = new Slots()) {
            Object request = slots.acquire(); slots.holdOtherSlots();
            long generation = (long) call(request, "generation");
            assertThat(call(request, "beginOffer")).isEqualTo(true);
            call(request, "prepareForRetry");
            assertThat(call(request, "generation")).isEqualTo(generation);
            assertThat(call(request, "cancelBeforeOffer", generation)).isEqualTo(true);
            assertThat(call(request, "beginOffer")).isEqualTo(false);
            assertThat(call(request, "claimCompletion")).isEqualTo(true);
            assertThat(call(request, "claimCompletion")).isEqualTo(false);
            // The completion claimant returns the slot; this test deliberately owns that claim.
            call(slots.lane, "recycle", request);
            assertThat(slots.acquire()).isSameAs(request);
            assertThat(call(request, "generation")).isEqualTo(generation + 1);
            call(request, "recycle");
        }
    }

    private static Object call(Object target, String name, Object... args) throws Exception {
        Method selected = java.util.Arrays.stream(target.getClass().getDeclaredMethods())
                .filter(method -> method.getName().equals(name) && method.getParameterCount() == args.length)
                .findFirst().orElseThrow();
        selected.setAccessible(true);
        return selected.invoke(target, args);
    }

    private static final class Slots implements AutoCloseable {
        private final AeronClientPool pool = new AeronClientPool("lease", ProductLine.SPOT,
                List.of("localhost", "localhost", "localhost"), "localhost", Duration.ofSeconds(1),
                "lease", "epoch", new AeronClientCapacity(1, 1, 2, 2, 2, 2, 32), () -> null, false);
        private final Object lane;
        private final java.util.List<Object> held = new java.util.ArrayList<>();
        Slots() throws Exception {
            var field = AeronClientPool.class.getDeclaredField("commandAgents"); field.setAccessible(true);
            lane = ((Object[]) field.get(pool))[0];
        }
        Object acquire() throws Exception {
            return call(lane, "oneWayFutureRequest", CoreMessageType.PLACE_ORDER, UUID.randomUUID(), 7L, new byte[0]);
        }
        void holdOtherSlots() throws Exception {
            Object next;
            while ((next = acquire()) != null) held.add(next);
        }
        public void close() { pool.close(); }
    }
}
