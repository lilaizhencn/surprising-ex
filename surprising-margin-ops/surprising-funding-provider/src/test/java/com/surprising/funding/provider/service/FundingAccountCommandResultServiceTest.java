package com.surprising.funding.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.AccountCommandResultEvent;
import com.surprising.account.api.model.AccountCommandStatus;
import com.surprising.account.api.model.AccountUserCommandType;
import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.funding.provider.repository.FundingRepository;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FundingAccountCommandResultServiceTest {

    @Test
    void appliesFundingResultsAsOneRepositoryBatchAndIgnoresOtherCommandTypes() {
        FundingRepository repository = mock(FundingRepository.class);
        FundingAccountCommandResultService service =
                new FundingAccountCommandResultService(repository, new FundingProperties());
        AccountCommandResultEvent funding = result("FUNDING:1", 1001L,
                AccountUserCommandType.FUNDING_SETTLE, "FUNDING");
        AccountCommandResultEvent other = result("ORDER:1", 1001L,
                AccountUserCommandType.ORDER_RESERVE, "ORDER");

        service.applyBatch(List.of(funding, other));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FundingRepository.PaymentResult>> results = ArgumentCaptor.forClass(List.class);
        verify(repository).completePayments(results.capture());
        assertThat(results.getValue()).singleElement().satisfies(result -> {
            assertThat(result.commandId()).isEqualTo("FUNDING:1");
            assertThat(result.userId()).isEqualTo(1001L);
            assertThat(result.status()).isEqualTo("APPLIED");
        });
    }

    @Test
    void reconcilesConfiguredNumberOfTerminalCommandsInOneBatch() {
        FundingRepository repository = mock(FundingRepository.class);
        FundingProperties properties = new FundingProperties();
        properties.getSettlement().setReconcileBatchSize(321);
        FundingRepository.PaymentResult result = new FundingRepository.PaymentResult(
                "FUNDING:1", 1001L, "APPLIED", null, null,
                Instant.parse("2026-07-01T00:00:00Z"));
        when(repository.terminalAccountCommandsForPendingPayments(321)).thenReturn(List.of(result));
        FundingAccountCommandResultService service =
                new FundingAccountCommandResultService(repository, properties);

        service.reconcileTerminalCommands();

        verify(repository).completePayments(List.of(result));
    }

    private AccountCommandResultEvent result(String commandId,
                                             long userId,
                                             AccountUserCommandType type,
                                             String source) {
        return new AccountCommandResultEvent(
                1L,
                commandId,
                ProductLine.LINEAR_PERPETUAL,
                userId,
                type,
                AccountCommandStatus.APPLIED,
                source,
                "1",
                null,
                null,
                null,
                Instant.parse("2026-07-01T00:00:00Z"),
                "trace-1");
    }
}
