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
import com.surprising.aeron.protocol.CorePositionView;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyNoInteractions;

class OrderPlacementStateServiceTest {

    @Test
    void closeSelectsSymbolMarginAndSideFromTheSameHedgeSnapshot() {
        OrderAeronGateway aeron = mock(OrderAeronGateway.class);
        when(aeron.userState(1001L)).thenReturn(new CoreUserStateView(
                ProductLine.LINEAR_PERPETUAL, 1001, 7, CorePositionMode.HEDGE, List.of(), List.of(), List.of(
                position("ETH-USDT", CoreMarginMode.ISOLATED, CorePositionSide.SHORT, -9),
                position("BTC-USDT", CoreMarginMode.CROSS, CorePositionSide.SHORT, -8),
                position("BTC-USDT", CoreMarginMode.ISOLATED, CorePositionSide.LONG, 7),
                position("BTC-USDT", CoreMarginMode.ISOLATED, CorePositionSide.SHORT, 0),
                position("BTC-USDT", CoreMarginMode.ISOLATED, CorePositionSide.SHORT, -5))));
        var service = new OrderPlacementStateService(aeron);

        var result = service.requireClosePosition(ProductLine.LINEAR_PERPETUAL, 1001, "btc-usdt",
                MarginMode.ISOLATED, PositionSide.SHORT);

        assertThat(result.signedQuantitySteps()).isEqualTo(-5);
        assertThat(result.instrumentChangeId()).isEqualTo(7);
        verify(aeron).userState(1001);
        verifyNoMoreInteractions(aeron);
    }

    @Test
    void closeRejectsHedgeWithoutSideBeforeLookingForAnOpenPosition() {
        OrderAeronGateway aeron = mock(OrderAeronGateway.class);
        when(aeron.userState(1001L)).thenReturn(new CoreUserStateView(
                ProductLine.LINEAR_PERPETUAL, 1001, 7, CorePositionMode.HEDGE,
                List.of(), List.of(), List.of()));
        assertThatThrownBy(() -> new OrderPlacementStateService(aeron).requireClosePosition(
                ProductLine.LINEAR_PERPETUAL, 1001, "BTC-USDT", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("positionSide LONG or SHORT is required in HEDGE position mode");
        verify(aeron).userState(1001);
        verifyNoMoreInteractions(aeron);
    }

    @Test
    void closeRejectsFlatPositionAndSpotWithoutSubmittingAnything() {
        OrderAeronGateway aeron = mock(OrderAeronGateway.class);
        var service = new OrderPlacementStateService(aeron);
        assertThatThrownBy(() -> service.requireClosePosition(ProductLine.SPOT, 1001, "BTC-USDT", null, null))
                .hasMessage("open position not found");
        verifyNoInteractions(aeron);
        when(aeron.userState(1001L)).thenReturn(new CoreUserStateView(
                ProductLine.LINEAR_PERPETUAL, 1001, 7, List.of(), List.of(), List.of(
                position("BTC-USDT", CoreMarginMode.CROSS, CorePositionSide.NET, 0))));
        assertThatThrownBy(() -> service.requireClosePosition(
                ProductLine.LINEAR_PERPETUAL, 1001, "BTC-USDT", null, null))
                .hasMessage("open position not found");
    }

    private static CorePositionView position(String symbol, CoreMarginMode margin, CorePositionSide side, long qty) {
        return new CorePositionView(symbol, "USDT", margin, side, 7, qty, 100, 500, 0, 50);
    }

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
        assertThatThrownBy(() -> service.requireClosePosition(ProductLine.LINEAR_DELIVERY, 1001L,
                "BTC-USDT", null, null)).hasMessageContaining("product line mismatch");
    }

    @Test
    void missingAeronUserStateFailsClosed() {
        OrderAeronGateway aeron = mock(OrderAeronGateway.class);
        OrderPlacementStateService service = new OrderPlacementStateService(aeron);

        assertThatThrownBy(() -> service.positionMode(ProductLine.LINEAR_PERPETUAL, 1001L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Aeron user state not found");
        assertThatThrownBy(() -> service.requireClosePosition(ProductLine.LINEAR_PERPETUAL, 1001L,
                "BTC-USDT", null, null)).hasMessageContaining("Aeron user state not found");
    }
}
