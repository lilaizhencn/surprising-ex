package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class ClosedPositionTriggerOrderPrunerTest {

    @Test
    void cancelsOnlyPendingTriggersInTheClosedPositionScope() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(any(String.class), any(Object[].class))).thenReturn(2);
        ClosedPositionTriggerOrderPruner pruner = new ClosedPositionTriggerOrderPruner(jdbcTemplate);
        Instant closedAt = Instant.parse("2026-07-01T00:00:00Z");

        int canceled = pruner.prune(ProductLine.INVERSE_PERPETUAL, 1001L, "BTC-USD", MarginMode.ISOLATED,
                PositionSide.SHORT, closedAt);

        assertThat(canceled).isEqualTo(2);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(sql.capture(), args.capture());
        assertThat(sql.getValue())
                .contains("status = 'CANCELED'")
                .contains("reject_reason = ?")
                .contains("status = 'PENDING'")
                .contains("product_line = ?")
                .contains("position_side = ?");
        assertThat(args.getValue()).containsExactly(
                ClosedPositionTriggerOrderPruner.REASON,
                java.sql.Timestamp.from(closedAt),
                "INVERSE_PERPETUAL",
                1001L,
                "BTC-USD",
                "ISOLATED",
                "SHORT");
    }
}
