package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.account.api.model.AccountCommandResultEvent;
import com.surprising.account.api.model.AccountCommandStatus;
import com.surprising.account.api.model.AccountUserCommandType;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.model.AccountCommandTerminalResult;
import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AccountCommandResultWaiterTest {

    @Test
    void receivesTerminalResultFromKafkaWithoutDatabasePolling() {
        AccountProperties properties = new AccountProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        ObjectMapper objectMapper = new ObjectMapper();
        AccountCommandResultWaiter waiter = new AccountCommandResultWaiter(objectMapper, properties);
        AccountCommandResultEvent event = event("command-1", AccountCommandStatus.APPLIED, "{\"ok\":true}");
        waiter.onResult(record(properties, objectMapper.writeValueAsString(event)));

        assertThat(waiter.await("command-1", Duration.ofSeconds(1)))
                .isEqualTo(new AccountCommandTerminalResult(AccountCommandStatus.APPLIED,
                        "{\"ok\":true}", null, null));
    }

    @Test
    void rejectedResultIsReturnedWithoutDatabaseFallback() {
        AccountProperties properties = new AccountProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        ObjectMapper objectMapper = new ObjectMapper();
        AccountCommandResultWaiter waiter = new AccountCommandResultWaiter(objectMapper, properties);
        AccountCommandResultEvent event = event("command-2", AccountCommandStatus.REJECTED, null);
        waiter.onResult(record(properties, objectMapper.writeValueAsString(event)));

        assertThat(waiter.await("command-2", Duration.ofSeconds(1)))
                .isEqualTo(new AccountCommandTerminalResult(AccountCommandStatus.REJECTED,
                        null, "NO_FUNDS", "insufficient balance"));
    }

    @Test
    void timeoutDoesNotLoseTheCommandIdempotencyKey() {
        AccountProperties properties = new AccountProperties();
        AccountCommandResultWaiter waiter = new AccountCommandResultWaiter(new ObjectMapper(), properties);

        assertThatThrownBy(() -> waiter.await("command-3", Duration.ofMillis(10)))
                .isInstanceOf(AccountCommandTimeoutException.class)
                .hasMessageContaining("command-3");
    }

    private ConsumerRecord<String, String> record(AccountProperties properties, String payload) {
        return new ConsumerRecord<>(properties.getKafka().getCommandResultsTopic(), 0, 0L,
                ProductLine.LINEAR_PERPETUAL.name() + ":1001", payload);
    }

    private AccountCommandResultEvent event(String commandId, AccountCommandStatus status, String resultPayload) {
        return new AccountCommandResultEvent(1L, commandId, ProductLine.LINEAR_PERPETUAL, 1001L,
                AccountUserCommandType.BALANCE_ADJUST, status, "test", commandId, resultPayload,
                status == AccountCommandStatus.REJECTED ? "NO_FUNDS" : null,
                status == AccountCommandStatus.REJECTED ? "insufficient balance" : null,
                Instant.parse("2026-08-02T00:00:00Z"), "trace");
    }
}
