package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.api.model.AccountUserCommandType;
import com.surprising.account.api.model.OrderReservationKind;
import com.surprising.account.api.model.OrderReserveAccountCommand;
import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.eventstore.UserPartitionCommandLane;
import com.surprising.eventstore.UserPartitionKey;
import com.surprising.eventstore.UserPartitionResultStore;
import com.surprising.eventstore.UserPartitionStateStore;
import com.surprising.eventstore.UserPartitionWal;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PositionMode;
import com.surprising.trading.api.model.PositionSide;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.ObjectMapper;

class AccountUserStateCommandWorkerTest {

    @Test
    void publishesUpdatedSnapshotBeforeCommandResultAndReplaysIdempotently() throws Exception {
        Path directory = Files.createTempDirectory("account-state-worker-");
        ObjectMapper objectMapper = new ObjectMapper();
        AccountProperties properties = new AccountProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
        UserPartitionKey partition = new UserPartitionKey(ProductLine.LINEAR_PERPETUAL, 1001L);
        AccountUserCommand command = command(objectMapper);

        try (UserPartitionWal wal = new UserPartitionWal(directory.resolve("wal"));
             UserPartitionStateStore stateStore = new UserPartitionStateStore(directory.resolve("state"));
             UserPartitionResultStore resultStore = new UserPartitionResultStore(directory.resolve("result"))) {
            AccountUserStateReducer reducer = new AccountUserStateReducer(
                    objectMapper, stateStore, new UserPartitionCommandLane());
            reducer.initialize(snapshot());
            wal.append(partition, command.commandId(), command.commandType().name(),
                    objectMapper.writeValueAsString(command).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    "worker-fingerprint", command.occurredAt());
            AccountUserStateCommandWorker worker = new AccountUserStateCommandWorker(
                    objectMapper, properties, wal, stateStore, resultStore, new UserPartitionCommandLane(),
                    reducer, null, kafkaTemplate);

            worker.applyPending();
            assertThat(stateStore.lastAppliedSequence(partition)).isEqualTo(1L);
            assertThat(resultStore.read(command.commandId())).isPresent();
            org.mockito.ArgumentCaptor<String> topic = org.mockito.ArgumentCaptor.forClass(String.class);
            org.mockito.Mockito.verify(kafkaTemplate, org.mockito.Mockito.times(2))
                    .send(topic.capture(), anyString(), anyString());
            assertThat(topic.getAllValues()).containsExactly(
                    properties.getKafka().getAccountStateEventsTopic(),
                    properties.getKafka().getCommandResultsTopic());
        }
    }

    private static AccountUserCommand command(ObjectMapper objectMapper) {
        return new AccountUserCommand(AccountUserCommand.CURRENT_SCHEMA_VERSION, "worker-reserve-1",
                ProductLine.LINEAR_PERPETUAL, 1001L, AccountUserCommandType.ORDER_RESERVE,
                "ORDER", "9001", null,
                objectMapper.writeValueAsString(new OrderReserveAccountCommand(
                        9001L, "BTC-USDT", OrderSide.BUY, OrderReservationKind.DERIVATIVE_MARGIN,
                        AccountType.USDT_PERPETUAL, "USDT", MarginMode.CROSS, PositionSide.NET,
                        100L, false, 300L)),
                Instant.parse("2026-08-02T00:00:00Z"), "worker-trace");
    }

    private static PerpetualAccountStateUpdatedEvent snapshot() {
        return new PerpetualAccountStateUpdatedEvent(
                PerpetualAccountStateUpdatedEvent.CURRENT_SCHEMA_VERSION, 1L, 1L,
                ProductLine.LINEAR_PERPETUAL, 1001L, AccountType.USDT_PERPETUAL.name(),
                List.of(new PerpetualAccountStateUpdatedEvent.Balance("USDT", 1_000L, 0L)),
                List.of(), List.of(), List.of(), List.of(), PositionMode.ONE_WAY,
                Instant.parse("2026-08-01T00:00:00Z"), "initial");
    }
}
