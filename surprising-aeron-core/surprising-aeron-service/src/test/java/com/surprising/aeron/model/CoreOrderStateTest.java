package com.surprising.aeron.model;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CoreOrderStateTest {
    @Test
    void terminalRecordsKeepHistoricalUnfilledQuantityForEveryProduct() {
        for (ProductLine line : ProductLine.values()) {
            for (CoreOrderStatus status : new CoreOrderStatus[]{CoreOrderStatus.CANCELED, CoreOrderStatus.REJECTED}) {
                var order = new CoreOrderState(1, line, 7, "1", CoreOrderSide.BUY,
                        100, 10, 3, false, status, 3);

                assertThat(order.quantitySteps()).isEqualTo(10);
                assertThat(order.executedQuantitySteps()).isEqualTo(3);
                assertThat(order.remainingQuantitySteps()).isEqualTo(7);
            }
        }
    }

    @Test
    void remainingQuantityWorksAtTheLongBoundary() {
        for (ProductLine line : ProductLine.values()) {
            var partial = new CoreOrderState(1, line, 7, "1", CoreOrderSide.BUY,
                    100, Long.MAX_VALUE, Long.MAX_VALUE - 1, false, CoreOrderStatus.OPEN, 1);
            var filled = new CoreOrderState(1, line, 7, "1", CoreOrderSide.BUY,
                    100, Long.MAX_VALUE, Long.MAX_VALUE, false, CoreOrderStatus.FILLED, 2);

            assertThat(partial.remainingQuantitySteps()).isEqualTo(1);
            assertThat(filled.remainingQuantitySteps()).isZero();
        }
    }

    @Test
    void invalidExecutionRangesAndFullyExecutedOpenOrdersAreRejected() {
        for (ProductLine line : ProductLine.values()) {
            for (long[] quantity : new long[][]{{0, 0}, {10, -1}, {10, 11}, {10, 10}}) {
                assertThatThrownBy(() -> new CoreOrderState(1, line, 7, "1", CoreOrderSide.BUY,
                        100, quantity[0], quantity[1], false, CoreOrderStatus.OPEN, 1))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }
    }
}
