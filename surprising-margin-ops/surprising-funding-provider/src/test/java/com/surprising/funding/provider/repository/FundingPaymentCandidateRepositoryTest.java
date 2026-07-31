package com.surprising.funding.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.funding.provider.model.FundingPaymentCursor;
import com.surprising.funding.provider.model.FundingPaymentPage;
import com.surprising.funding.provider.model.FundingSettlementWork;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class FundingPaymentCandidateRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void paymentCandidatesUseStableCompositeKeysetCursor() {
        FundingPaymentCandidateRepository repository =
                new FundingPaymentCandidateRepository(jdbcTemplate, new FundingProperties());
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        FundingSettlementWork settlement = new FundingSettlementWork(
                22L, "BTC-USDT", Instant.parse("2026-07-01T00:00:00Z"), 100L,
                7L, 65_000L, new FundingPaymentCursor(1001L, "CROSS", "NET"));

        FundingPaymentPage page = repository.findPage(settlement, 500);

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
}
