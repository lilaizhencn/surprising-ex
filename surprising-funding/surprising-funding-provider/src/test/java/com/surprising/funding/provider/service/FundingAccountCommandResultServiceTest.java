package com.surprising.funding.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.AccountCommandResultEvent;
import com.surprising.account.api.model.AccountCommandStatus;
import com.surprising.account.api.model.AccountUserCommandType;
import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.funding.provider.model.FundingPaymentResult;
import com.surprising.funding.provider.repository.FundingPaymentCompletionRepository;
import com.surprising.funding.provider.repository.FundingPendingCommandRepository;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FundingAccountCommandResultServiceTest {

    @Test
    void appliesFundingResultsAsOneRepositoryBatchAndIgnoresOtherCommandTypes() {
        FundingPaymentCompletionRepository completionRepository =
                mock(FundingPaymentCompletionRepository.class);
        FundingPendingCommandRepository pendingCommandRepository =
                mock(FundingPendingCommandRepository.class);
        FundingAccountCommandResultService service =
                new FundingAccountCommandResultService(
                        completionRepository, pendingCommandRepository, new FundingProperties());
        AccountCommandResultEvent funding = result("FUNDING:1", 1001L,
                AccountUserCommandType.FUNDING_SETTLE, "FUNDING");
        AccountCommandResultEvent other = result("ORDER:1", 1001L,
                AccountUserCommandType.ORDER_RESERVE, "ORDER");

        service.applyBatch(List.of(funding, other));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FundingPaymentResult>> results = ArgumentCaptor.forClass(List.class);
        verify(completionRepository).completeBatch(results.capture());
        assertThat(results.getValue()).singleElement().satisfies(result -> {
            assertThat(result.commandId()).isEqualTo("FUNDING:1");
            assertThat(result.userId()).isEqualTo(1001L);
            assertThat(result.status()).isEqualTo("APPLIED");
        });
    }

    @Test
    void reconcilesConfiguredNumberOfTerminalCommandsInOneBatch() {
        FundingPaymentCompletionRepository completionRepository =
                mock(FundingPaymentCompletionRepository.class);
        FundingPendingCommandRepository pendingCommandRepository =
                mock(FundingPendingCommandRepository.class);
        FundingProperties properties = new FundingProperties();
        properties.getSettlement().setReconcileBatchSize(321);
        FundingPaymentResult result = new FundingPaymentResult(
                "FUNDING:1", 1001L, "APPLIED", null, null,
                Instant.parse("2026-07-01T00:00:00Z"));
        when(pendingCommandRepository.findTerminal(321)).thenReturn(List.of(result));
        FundingAccountCommandResultService service =
                new FundingAccountCommandResultService(
                        completionRepository, pendingCommandRepository, properties);

        service.reconcileTerminalCommands();

        verify(completionRepository).completeBatch(List.of(result));
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
