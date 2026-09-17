package com.surprising.aeron.service.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

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
}
