package com.surprising.funding.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.funding.provider.model.FundingPaymentCandidate;
import com.surprising.funding.provider.model.FundingPaymentWrite;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class FundingPaymentRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void paymentPageUsesCachedNativeSequenceAndOneJdbcBatch() throws Exception {
        FundingPaymentRepository repository =
                new FundingPaymentRepository(jdbcTemplate, new FundingProperties());
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

        List<FundingPaymentWrite> writes =
                repository.insert(77L, List.of(payment), Instant.parse("2026-07-01T00:00:00Z"));

        assertThat(writes).singleElement().satisfies(write -> {
            assertThat(write.paymentId()).isEqualTo(9001L);
            assertThat(write.commandId()).isEqualTo("FUNDING:LINEAR_PERPETUAL:77:9001");
            assertThat(write.payment()).isEqualTo(payment);
        });
        verify(jdbcTemplate).batchUpdate(contains("INSERT INTO funding_payments"),
                any(BatchPreparedStatementSetter.class));
    }
}
