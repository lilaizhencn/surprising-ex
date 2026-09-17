package com.surprising.aeron.service.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class ResponseArenaTest {
    @Test
    void releasesAndReusesBoundedSlotsWithoutGrowingForCommonResponses() {
        ResponseArena arena = new ResponseArena(2, 16);
        byte[] first = arena.acquire(8);
        byte[] second = arena.acquire(12);
        byte[] fallback = arena.acquire(8);

        assertThat(first).hasSize(16);
        assertThat(second).hasSize(16);
        assertThat(fallback).hasSize(8);

        arena.release(first);
        byte[] reused = arena.acquire(8);
        assertThat(reused).isSameAs(first);
        arena.release(second);
        arena.release(reused);
        arena.clear();
        assertThat(arena.acquire(12)).isIn(first, second);
    }

    @Test
    void largeResponsesUseExactFallbackStorage() {
        ResponseArena arena = new ResponseArena(1, 16);
        byte[] response = arena.acquire(17);
        assertThat(response).hasSize(17);
        arena.release(response);
        assertThat(arena.acquire(16)).isNotSameAs(response);
    }

    @Test
    void keepsSlotLeasedUntilLedgerAndTransportReferencesAreReleased() {
        ResponseArena arena = new ResponseArena(1, 16);
        byte[] response = arena.acquire(8);
        arena.retain(response);

        arena.release(response);
        byte[] stillLeased = arena.acquire(8);
        assertThat(stillLeased).hasSize(8).isNotSameAs(response);

        arena.release(response);
        assertThat(arena.acquire(8)).isSameAs(response);
    }

    @Test
    void concurrentLaneClaimsNeverShareAResponseSlot() throws Exception {
        ResponseArena arena = new ResponseArena(64, 16);
        ExecutorService lanes = Executors.newFixedThreadPool(4);
        try {
            List<Callable<byte[]>> claims = new ArrayList<>();
            for (int index = 0; index < 64; index++) claims.add(() -> arena.acquire(8));
            List<byte[]> responses = new ArrayList<>();
            for (var future : lanes.invokeAll(claims)) responses.add(future.get());
            assertThat(new HashSet<>(responses)).hasSize(64);
            for (byte[] response : responses) arena.release(response);
        } finally {
            lanes.shutdownNow();
        }
    }
}
