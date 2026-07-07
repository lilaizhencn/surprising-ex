package com.surprising.funding.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.product.api.ProductLine;
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
class FundingOutboxRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private FundingRepository fundingRepository;

    @Test
    void enqueueFailsWhenOutboxInsertIsSkipped() {
        FundingOutboxRepository repository = new FundingOutboxRepository(jdbcTemplate, fundingRepository);
        when(fundingRepository.nextSequence("funding-outbox")).thenReturn(901L);
        when(jdbcTemplate.update(contains("INSERT INTO funding_outbox_events"), any(Object[].class)))
                .thenReturn(0);

        assertThatThrownBy(() -> repository.enqueue("funding-topic", "BTC-USDT", "FUNDING_RATE",
                "{}", Instant.parse("2026-07-01T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("funding outbox enqueue");
    }

    @Test
    void markPublishedFailsWhenNoRowIsUpdated() {
        FundingOutboxRepository repository = new FundingOutboxRepository(jdbcTemplate, fundingRepository);
        when(jdbcTemplate.update(contains("SET published_at"), any(), any(), eq(901L)))
                .thenReturn(0);

        assertThatThrownBy(() -> repository.markPublished(901L, Instant.parse("2026-07-01T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("funding outbox publish mark");
    }

    @Test
    void lockPendingSelectsUnpublishedDueRowsForRetry() {
        FundingOutboxRepository repository = new FundingOutboxRepository(jdbcTemplate, fundingRepository);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), eq(100))).thenReturn(List.of());

        repository.lockPending(100);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), eq(100));
        assertThat(sql.getValue())
                .contains("published_at IS NULL")
                .contains("next_attempt_at <= now()")
                .contains("FOR UPDATE SKIP LOCKED");
    }

    @Test
    void lockPendingFiltersByFundingProductTopicWhenProductTopicsAreEnabled() {
        FundingProperties properties = new FundingProperties();
        properties.getKafka().setProductLine(ProductLine.INVERSE_PERPETUAL);
        properties.getKafka().setProductTopicsEnabled(true);
        FundingOutboxRepository repository = new FundingOutboxRepository(jdbcTemplate, fundingRepository, properties);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(),
                eq("surprising.inverse-perp.funding.rate.v1"),
                eq(100))).thenReturn(List.of());

        repository.lockPending(100);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(),
                eq("surprising.inverse-perp.funding.rate.v1"),
                eq(100));
        assertThat(sql.getValue())
                .contains("topic = ?")
                .contains("next_attempt_at <= now()");
    }

    @Test
    void lockPendingDoesNotClaimRowsForNonFundingProductLine() {
        FundingProperties properties = new FundingProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        FundingOutboxRepository repository = new FundingOutboxRepository(jdbcTemplate, fundingRepository, properties);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), eq(100))).thenReturn(List.of());

        repository.lockPending(100);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), eq(100));
        assertThat(sql.getValue())
                .contains("AND 1 = 0")
                .doesNotContain("topic = ?");
    }

    @Test
    void markFailedSchedulesRetryWithBackoffAndTruncatesError() {
        FundingOutboxRepository repository = new FundingOutboxRepository(jdbcTemplate, fundingRepository);
        when(jdbcTemplate.update(any(String.class), any(Object[].class))).thenReturn(1);
        String error = "x".repeat(1100);

        repository.markFailed(901L, error, Instant.parse("2026-07-01T00:00:00Z"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(sql.capture(), args.capture());
        assertThat(sql.getValue())
                .contains("attempts = attempts + 1")
                .contains("last_error = ?")
                .contains("power(2, LEAST(attempts, 6))")
                .contains("next_attempt_at");
        assertThat((String) args.getValue()[0]).hasSize(1000);
        assertThat(args.getValue()[3]).isEqualTo(901L);
    }

    @Test
    void markFailedFailsWhenNoRowIsUpdated() {
        FundingOutboxRepository repository = new FundingOutboxRepository(jdbcTemplate, fundingRepository);
        when(jdbcTemplate.update(contains("SET attempts = attempts + 1"), any(), any(), any(), eq(901L)))
                .thenReturn(0);

        assertThatThrownBy(() -> repository.markFailed(901L, "kafka unavailable",
                Instant.parse("2026-07-01T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("funding outbox failure mark");
    }

    @SuppressWarnings("unchecked")
    private RowMapper<Object> anyRowMapper() {
        return any(RowMapper.class);
    }
}
