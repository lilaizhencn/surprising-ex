package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.aeron.client.CoreCommandOutcome;
import com.surprising.aeron.protocol.CoreCommandResultCodec;
import com.surprising.aeron.protocol.CoreCommandResultView;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderStateView;
import com.surprising.aeron.protocol.CoreOrderPreflightView;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.trading.order.model.StableOrderIdentity;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.order.config.TradingOrderProperties;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class StableOrderIdentityTest {

    @Test
    void specializedTradingIdentitiesAreStableAndNamespaced() {
        ProductLine line = ProductLine.LINEAR_PERPETUAL;

        assertThat(StableOrderIdentity.triggerOrderId(line, 1001, "client-1"))
                .isEqualTo(StableOrderIdentity.triggerOrderId(line, 1001, "client-1"))
                .isNotEqualTo(StableOrderIdentity.orderId(line, 1001, "client-1"));
        assertThat(StableOrderIdentity.algoOrderId(line, 1001, "client-1"))
                .isEqualTo(StableOrderIdentity.algoOrderId(line, 1001, "client-1"))
                .isNotEqualTo(StableOrderIdentity.triggerOrderId(line, 1001, "client-1"));
        assertThat(StableOrderIdentity.triggerCommandId(line, 1001, "client-1"))
                .isEqualTo(StableOrderIdentity.triggerCommandId(line, 1001, "client-1"))
                .isNotEqualTo(StableOrderIdentity.commandId(line, 1001, "client-1"));
        assertThat(StableOrderIdentity.algoCommandId(line, 1001, "client-1"))
                .isEqualTo(StableOrderIdentity.algoCommandId(line, 1001, "client-1"))
                .isNotEqualTo(StableOrderIdentity.triggerCommandId(line, 1001, "client-1"));
        assertThat(StableOrderIdentity.algoOrderId(ProductLine.SPOT, 1001, "client-1"))
                .isNotEqualTo(StableOrderIdentity.algoOrderId(line, 1001, "client-1"));
        assertThatThrownBy(() -> StableOrderIdentity.algoOrderId(line, 1001, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void placeIdentitySurvivesProviderReconstruction() {
        OrderAeronGateway aeron = Mockito.mock(OrderAeronGateway.class);
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        properties.getAeron().setNodeId(3);
        when(aeron.preflight(eq(1001L), any(PlaceOrderCommand.class)))
                .thenReturn(new OrderAeronGateway.PreflightResult(CoreResultCode.NONE,
                        new CoreOrderPreflightView("USDT", 1L)));
        when(aeron.commandOutcome(eq(CoreMessageType.PLACE_ORDER), any(UUID.class), eq(1001L), any(byte[].class)))
                .thenAnswer(invocation -> {
                    PlaceOrderCommand command = TradingCommandCodec.decodePlaceOrder(invocation.getArgument(3));
                    return new CoreCommandOutcome.Terminal(commandResponse(command, "stable-client"));
                });

        OrderService first = service(aeron, properties);
        first.place(request());
        long firstTimestamp = System.currentTimeMillis();
        while (System.currentTimeMillis() == firstTimestamp) {
            Thread.onSpinWait();
        }
        OrderService reconstructed = service(aeron, properties);
        reconstructed.place(request());

        ArgumentCaptor<UUID> commandIds = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<byte[]> payloads = ArgumentCaptor.forClass(byte[].class);
        verify(aeron, times(2)).commandOutcome(eq(CoreMessageType.PLACE_ORDER), commandIds.capture(), eq(1001L),
                payloads.capture());
        assertThat(commandIds.getAllValues().get(0)).isEqualTo(commandIds.getAllValues().get(1));
        assertThat(TradingCommandCodec.decodePlaceOrder(payloads.getAllValues().get(0)).orderId())
                .isEqualTo(TradingCommandCodec.decodePlaceOrder(payloads.getAllValues().get(1)).orderId());
    }

    @Test
    void normalizedPlaceCommandPreservesStpAndRegistersBracketOrders() {
        OrderAeronGateway aeron = Mockito.mock(OrderAeronGateway.class);
        BracketOrderDispatcher bracketDispatcher = Mockito.mock(BracketOrderDispatcher.class);
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        properties.getAeron().setNodeId(3);
        when(aeron.preflight(eq(1001L), any(PlaceOrderCommand.class)))
                .thenReturn(new OrderAeronGateway.PreflightResult(CoreResultCode.NONE,
                        new CoreOrderPreflightView("USDT", 1L)));
        when(aeron.commandOutcome(eq(CoreMessageType.PLACE_ORDER), any(UUID.class), eq(1001L), any(byte[].class)))
                .thenAnswer(invocation -> {
                    PlaceOrderCommand command = TradingCommandCodec.decodePlaceOrder(invocation.getArgument(3));
                    return new CoreCommandOutcome.Terminal(commandResponse(command, "trimmed-client"));
                });

        OrderService orderService = new OrderService(properties, null, aeron, null,
                Mockito.mock(com.surprising.trading.matching.service.MatchingMarketDataService.class),
                null, null, null, bracketDispatcher);

        // Leading/trailing spaces on clientOrderId force normalize() to construct a new PlaceOrderRequest
        PlaceOrderRequest rawRequest = new PlaceOrderRequest(
                1001L, "  trimmed-client  ", "1",
                OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC,
                60_000L, 2L, null, null, false, false, null,
                65_000L, 58_000L,
                com.surprising.trading.api.model.TriggerPriceSource.MARK,
                com.surprising.trading.api.model.SelfTradePreventionMode.EXPIRE_MAKER);

        orderService.placeCommand(rawRequest);

        ArgumentCaptor<PlaceOrderRequest> preparedCaptor = ArgumentCaptor.forClass(PlaceOrderRequest.class);
        verify(bracketDispatcher).registerBracket(preparedCaptor.capture(), any());
        assertThat(preparedCaptor.getValue().takeProfitPriceTicks()).isEqualTo(65_000L);
        assertThat(preparedCaptor.getValue().stopLossPriceTicks()).isEqualTo(58_000L);
        assertThat(preparedCaptor.getValue().tpSlPriceSource())
                .isEqualTo(com.surprising.trading.api.model.TriggerPriceSource.MARK);
        assertThat(preparedCaptor.getValue().stpMode())
                .isEqualTo(com.surprising.trading.api.model.SelfTradePreventionMode.EXPIRE_MAKER);
    }

    private static OrderService service(OrderAeronGateway aeron, TradingOrderProperties properties) {
        return new OrderService(properties, null, aeron, null,
                org.mockito.Mockito.mock(com.surprising.trading.matching.service.MatchingMarketDataService.class),
                null, null, null);
    }

    private static PlaceOrderRequest request() {
        return new PlaceOrderRequest(1001, "stable-client", "1", OrderSide.BUY, OrderType.LIMIT,
                TimeInForce.GTC, 60_000, 2, MarginMode.CROSS, PositionSide.NET, false, false);
    }

    private static CoreResponse commandResponse(PlaceOrderCommand command, String clientOrderId) {
        CoreOrderStateView order = new CoreOrderStateView(command.orderId(), ProductLine.LINEAR_PERPETUAL,
                1001, command.instrumentId(), command.side(), command.limitPriceTicks(),
                command.quantitySteps(), 0, command.quantitySteps(), command.reduceOnly(), command.marginMode(),
                command.positionSide(), command.orderType(), command.timeInForce(), command.postOnly(), clientOrderId,
                UUID.randomUUID(), -10, 25, 0, 1_000, 1_000, 1,
                "OPEN", 1);
        return new CoreResponse(ResponseStatus.APPLIED, ResponseStatus.APPLIED, CoreResultCode.NONE,
                1, CoreCommandResultCodec.encode(new CoreCommandResultView(List.of(order), List.of())));
    }

}
