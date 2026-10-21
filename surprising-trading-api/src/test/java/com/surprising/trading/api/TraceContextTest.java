package com.surprising.trading.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TraceContextTest {

    @AfterEach
    void clearTraceContext() {
        TraceContext.clear();
    }

    @Test
    void currentOrCreateReusesCurrentTraceId() {
        TraceContext.set(" trace-123 ");

        assertThat(TraceContext.currentOrCreate()).isEqualTo("trace-123");
        assertThat(TraceContext.currentOrCreate()).isEqualTo("trace-123");
    }

    @Test
    void invalidTraceIdIsReplaced() {
        String normalized = TraceContext.normalizeOrCreate("bad trace id");

        assertThat(normalized).isNotEqualTo("bad trace id");
        assertThat(normalized).isNotBlank();
    }
}
