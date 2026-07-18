package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.repository.AccountTradeSettlementMonitorRepository;
import com.surprising.account.provider.repository.AccountTradeSettlementMonitorRepository.IncompleteTradeSnapshot;
import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

class AccountTradeSettlementHealthIndicatorTest {

    @Test
    void reportsDownWhenOnlyOneTradeSideRemainsIncompletePastDeadline() {
        AccountTradeSettlementMonitorRepository repository =
                mock(AccountTradeSettlementMonitorRepository.class);
        AccountProperties properties = properties();
        Instant oldest = Instant.parse("2026-07-18T00:00:00Z");
        when(repository.staleIncomplete(
                eq(ProductLine.LINEAR_PERPETUAL), eq(Duration.ofSeconds(45)), any()))
                .thenReturn(new IncompleteTradeSnapshot(3L, oldest));

        var health = new AccountTradeSettlementHealthIndicator(repository, properties).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("staleIncompleteTrades", 3L)
                .containsEntry("oldestCreatedAt", oldest.toString())
                .containsEntry("staleAfterSeconds", 45L);
    }

    @Test
    void reportsUpWhenEveryTradeHasBothSidesApplied() {
        AccountTradeSettlementMonitorRepository repository =
                mock(AccountTradeSettlementMonitorRepository.class);
        AccountProperties properties = properties();
        when(repository.staleIncomplete(
                eq(ProductLine.LINEAR_PERPETUAL), eq(Duration.ofSeconds(45)), any()))
                .thenReturn(new IncompleteTradeSnapshot(0L, null));

        var health = new AccountTradeSettlementHealthIndicator(repository, properties).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("staleIncompleteTrades", 0L);
    }

    private AccountProperties properties() {
        AccountProperties properties = new AccountProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        properties.getTradeSettlement().setStaleAfter(Duration.ofSeconds(45));
        return properties;
    }
}
