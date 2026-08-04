package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.api.model.AccountUserCommandType;
import com.surprising.eventstore.UserPartitionKey;
import com.surprising.eventstore.UserPartitionWal;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AccountUserCommandWalIngressTest {

    @Test
    void retryWithDifferentTimeAndTraceKeepsTheSameWalFingerprint() {
        UserPartitionWal wal = mock(UserPartitionWal.class);
        AccountUserCommandWalIngress ingress = new AccountUserCommandWalIngress(wal);
        UserPartitionKey partition = new UserPartitionKey(ProductLine.SPOT, 1001L);
        AccountUserCommand first = command(Instant.parse("2026-08-03T00:00:00Z"), "trace-a");
        AccountUserCommand retry = command(Instant.parse("2026-08-03T00:00:01Z"), "trace-b");

        ingress.append(List.of(
                new AccountUserCommandWalIngress.CommandEnvelope(first, "first-envelope"),
                new AccountUserCommandWalIngress.CommandEnvelope(retry, "retry-envelope")));

        ArgumentCaptor<String> fingerprints = ArgumentCaptor.forClass(String.class);
        verify(wal, org.mockito.Mockito.times(2)).append(
                eq(partition), eq(first.commandId()), eq(first.commandType().name()),
                any(byte[].class), fingerprints.capture(), any(Instant.class));
        assertThat(fingerprints.getAllValues()).hasSize(2)
                .containsOnly(fingerprints.getAllValues().get(0));
    }

    private static AccountUserCommand command(Instant occurredAt, String traceId) {
        return new AccountUserCommand(
                AccountUserCommand.CURRENT_SCHEMA_VERSION,
                "account-api:product_balance_adjust:stable",
                ProductLine.SPOT,
                1001L,
                AccountUserCommandType.PRODUCT_BALANCE_ADJUST,
                "account-api",
                "reference-1",
                null,
                "{\"amountUnits\":100}",
                occurredAt,
                traceId);
    }
}
