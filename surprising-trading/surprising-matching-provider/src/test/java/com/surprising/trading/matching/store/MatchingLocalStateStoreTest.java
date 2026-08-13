package com.surprising.trading.matching.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.trading.api.model.MatchResultEvent;
import com.surprising.trading.api.model.OrderCommandEvent;
import com.surprising.trading.api.model.OrderCommandType;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.matching.repository.MatchingOutboxRepository.MatchingOutboxWrite;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class MatchingLocalStateStoreTest {

    @TempDir
    Path directory;

    @Test
    void commitsResultAndOrderStateAtomicallyAndIsIdempotent() {
        ObjectMapper mapper = new ObjectMapper();
        OrderCommandEvent command = command(11L, 101L, 1001L, Instant.parse("2026-07-01T00:00:00Z"));
        MatchResultEvent result = new MatchResultEvent(command.commandId(), command.orderId(), command.userId(),
                command.symbol(), command.instrumentVersion(), command.commandType(), "SUCCESS", 0L,
                OrderStatus.ACCEPTED, Instant.parse("2026-07-01T00:00:01Z"), List.of());
        MatchingOutboxWrite publicTrade = new MatchingOutboxWrite(
                "PUBLIC_TRADE", 101L, "surprising.linear-perp.match.trades.v1", "BTC-USDT", "TRADE",
                "{}", Instant.parse("2026-07-01T00:00:01Z"));

        try (MatchingLocalStateStore store = new MatchingLocalStateStore(directory, mapper)) {
            store.prepare(command);
            assertThat(store.commit(result, List.of(), List.of(publicTrade))).isTrue();
            assertThat(store.commit(result, List.of(), List.of(publicTrade))).isFalse();
            assertThat(store.result(command.commandId())).isPresent();
            assertThat(store.order(command.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.ACCEPTED);
            assertThat(store.pendingOutbox(10)).hasSize(1);
        }

        try (MatchingLocalStateStore reopened = new MatchingLocalStateStore(directory, mapper)) {
            assertThat(reopened.result(command.commandId())).isPresent();
            assertThat(reopened.order(command.orderId()).orElseThrow().remainingQuantitySteps()).isEqualTo(5L);
            assertThat(reopened.pendingOutbox(10)).extracting(MatchingLocalStateStore.LocalOutboxRecord::aggregateType)
                    .containsExactly("PUBLIC_TRADE");
            assertThatThrownBy(() -> reopened.commit(new MatchResultEvent(command.commandId(), command.orderId(),
                    command.userId(), command.symbol(), command.instrumentVersion(), command.commandType(),
                    "CONFLICT", 0L, OrderStatus.REJECTED, Instant.now(), List.of()), List.of()))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void canceledUnfilledOrderDropsRemainingQuantityWithoutBreakingLocalInvariant() {
        ObjectMapper mapper = new ObjectMapper();
        OrderCommandEvent command = command(21L, 201L, 1001L, Instant.parse("2026-07-01T00:00:00Z"));
        MatchResultEvent cancel = new MatchResultEvent(command.commandId() + 1L, command.orderId(), command.userId(),
                command.symbol(), command.instrumentVersion(), OrderCommandType.CANCEL, "SUCCESS", 0L,
                OrderStatus.CANCELED, Instant.parse("2026-07-01T00:00:01Z"), List.of());

        try (MatchingLocalStateStore store = new MatchingLocalStateStore(directory, mapper)) {
            store.prepare(command);
            assertThat(store.commit(cancel, List.of())).isTrue();
            MatchingLocalStateStore.StoredOrder canceled = store.order(command.orderId()).orElseThrow();
            assertThat(canceled.status()).isEqualTo(OrderStatus.CANCELED);
            assertThat(canceled.executedQuantitySteps()).isZero();
            assertThat(canceled.remainingQuantitySteps()).isZero();
        }
    }

    @Test
    void commitsMultipleOutboxWritesOnOneEventStream() {
        ObjectMapper mapper = new ObjectMapper();
        OrderCommandEvent command = command(31L, 301L, 1001L, Instant.parse("2026-07-01T00:00:00Z"));
        MatchResultEvent result = new MatchResultEvent(command.commandId(), command.orderId(), command.userId(),
                command.symbol(), command.instrumentVersion(), command.commandType(), "SUCCESS", 0L,
                OrderStatus.ACCEPTED, Instant.parse("2026-07-01T00:00:01Z"), List.of());
        Instant occurredAt = Instant.parse("2026-07-01T00:00:01Z");
        MatchingOutboxWrite taker = new MatchingOutboxWrite(
                "ACCOUNT_COMMAND", 700L, "surprising.spot.account.user.commands.v1",
                "SPOT:USER:1001", "TRADE_SIDE_SETTLE", "{\"role\":\"TAKER\"}", occurredAt);
        MatchingOutboxWrite release = new MatchingOutboxWrite(
                "ACCOUNT_COMMAND", 700L, "surprising.spot.account.user.commands.v1",
                "SPOT:USER:1001", "ORDER_RELEASE", "{\"role\":\"RELEASE\"}", occurredAt);

        try (MatchingLocalStateStore store = new MatchingLocalStateStore(directory, mapper)) {
            store.prepare(command);
            assertThat(store.commit(result, List.of(), List.of(taker, release))).isTrue();
            assertThat(store.pendingOutbox(10)).extracting(MatchingLocalStateStore.LocalOutboxRecord::eventType)
                    .containsExactly("TRADE_SIDE_SETTLE", "ORDER_RELEASE");
        }
    }

    @Test
    void isolatesOutboxSequenceAcrossSymbols() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        int commandCount = 64;
        List<OrderCommandEvent> commands = new ArrayList<>(commandCount);
        List<MatchResultEvent> results = new ArrayList<>(commandCount);
        List<MatchingOutboxWrite> outboxWrites = new ArrayList<>(commandCount);
        Instant commandTime = Instant.parse("2026-07-01T00:00:00Z");
        Instant resultTime = Instant.parse("2026-07-01T00:00:01Z");
        for (int index = 0; index < commandCount; index++) {
            long commandId = 1000L + index;
            long orderId = 2000L + index;
            String symbol = "TEST-" + index + "-USDT";
            OrderCommandEvent command = command(commandId, orderId, 3000L + index, symbol, commandTime);
            commands.add(command);
            results.add(new MatchResultEvent(command.commandId(), command.orderId(), command.userId(),
                    command.symbol(), command.instrumentVersion(), command.commandType(), "SUCCESS", 0L,
                    OrderStatus.ACCEPTED, resultTime, List.of()));
            outboxWrites.add(new MatchingOutboxWrite("MATCH_RESULT", orderId,
                    "surprising.test.match.results.v1", symbol, "MATCH_RESULT", "{\"orderId\":" + orderId + "}",
                    resultTime));
        }

        try (MatchingLocalStateStore store = new MatchingLocalStateStore(directory, mapper)) {
            for (OrderCommandEvent command : commands) {
                store.prepare(command);
            }
            ExecutorService executor = Executors.newFixedThreadPool(16);
            CountDownLatch ready = new CountDownLatch(16);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> futures = new ArrayList<>(commandCount);
            try {
                for (int index = 0; index < commandCount; index++) {
                    int taskIndex = index;
                    futures.add(executor.submit(() -> {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("并发测试未开始");
                        }
                        return store.commit(results.get(taskIndex), List.of(), List.of(outboxWrites.get(taskIndex)));
                    }));
                }
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                for (Future<Boolean> future : futures) {
                    assertThat(future.get(30, TimeUnit.SECONDS)).isTrue();
                }
            } finally {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }

            List<MatchingLocalStateStore.LocalOutboxRecord> records = store.pendingOutbox(commandCount);
            Set<String> streams = new HashSet<>();
            Set<Long> aggregateIds = new HashSet<>();
            for (MatchingLocalStateStore.LocalOutboxRecord record : records) {
                streams.add(record.eventKey());
                assertThat(record.sequence()).isEqualTo(1L);
                aggregateIds.add(record.aggregateId());
            }
            assertThat(records).hasSize(commandCount);
            assertThat(streams).hasSize(commandCount);
            assertThat(aggregateIds).hasSize(commandCount);
        }
    }

    private static OrderCommandEvent command(long commandId, long orderId, long userId, Instant time) {
        return command(commandId, orderId, userId, "BTC-USDT", time);
    }

    private static OrderCommandEvent command(long commandId, long orderId, long userId,
                                             String symbol, Instant time) {
        return new OrderCommandEvent(OrderCommandType.PLACE, commandId, orderId, userId, "client-" + orderId,
                symbol, 3L, OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC, 100L, 5L,
                MarginMode.CROSS, PositionSide.NET, 2L, 5L, false, false, time, "trace");
    }
}
