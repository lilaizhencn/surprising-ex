package com.surprising.gateway.provider.auth;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class GatewayUserMfaRepositoryTest {
    @Test
    void disablingClearsVerifiedStateSoNextEnrollmentMustRotateTheSecret() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(7L))).thenReturn(7L);
        GatewayUserMfaRepository repository = new GatewayUserMfaRepository(jdbc);
        Instant now = Instant.parse("2026-10-06T12:00:00Z");

        repository.disable(7L, now);

        verify(jdbc).update(contains("verified_at = NULL"), eq(Timestamp.from(now)), eq(7L));
    }
}
