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
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
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

        try (MatchingLocalStateStore store = new MatchingLocalStateStore(directory, mapper)) {
            store.prepare(command);
            assertThat(store.commit(result, List.of())).isTrue();
            assertThat(store.commit(result, List.of())).isFalse();
            assertThat(store.result(command.commandId())).isPresent();
            assertThat(store.order(command.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.ACCEPTED);
        }

        try (MatchingLocalStateStore reopened = new MatchingLocalStateStore(directory, mapper)) {
            assertThat(reopened.result(command.commandId())).isPresent();
            assertThat(reopened.order(command.orderId()).orElseThrow().remainingQuantitySteps()).isEqualTo(5L);
            assertThatThrownBy(() -> reopened.commit(new MatchResultEvent(command.commandId(), command.orderId(),
                    command.userId(), command.symbol(), command.instrumentVersion(), command.commandType(),
                    "CONFLICT", 0L, OrderStatus.REJECTED, Instant.now(), List.of()), List.of()))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    private static OrderCommandEvent command(long commandId, long orderId, long userId, Instant time) {
        return new OrderCommandEvent(OrderCommandType.PLACE, commandId, orderId, userId, "client-" + orderId,
                "BTC-USDT", 3L, OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC, 100L, 5L,
                MarginMode.CROSS, PositionSide.NET, 2L, 5L, false, false, time, "trace");
    }
}
