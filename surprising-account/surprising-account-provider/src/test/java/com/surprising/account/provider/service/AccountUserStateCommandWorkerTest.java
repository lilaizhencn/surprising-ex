package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.api.model.AccountUserCommandType;
import com.surprising.account.api.model.BalanceAdjustmentAccountCommand;
import com.surprising.account.api.model.BalanceAdjustmentRequest;
import com.surprising.account.api.model.OrderReservationKind;
import com.surprising.account.api.model.OrderReserveAccountCommand;
import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import com.surprising.account.api.model.ProductBalanceAdjustmentAccountCommand;
import com.surprising.account.api.model.ProductBalanceAdjustmentRequest;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.model.AccountCommandTerminalResult;
import com.surprising.eventstore.UserPartitionCommandLane;
import com.surprising.eventstore.UserPartitionKey;
import com.surprising.eventstore.UserPartitionResultStore;
import com.surprising.eventstore.UserPartitionStateStore;
import com.surprising.eventstore.UserPartitionWal;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
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
    void acceptsEquivalentJsonResultFieldOrderDuringRecovery() {
        ObjectMapper objectMapper = new ObjectMapper();
        AccountUserStateCommandWorker worker = new AccountUserStateCommandWorker(
                objectMapper, null, null, null, null, null, null, null);
        AccountCommandTerminalResult persisted = new AccountCommandTerminalResult(
                com.surprising.account.api.model.AccountCommandStatus.APPLIED,
                "{\"tradeId\":1,\"orderId\":2}", null, null, List.of());
        AccountCommandTerminalResult recomputed = new AccountCommandTerminalResult(
                com.surprising.account.api.model.AccountCommandStatus.APPLIED,
                "{\"orderId\":2,\"tradeId\":1}", null, null, List.of());

        assertThat(worker.terminalEquivalent(persisted, recomputed)).isTrue();
    }

    @Test
    void persistsDeterministicLedgerDeltaAlongsideTheLocalTerminalResult() throws Exception {
        Path directory = Files.createTempDirectory("account-state-worker-ledger-");
        ObjectMapper objectMapper = new ObjectMapper();
        AccountProperties properties = new AccountProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
        UserPartitionKey partition = new UserPartitionKey(ProductLine.LINEAR_PERPETUAL, 1001L);
        AccountUserCommand command = new AccountUserCommand(
                AccountUserCommand.CURRENT_SCHEMA_VERSION, "worker-balance-adjust", ProductLine.LINEAR_PERPETUAL,
                1001L, AccountUserCommandType.BALANCE_ADJUST, "TEST", "balance-adjust-1", null,
                objectMapper.writeValueAsString(new BalanceAdjustmentAccountCommand(
                        new BalanceAdjustmentRequest(1001L, "USDT", -200L, "balance-adjust-1", "测试调整"),
                        "admin-1", "管理员")), Instant.parse("2026-08-02T00:00:00Z"), "worker-trace");

        try (UserPartitionWal wal = new UserPartitionWal(directory.resolve("wal"));
             UserPartitionStateStore stateStore = new UserPartitionStateStore(directory.resolve("state"));
             UserPartitionResultStore resultStore = new UserPartitionResultStore(directory.resolve("result"))) {
            wal.append(partition, command.commandId(), command.commandType().name(),
                    objectMapper.writeValueAsString(command).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    "worker-balance-fingerprint", command.occurredAt());
            AccountUserStateReducer reducer = new AccountUserStateReducer(
                    objectMapper, stateStore, new UserPartitionCommandLane());
            reducer.initialize(snapshot());
            new AccountUserStateCommandWorker(objectMapper, properties, wal, stateStore, resultStore,
                    new UserPartitionCommandLane(), reducer, kafkaTemplate).applyPending();

            AccountCommandTerminalResult terminal = objectMapper.readValue(
                    new String(resultStore.read(partition, command.commandId()).orElseThrow(),
                            java.nio.charset.StandardCharsets.UTF_8), AccountCommandTerminalResult.class);
            assertThat(terminal.ledgerDeltas()).containsExactly(
                    new AccountCommandTerminalResult.LedgerDelta(
                            "USDT", -200L, 800L, "BALANCE_ADJUSTMENT", command.commandId(),
                            "BALANCE_ADJUST", null));
        }
    }

    @Test
    void restartsAfterResultPublishFailureWithoutReapplyingBalanceMutation() throws Exception {
        Path directory = Files.createTempDirectory("account-state-worker-crash-");
        ObjectMapper objectMapper = new ObjectMapper();
        AccountProperties properties = new AccountProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        CompletableFuture<SendResult<String, String>> success = CompletableFuture.completedFuture(null);
        CompletableFuture<SendResult<String, String>> failure = new CompletableFuture<>();
        failure.completeExceptionally(new IllegalStateException("模拟结果发布失败"));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(success, failure, success, success);
        UserPartitionKey partition = new UserPartitionKey(ProductLine.LINEAR_PERPETUAL, 1001L);
        AccountUserCommand command = command(objectMapper);

        try (UserPartitionWal wal = new UserPartitionWal(directory.resolve("wal"));
             UserPartitionStateStore stateStore = new UserPartitionStateStore(directory.resolve("state"));
             UserPartitionResultStore resultStore = new UserPartitionResultStore(directory.resolve("result"))) {
            wal.append(partition, command.commandId(), command.commandType().name(),
                    objectMapper.writeValueAsString(command).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    "worker-fingerprint", command.occurredAt());
            AccountUserStateReducer firstReducer = new AccountUserStateReducer(
                    objectMapper, stateStore, new UserPartitionCommandLane());
            firstReducer.initialize(snapshot());
            AccountUserStateCommandWorker firstWorker = new AccountUserStateCommandWorker(
                    objectMapper, properties, wal, stateStore, resultStore, new UserPartitionCommandLane(),
                    firstReducer, kafkaTemplate);

            // 结果发布失败由 worker 捕获并保留在 WAL；状态和终态已经可靠落盘。
            assertThatCode(firstWorker::applyPending).doesNotThrowAnyException();
            assertThat(stateStore.lastAppliedSequence(partition)).isEqualTo(1L);
            assertThat(resultStore.read(partition, command.commandId())).isPresent();
            assertThat(firstReducer.state(partition).orElseThrow().snapshot().balances()).containsExactly(
                    new PerpetualAccountStateUpdatedEvent.Balance("USDT", 700L, 300L));

            AccountUserStateReducer restartedReducer = new AccountUserStateReducer(
                    objectMapper, stateStore, new UserPartitionCommandLane());
            AccountUserStateCommandWorker restartedWorker = new AccountUserStateCommandWorker(
                    objectMapper, properties, wal, stateStore, resultStore, new UserPartitionCommandLane(),
                    restartedReducer, kafkaTemplate);
            assertThatCode(restartedWorker::applyPending).doesNotThrowAnyException();
            assertThat(stateStore.lastAppliedSequence(partition)).isEqualTo(1L);
            assertThat(restartedReducer.state(partition).orElseThrow().snapshot().balances()).containsExactly(
                    new PerpetualAccountStateUpdatedEvent.Balance("USDT", 700L, 300L));
            org.mockito.Mockito.verify(kafkaTemplate, org.mockito.Mockito.times(4))
                    .send(anyString(), anyString(), anyString());
        }
    }

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
                    reducer, kafkaTemplate);

            worker.applyPending();
            assertThat(stateStore.lastAppliedSequence(partition)).isEqualTo(1L);
            assertThat(resultStore.read(partition, command.commandId())).isPresent();
            org.mockito.ArgumentCaptor<String> topic = org.mockito.ArgumentCaptor.forClass(String.class);
            org.mockito.Mockito.verify(kafkaTemplate, org.mockito.Mockito.times(2))
                    .send(topic.capture(), anyString(), anyString());
            assertThat(topic.getAllValues()).containsExactly(
                    properties.getKafka().getAccountStateEventsTopic(),
                    properties.getKafka().getCommandResultsTopic());

            AccountUserStateReducer restartedReducer = new AccountUserStateReducer(
                    objectMapper, stateStore, new UserPartitionCommandLane());
            AccountUserStateCommandWorker restartedWorker = new AccountUserStateCommandWorker(
                    objectMapper, properties, wal, stateStore, resultStore, new UserPartitionCommandLane(),
                    restartedReducer, kafkaTemplate);
            restartedWorker.applyPending();
            org.mockito.ArgumentCaptor<String> payload = org.mockito.ArgumentCaptor.forClass(String.class);
            org.mockito.Mockito.verify(kafkaTemplate, org.mockito.Mockito.times(4))
                    .send(anyString(), anyString(), payload.capture());
            assertThat(payload.getAllValues().get(1)).isEqualTo(payload.getAllValues().get(3));
            org.mockito.Mockito.verify(kafkaTemplate, org.mockito.Mockito.times(4))
                    .send(anyString(), anyString(), anyString());
        }
    }

    @Test
    void rejectsReserveOnMissingSnapshotInsteadOfPoisoningUserPartition() throws Exception {
        Path directory = Files.createTempDirectory("account-state-worker-empty-");
        ObjectMapper objectMapper = new ObjectMapper();
        AccountProperties properties = new AccountProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
        UserPartitionKey partition = new UserPartitionKey(ProductLine.LINEAR_PERPETUAL, 1002L);
        AccountUserCommand command = new AccountUserCommand(
                AccountUserCommand.CURRENT_SCHEMA_VERSION, "worker-empty-reserve", ProductLine.LINEAR_PERPETUAL,
                1002L, AccountUserCommandType.ORDER_RESERVE, "ORDER", "9100", null,
                objectMapper.writeValueAsString(new OrderReserveAccountCommand(
                        9100L, "BTC-USDT", OrderSide.BUY, OrderReservationKind.DERIVATIVE_MARGIN,
                        AccountType.USDT_PERPETUAL, "USDT", MarginMode.CROSS, PositionSide.NET,
                        1L, false, 100L)), Instant.parse("2026-08-02T00:00:00Z"), "worker-empty");

        try (UserPartitionWal wal = new UserPartitionWal(directory.resolve("wal"));
             UserPartitionStateStore stateStore = new UserPartitionStateStore(directory.resolve("state"));
             UserPartitionResultStore resultStore = new UserPartitionResultStore(directory.resolve("result"))) {
            wal.append(partition, command.commandId(), command.commandType().name(),
                    objectMapper.writeValueAsString(command).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    "worker-empty-fingerprint", command.occurredAt());
            AccountUserStateReducer reducer = new AccountUserStateReducer(
                    objectMapper, stateStore, new UserPartitionCommandLane());
            AccountUserStateCommandWorker worker = new AccountUserStateCommandWorker(
                    objectMapper, properties, wal, stateStore, resultStore, new UserPartitionCommandLane(),
                    reducer, kafkaTemplate);

            worker.applyPending();

            AccountCommandTerminalResult terminal = objectMapper.readValue(
                    new String(resultStore.read(partition, command.commandId()).orElseThrow(),
                            java.nio.charset.StandardCharsets.UTF_8), AccountCommandTerminalResult.class);
            assertThat(terminal.status()).isEqualTo(com.surprising.account.api.model.AccountCommandStatus.REJECTED);
            assertThat(terminal.errorCode()).isEqualTo("INSUFFICIENT_AVAILABLE_BALANCE");
            assertThat(stateStore.lastAppliedSequence(partition)).isEqualTo(1L);
        }
    }

    @Test
    void appliesFirstProductBalanceAdjustmentWithoutExistingSnapshot() throws Exception {
        Path directory = Files.createTempDirectory("account-state-worker-first-adjustment-");
        ObjectMapper objectMapper = new ObjectMapper();
        AccountProperties properties = new AccountProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
        UserPartitionKey partition = new UserPartitionKey(ProductLine.LINEAR_PERPETUAL, 1003L);
        AccountUserCommand command = new AccountUserCommand(
                AccountUserCommand.CURRENT_SCHEMA_VERSION, "worker-first-product-adjustment",
                ProductLine.LINEAR_PERPETUAL, 1003L, AccountUserCommandType.PRODUCT_BALANCE_ADJUST,
                "ADMIN", "first-product-adjustment", null,
                objectMapper.writeValueAsString(new ProductBalanceAdjustmentAccountCommand(
                        new ProductBalanceAdjustmentRequest(1003L, AccountType.USDT_PERPETUAL,
                                "USDT", 500L, "first-product-adjustment", "测试首次入账"),
                        "admin-1", "管理员")),
                Instant.parse("2026-08-02T00:00:00Z"), "worker-first-product-adjustment");

        try (UserPartitionWal wal = new UserPartitionWal(directory.resolve("wal"));
             UserPartitionStateStore stateStore = new UserPartitionStateStore(directory.resolve("state"));
             UserPartitionResultStore resultStore = new UserPartitionResultStore(directory.resolve("result"))) {
            wal.append(partition, command.commandId(), command.commandType().name(),
                    objectMapper.writeValueAsString(command).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    "worker-first-product-adjustment-fingerprint", command.occurredAt());
            InstrumentSnapshotCache instruments = new InstrumentSnapshotCache();
            instruments.replace(ProductLine.LINEAR_PERPETUAL, List.of(), java.util.Map.of("USDT", 1L));
            AccountUserStateReducer reducer = new AccountUserStateReducer(
                    objectMapper, stateStore, new UserPartitionCommandLane(),
                    instruments, new PositionCalculator());
            AccountUserStateCommandWorker worker = new AccountUserStateCommandWorker(
                    objectMapper, properties, wal, stateStore, resultStore, new UserPartitionCommandLane(),
                    reducer, kafkaTemplate);

            worker.applyPending();

            AccountCommandTerminalResult terminal = objectMapper.readValue(
                    new String(resultStore.read(partition, command.commandId()).orElseThrow(),
                            java.nio.charset.StandardCharsets.UTF_8), AccountCommandTerminalResult.class);
            assertThat(terminal.status()).isEqualTo(com.surprising.account.api.model.AccountCommandStatus.APPLIED);
            assertThat(reducer.state(partition).orElseThrow().snapshot().balances()).containsExactly(
                    new PerpetualAccountStateUpdatedEvent.Balance("USDT", 500L, 0L));
            assertThat(stateStore.lastAppliedSequence(partition)).isEqualTo(1L);
        }
    }

    @Test
    void rejectsMalformedProductBalanceAdjustmentWithoutBlockingThePartition() throws Exception {
        Path directory = Files.createTempDirectory("account-state-worker-invalid-adjustment-");
        ObjectMapper objectMapper = new ObjectMapper();
        AccountProperties properties = new AccountProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
        UserPartitionKey partition = new UserPartitionKey(ProductLine.LINEAR_PERPETUAL, 1001L);
        AccountUserCommand command = new AccountUserCommand(
                AccountUserCommand.CURRENT_SCHEMA_VERSION, "worker-invalid-product-adjustment",
                ProductLine.LINEAR_PERPETUAL, 1001L, AccountUserCommandType.PRODUCT_BALANCE_ADJUST,
                "ADMIN", "invalid-product-adjustment", null,
                objectMapper.writeValueAsString(new ProductBalanceAdjustmentAccountCommand(
                        new ProductBalanceAdjustmentRequest(1001L, AccountType.USDT_PERPETUAL,
                                "", 500L, "invalid-product-adjustment", "测试非法入账"),
                        "admin-1", "管理员")),
                Instant.parse("2026-08-02T00:00:00Z"), "worker-invalid-product-adjustment");

        try (UserPartitionWal wal = new UserPartitionWal(directory.resolve("wal"));
             UserPartitionStateStore stateStore = new UserPartitionStateStore(directory.resolve("state"));
             UserPartitionResultStore resultStore = new UserPartitionResultStore(directory.resolve("result"))) {
            wal.append(partition, command.commandId(), command.commandType().name(),
                    objectMapper.writeValueAsString(command).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    "worker-invalid-product-adjustment-fingerprint", command.occurredAt());
            AccountUserStateReducer reducer = new AccountUserStateReducer(
                    objectMapper, stateStore, new UserPartitionCommandLane());
            reducer.initialize(snapshot());
            AccountUserStateCommandWorker worker = new AccountUserStateCommandWorker(
                    objectMapper, properties, wal, stateStore, resultStore, new UserPartitionCommandLane(),
                    reducer, kafkaTemplate);

            worker.applyPending();

            AccountCommandTerminalResult terminal = objectMapper.readValue(
                    new String(resultStore.read(partition, command.commandId()).orElseThrow(),
                            java.nio.charset.StandardCharsets.UTF_8), AccountCommandTerminalResult.class);
            assertThat(terminal.status()).isEqualTo(com.surprising.account.api.model.AccountCommandStatus.REJECTED);
            assertThat(terminal.errorCode()).isEqualTo("INVALID_COMMAND_PAYLOAD");
            assertThat(reducer.state(partition).orElseThrow().snapshot().balances()).containsExactly(
                    new PerpetualAccountStateUpdatedEvent.Balance("USDT", 1000L, 0L));
            assertThat(stateStore.lastAppliedSequence(partition)).isEqualTo(1L);
        }
    }

    @Test
    void recoversDependencyRejectionAfterResultWasWrittenBeforeStateCommit() throws Exception {
        Path directory = Files.createTempDirectory("account-state-worker-dependency-recovery-");
        ObjectMapper objectMapper = new ObjectMapper();
        AccountProperties properties = new AccountProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
        UserPartitionKey partition = new UserPartitionKey(ProductLine.LINEAR_PERPETUAL, 1001L);
        AccountUserCommand rejected = reserveCommand(objectMapper, "worker-dependency-rejected", "9002",
                2_000L, 2_000L, null);
        AccountUserCommand dependent = reserveCommand(objectMapper, "worker-dependency-child", "9003",
                100L, 100L, rejected.commandId());

        try (UserPartitionWal wal = new UserPartitionWal(directory.resolve("wal"));
             UserPartitionStateStore stateStore = new UserPartitionStateStore(directory.resolve("state"));
             UserPartitionResultStore resultStore = new UserPartitionResultStore(directory.resolve("result"))) {
            wal.append(partition, rejected.commandId(), rejected.commandType().name(),
                    objectMapper.writeValueAsString(rejected).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    "dependency-rejected", rejected.occurredAt());
            wal.append(partition, dependent.commandId(), dependent.commandType().name(),
                    objectMapper.writeValueAsString(dependent).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    "dependency-child", dependent.occurredAt());
            AccountUserStateReducer reducer = new AccountUserStateReducer(
                    objectMapper, stateStore, new UserPartitionCommandLane());
            reducer.initialize(snapshot());
            AccountUserStateReducer reducerSpy = org.mockito.Mockito.spy(reducer);
            doAnswer(invocation -> {
                if (invocation.getArgument(1, Long.class) == 2L) {
                    throw new IllegalStateException("模拟第二条命令提交崩溃");
                }
                return invocation.callRealMethod();
            }).when(reducerSpy).commit(any(AccountUserCommand.class), eq(2L),
                    any(AccountUserStateReducer.Reduction.class));
            AccountUserStateCommandWorker firstWorker = new AccountUserStateCommandWorker(
                    objectMapper, properties, wal, stateStore, resultStore, new UserPartitionCommandLane(),
                    reducerSpy, kafkaTemplate);

            firstWorker.applyPending();
            assertThat(stateStore.lastAppliedSequence(partition)).isEqualTo(1L);
            assertThat(resultStore.read(partition, dependent.commandId())).isPresent();

            AccountUserStateReducer restartedReducer = new AccountUserStateReducer(
                    objectMapper, stateStore, new UserPartitionCommandLane());
            AccountUserStateCommandWorker restartedWorker = new AccountUserStateCommandWorker(
                    objectMapper, properties, wal, stateStore, resultStore, new UserPartitionCommandLane(),
                    restartedReducer, kafkaTemplate);
            assertThatCode(restartedWorker::applyPending).doesNotThrowAnyException();
            assertThat(stateStore.lastAppliedSequence(partition)).isEqualTo(2L);
            assertThat(resultStore.read(partition, dependent.commandId())).isPresent();
        }
    }

    private static AccountUserCommand command(ObjectMapper objectMapper) {
        return reserveCommand(objectMapper, "worker-reserve-1", "9001", 100L, 300L, null);
    }

    private static AccountUserCommand reserveCommand(ObjectMapper objectMapper,
                                                      String commandId,
                                                      String orderId,
                                                      long orderQuantity,
                                                      long reservedUnits,
                                                      String dependsOnCommandId) {
        long parsedOrderId = Long.parseLong(orderId);
        return new AccountUserCommand(AccountUserCommand.CURRENT_SCHEMA_VERSION, commandId,
                ProductLine.LINEAR_PERPETUAL, 1001L, AccountUserCommandType.ORDER_RESERVE,
                "ORDER", orderId, dependsOnCommandId,
                objectMapper.writeValueAsString(new OrderReserveAccountCommand(
                        parsedOrderId, "BTC-USDT", OrderSide.BUY, OrderReservationKind.DERIVATIVE_MARGIN,
                        AccountType.USDT_PERPETUAL, "USDT", MarginMode.CROSS, PositionSide.NET,
                        orderQuantity, false, reservedUnits)),
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
