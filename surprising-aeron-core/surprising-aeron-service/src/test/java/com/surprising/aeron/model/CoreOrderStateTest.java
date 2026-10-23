package com.surprising.aeron.model;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CoreOrderStateTest {
    @Test
    void partialFillThenCancelKeepsUnfilledHistoryAndFeesForEveryProduct() {
        for (ProductLine line : ProductLine.values()) {
            var order = new CoreOrderState(1, line, 7, "1", CoreOrderSide.BUY,
                    100, 10, 0, false, CoreOrderStatus.OPEN, 1);
            var partial = order.fill(3, 4);
            var canceled = partial.cancel();

            assertThat(partial.executedQuantitySteps()).isEqualTo(3);
            assertThat(partial.remainingQuantitySteps()).isEqualTo(7);
            assertThat(partial.status()).isEqualTo(CoreOrderStatus.OPEN);
            assertThat(canceled.quantitySteps()).isEqualTo(10);
            assertThat(canceled.remainingQuantitySteps()).isEqualTo(7);
            assertThat(canceled.cumulativeFeeUnits()).isEqualTo(4);
            assertThat(canceled.status()).isEqualTo(CoreOrderStatus.CANCELED);
            assertThat(canceled.revision()).isEqualTo(3);
            assertThat(canceled.cancel()).isSameAs(canceled);
            assertThatThrownBy(() -> canceled.fill(1)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void fillingTheLastUnitWorksAtTheLongQuantityBoundary() {
        for (ProductLine line : ProductLine.values()) {
            var order = new CoreOrderState(1, line, 7, "1", CoreOrderSide.BUY,
                    100, Long.MAX_VALUE, Long.MAX_VALUE - 1, false, CoreOrderStatus.OPEN, 1);
            var filled = order.fill(1);

            assertThat(order.remainingQuantitySteps()).isEqualTo(1);
            assertThat(filled.executedQuantitySteps()).isEqualTo(Long.MAX_VALUE);
            assertThat(filled.remainingQuantitySteps()).isZero();
            assertThat(filled.status()).isEqualTo(CoreOrderStatus.FILLED);
            assertThatThrownBy(() -> filled.fill(1)).isInstanceOf(IllegalStateException.class);
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

    @Test
    void priceAndCommitChangesPreserveExecutionAndFinancialMetadata() {
        for (ProductLine line : ProductLine.values()) {
            var order = new CoreOrderState(1, line, 7, "1", CoreOrderSide.BUY, 100, 100,
                    10, 3, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                    CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "client-1", new UUID(0, 1),
                    -2, 5, 4, 7, 9, 100, 200, 300, CoreOrderStatus.OPEN, 1);
            var updated = order.replacePrice(120).withCommitMetadata(400, 500);

            assertThat(updated.priceTicks()).isEqualTo(120);
            assertThat(updated.matchingPriceTicks()).isEqualTo(120);
            assertThat(updated.executedQuantitySteps()).isEqualTo(3);
            assertThat(updated.remainingQuantitySteps()).isEqualTo(7);
            assertThat(updated.cumulativeFeeUnits()).isEqualTo(4);
            assertThat(updated.executedValueHigh()).isEqualTo(7);
            assertThat(updated.executedValueLow()).isEqualTo(9);
            assertThat(updated.createdAtEpochMillis()).isEqualTo(100);
            assertThat(updated.updatedAtEpochMillis()).isEqualTo(400);
            assertThat(updated.clusterPosition()).isEqualTo(500);
            assertThat(updated.reject().remainingQuantitySteps()).isEqualTo(7);
        }
    }
}
