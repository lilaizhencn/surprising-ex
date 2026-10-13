package com.surprising.aeron.service.state;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.state.model.CoreOrderStatus;
import com.surprising.product.api.ProductLine;
import static org.assertj.core.api.Assertions.*;

class OrderSlotReuseTest {
    private static ResolvedPlaceOrder intent(long id) {
        var order = new PlaceOrderCommand(id, "1", CoreOrderSide.BUY, 100, 10, false,
                CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "client-" + id);
        return new ResolvedPlaceOrder(order, CoreStateTestFixtures.runtimeInstrument(), 0, 100, 100, 100,
                ReservationKind.DERIVATIVE_MARGIN, "USDT", 0, 0);
    }
    @Test void authoritativeOrderWaitsForResponseRetirementBeforeReinitialization() {
        var lane = new AccountLaneState(0, 256);
        lane.bindOwner();
        var first = lane.preparedOrder(ProductLine.LINEAR_PERPETUAL, 7, intent(1), new UUID(1, 1), 0, 100, 200, 0);
        first.applyFillInPlace(10, 0, 3, CoreOrderStatus.FILLED, 2, 110, 210);
        first.executionValue(5, 6);
        var stable = first.snapshot();
        var response = first.copyIntoCommandSlot(null);
        assertThat(first.retireForReuse(4)).isTrue();
        lane.returnRetiredOrder(first);
        var beforeFence = lane.preparedOrder(ProductLine.LINEAR_PERPETUAL, 8, intent(2), new UUID(1, 2), 0, 120, 220, 3);
        assertThat(beforeFence).isNotSameAs(first);
        assertThat(first.orderId()).isEqualTo(1);
        var reused = lane.preparedOrder(ProductLine.LINEAR_PERPETUAL, 9, intent(3), new UUID(1, 3), 0, 130, 230, 4);
        assertThat(reused).isSameAs(first);
        assertThat(reused.orderId()).isEqualTo(3);
        assertThat(reused.userId()).isEqualTo(9);
        assertThat(reused.status()).isEqualTo(CoreOrderStatus.OPEN);
        assertThat(reused.remainingQuantitySteps()).isEqualTo(10);
        assertThat(reused.executedQuantitySteps()).isZero();
        assertThat(reused.cumulativeFeeUnits()).isZero();
        assertThat(reused.executedValueHigh()).isZero();
        assertThat(reused.executedValueLow()).isZero();
        assertThat(response.orderId()).isEqualTo(1);
        assertThat(stable.executedQuantitySteps()).isEqualTo(10);
        assertThatThrownBy(() -> reused.copyIntoCommandSlot(stable)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void afterImagesFreezeAtLaneCompletionAndMoveWithPhysicalBuffers() {
        var laneBuffer = new RuntimeOrderChangeBuffer();
        var ownerBuffer = new RuntimeOrderChangeBuffer();
        var source = CoreStateTestFixtures.order(1, 7, 0, 10);
        laneBuffer.putLive(1, source);
        source.applyFillInPlace(3, 7, 4, CoreOrderStatus.OPEN, 2, 110, 210);
        laneBuffer.freezeValues();
        ownerBuffer.swapWith(laneBuffer);
        var image = ownerBuffer.get(1);
        source.applyFillInPlace(10, 0, 9, CoreOrderStatus.FILLED, 3, 120, 220);
        laneBuffer.putLive(1, source); laneBuffer.freezeValues();
        assertThat(image.executedQuantitySteps()).isEqualTo(3);
        assertThat(image.cumulativeFeeUnits()).isEqualTo(4);
        assertThat(laneBuffer.get(1).executedQuantitySteps()).isEqualTo(10);
        ownerBuffer.clear();
        assertThat(image.instrument()).isNull();
        assertThat(laneBuffer.get(1).instrument()).isNotNull();
        ownerBuffer.put(1, source);
        assertThat(ownerBuffer.get(1)).isSameAs(image);
    }
}
