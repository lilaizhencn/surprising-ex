package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.api.model.AccountUserCommandType;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.product.api.ProductLine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class AccountUserCommandConsumerTest {

    @Test
    void parsesOnePollAsOneOrderedWalBatch() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AccountProperties properties = new AccountProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        AccountUserCommandWalIngress ingress = mock(AccountUserCommandWalIngress.class);
        when(ingress.append(anyList())).thenReturn(List.of(
                AccountUserCommandWalIngress.AppendOutcome.DURABLE,
                AccountUserCommandWalIngress.AppendOutcome.DURABLE));
        AccountUserCommandConsumer consumer = new AccountUserCommandConsumer(
                objectMapper, ingress, properties, new AccountCommandMetrics(new SimpleMeterRegistry()));

        AccountUserCommand first = command("command-1", 1001L, Instant.parse("2026-07-20T00:00:00Z"));
        AccountUserCommand second = command("command-2", 1001L, Instant.parse("2026-07-20T00:00:01Z"));
        consumer.onCommands(List.of(
                record(properties, first, objectMapper.writeValueAsString(first), 41L),
                record(properties, second, objectMapper.writeValueAsString(second), 42L)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AccountUserCommandWalIngress.CommandEnvelope>> captor = ArgumentCaptor.forClass(List.class);
        verify(ingress).append(captor.capture());
        assertThat(captor.getValue()).extracting(envelope -> envelope.command().commandId())
                .containsExactly("command-1", "command-2");
    }

    @Test
    void productionConstructorAcknowledgesOnlyAfterWalIngress() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AccountProperties properties = new AccountProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        AccountUserCommandWalIngress ingress = mock(AccountUserCommandWalIngress.class);
        when(ingress.append(anyList())).thenReturn(List.of(
                AccountUserCommandWalIngress.AppendOutcome.DURABLE));
        AccountUserCommandConsumer consumer = new AccountUserCommandConsumer(
                objectMapper, ingress, properties,
                new AccountCommandMetrics(new SimpleMeterRegistry()));
        AccountUserCommand command = command("command-wal-1", 1001L,
                Instant.parse("2026-07-20T00:00:00Z"));

        consumer.onCommands(List.of(record(properties, command,
                objectMapper.writeValueAsString(command), 43L)));

        verify(ingress).append(anyList());
    }

    private static AccountUserCommand command(String commandId, long userId, Instant occurredAt) {
        return new AccountUserCommand(AccountUserCommand.CURRENT_SCHEMA_VERSION, commandId,
                ProductLine.LINEAR_PERPETUAL, userId, AccountUserCommandType.ORDER_RELEASE,
                "TEST", commandId, null, "{}", occurredAt, null);
    }

    private static ConsumerRecord<String, String> record(AccountProperties properties,
                                                           AccountUserCommand command,
                                                           String payload,
                                                           long offset) {
        return new ConsumerRecord<>(properties.getKafka().getUserCommandsTopic(), 7, offset,
                command.partitionKey(), payload);
    }
}
