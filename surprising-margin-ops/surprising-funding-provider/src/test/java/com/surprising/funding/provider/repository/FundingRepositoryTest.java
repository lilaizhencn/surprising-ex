package com.surprising.funding.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.funding.provider.model.FundingPaymentCandidate;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class FundingRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void completePaymentUsesConditionalBatchUpdateAndIncrementalSettlementCounters() throws Exception {
        FundingRepository repository = new FundingRepository(jdbcTemplate, new FundingProperties());
        when(jdbcTemplate.query(contains("WHERE command_id IN"), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet resultSet = mock(ResultSet.class);
                    when(resultSet.getLong("payment_id")).thenReturn(11L);
                    when(resultSet.getLong("settlement_id")).thenReturn(22L);
                    when(resultSet.getString("command_id")).thenReturn("funding-command");
                    when(resultSet.getLong("user_id")).thenReturn(33L);
                    when(resultSet.getString("status")).thenReturn("PENDING");
                    return List.of(mapper.mapRow(resultSet, 0));
                });
        when(jdbcTemplate.queryForObject(contains("WITH input"), eq(Integer.class), any(Object[].class)))
                .thenReturn(1);

        boolean changed = repository.completePayment("funding-command", 33L, "APPLIED",
                null, null, Instant.parse("2026-07-01T00:00:00Z"));

        assertThat(changed).isTrue();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(sql.capture(), eq(Integer.class), any(Object[].class));
        assertThat(sql.getValue())
                .contains("UPDATE funding_payments")
                .contains("UPDATE funding_settlements")
                .contains("applied_payment_count = s.applied_payment_count + c.applied_count")
                .contains("rejected_payment_count = s.rejected_payment_count + c.rejected_count")
                .doesNotContain("GROUP BY settlement_id\n                  ) counts");
    }

    @Test
    void duplicatePaymentResultIsIdempotentWithoutUpdatingCounters() throws Exception {
        FundingRepository repository = new FundingRepository(jdbcTemplate, new FundingProperties());
        when(jdbcTemplate.query(contains("WHERE command_id IN"), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet resultSet = mock(ResultSet.class);
                    when(resultSet.getLong("payment_id")).thenReturn(11L);
                    when(resultSet.getLong("settlement_id")).thenReturn(22L);
                    when(resultSet.getString("command_id")).thenReturn("funding-command");
                    when(resultSet.getLong("user_id")).thenReturn(33L);
                    when(resultSet.getString("status")).thenReturn("APPLIED");
                    return List.of(mapper.mapRow(resultSet, 0));
                });

        boolean changed = repository.completePayment("funding-command", 33L, "APPLIED",
                null, null, Instant.parse("2026-07-01T00:00:00Z"));

        assertThat(changed).isFalse();
        verify(jdbcTemplate, never()).queryForObject(contains("WITH input"), eq(Integer.class),
                any(Object[].class));
    }

    @Test
    void conflictingTerminalResultFailsClosed() throws Exception {
        FundingRepository repository = new FundingRepository(jdbcTemplate, new FundingProperties());
        when(jdbcTemplate.query(contains("WHERE command_id IN"), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet resultSet = mock(ResultSet.class);
                    when(resultSet.getLong("payment_id")).thenReturn(11L);
                    when(resultSet.getLong("settlement_id")).thenReturn(22L);
                    when(resultSet.getString("command_id")).thenReturn("funding-command");
                    when(resultSet.getLong("user_id")).thenReturn(33L);
                    when(resultSet.getString("status")).thenReturn("REJECTED");
                    return List.of(mapper.mapRow(resultSet, 0));
                });

        assertThatThrownBy(() -> repository.completePayment("funding-command", 33L, "APPLIED",
                null, null, Instant.parse("2026-07-01T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conflicting funding payment result");
    }

    @Test
    void paymentCandidatesUseStableCompositeKeysetCursor() {
        FundingRepository repository = new FundingRepository(jdbcTemplate, new FundingProperties());
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        FundingRepository.FundingSettlementWork settlement = new FundingRepository.FundingSettlementWork(
                22L, "BTC-USDT", Instant.parse("2026-07-01T00:00:00Z"), 100L,
                7L, 65_000L, new FundingRepository.FundingPaymentCursor(1001L, "CROSS", "NET"));

        FundingRepository.FundingPaymentPage page = repository.paymentCandidatesPage(settlement, 500);

        assertThat(page.items()).isEmpty();
        assertThat(page.hasMore()).isFalse();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getValue())
                .contains("(p.user_id, p.margin_mode, p.position_side) > (?, ?, ?)")
                .contains("ORDER BY p.user_id ASC, p.margin_mode ASC, p.position_side ASC")
                .contains("LIMIT ?");
        assertThat(args.getValue()).endsWith(1001L, "CROSS", "NET", 501);
    }

    @Test
    void paymentPageUsesCachedNativeSequenceAndOneJdbcBatch() throws Exception {
        FundingRepository repository = new FundingRepository(jdbcTemplate, new FundingProperties());
        when(jdbcTemplate.query(contains("nextval('funding_payment_id_seq')"),
                any(RowMapper.class), eq(1))).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getLong("payment_id")).thenReturn(9001L);
            return List.of(mapper.mapRow(resultSet, 0));
        });
        when(jdbcTemplate.batchUpdate(contains("INSERT INTO funding_payments"),
                any(BatchPreparedStatementSetter.class))).thenReturn(new int[]{1});
        FundingPaymentCandidate payment = new FundingPaymentCandidate(
                1001L, "BTC-USDT", MarginMode.CROSS, PositionSide.NET, "USDT",
                10L, 100_000L, 100L, -10L);

        List<FundingRepository.FundingPaymentWrite> writes =
                repository.insertPayments(77L, List.of(payment), Instant.parse("2026-07-01T00:00:00Z"));

        assertThat(writes).singleElement().satisfies(write -> {
            assertThat(write.paymentId()).isEqualTo(9001L);
            assertThat(write.commandId()).isEqualTo("FUNDING:LINEAR_PERPETUAL:77:9001");
            assertThat(write.payment()).isEqualTo(payment);
        });
        verify(jdbcTemplate).batchUpdate(contains("INSERT INTO funding_payments"),
                any(BatchPreparedStatementSetter.class));
    }
}
