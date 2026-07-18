package com.surprising.account.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.product.api.ProductLine;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class AccountTradeSettlementMonitorRepositoryTest {

    @Test
    void reconcilesCompletedPairsInBoundedBatchesAndReadsOnlyPendingSides() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AccountTradeSettlementMonitorRepository repository =
                new AccountTradeSettlementMonitorRepository(jdbcTemplate);
        Instant now = Instant.parse("2026-07-19T00:00:00Z");
        Timestamp checkedAt = Timestamp.from(now);
        Timestamp cutoff = Timestamp.from(now.minusSeconds(45));
        Timestamp oldest = Timestamp.from(now.minusSeconds(90));

        when(jdbcTemplate.query(any(String.class), any(ResultSetExtractor.class),
                eq("LINEAR_PERPETUAL"), eq(checkedAt), eq("LINEAR_PERPETUAL"), eq(cutoff)))
                .thenAnswer(invocation -> {
                    ResultSetExtractor<?> extractor = invocation.getArgument(1);
                    ResultSet resultSet = mock(ResultSet.class);
                    when(resultSet.next()).thenReturn(true);
                    when(resultSet.getLong("incomplete_count")).thenReturn(2L);
                    when(resultSet.getTimestamp("oldest_created_at")).thenReturn(oldest);
                    return extractor.extractData(resultSet);
                });

        var snapshot = repository.staleIncomplete(
                ProductLine.LINEAR_PERPETUAL, Duration.ofSeconds(45), now);

        assertThat(snapshot.count()).isEqualTo(2L);
        assertThat(snapshot.oldestCreatedAt()).isEqualTo(oldest.toInstant());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(ResultSetExtractor.class),
                eq("LINEAR_PERPETUAL"), eq(checkedAt), eq("LINEAR_PERPETUAL"), eq(cutoff));
        assertThat(sql.getValue())
                .contains("reconciled_at IS NULL")
                .contains("HAVING COUNT(*) = 2")
                .contains("LIMIT 1000")
                .contains("UPDATE account_trade_settlement_sides")
                .contains("HAVING COUNT(*) < 2");
    }
}
