package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CoreUserStateView;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.PositionMode;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderPlacementStateServiceTest {

    @Test
    void perpetualPositionModeComesFromAeronUserState() {
        OrderAeronGateway aeron = mock(OrderAeronGateway.class);
        when(aeron.userState(1001L)).thenReturn(new CoreUserStateView(
                ProductLine.LINEAR_PERPETUAL, 1001L, 7L, CorePositionMode.HEDGE,
                List.of(), List.of(), List.of()));

        OrderPlacementStateService service = new OrderPlacementStateService(aeron);

        assertThat(service.positionMode(ProductLine.LINEAR_PERPETUAL, 1001L)).isEqualTo(PositionMode.HEDGE);
    }

    @Test
    void mismatchedProductLineFailsClosed() {
        OrderAeronGateway aeron = mock(OrderAeronGateway.class);
        when(aeron.userState(1001L)).thenReturn(new CoreUserStateView(
                ProductLine.LINEAR_PERPETUAL, 1001L, 7L, List.of(), List.of(), List.of()));
        OrderPlacementStateService service = new OrderPlacementStateService(aeron);

        assertThatThrownBy(() -> service.positionMode(ProductLine.LINEAR_DELIVERY, 1001L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("product line mismatch");
    }

    @Test
    void missingAeronUserStateFailsClosed() {
        OrderAeronGateway aeron = mock(OrderAeronGateway.class);
        OrderPlacementStateService service = new OrderPlacementStateService(aeron);

        assertThatThrownBy(() -> service.positionMode(ProductLine.LINEAR_PERPETUAL, 1001L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Aeron user state not found");
    }
}
