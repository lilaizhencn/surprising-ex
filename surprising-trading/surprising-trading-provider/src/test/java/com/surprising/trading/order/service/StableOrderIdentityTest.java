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
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.ValidationResult;
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

        AeronOrderCommandService first = service(aeron, properties);
        first.place(request(), validation());
        long firstTimestamp = System.currentTimeMillis();
        while (System.currentTimeMillis() == firstTimestamp) {
            Thread.onSpinWait();
        }
        AeronOrderCommandService reconstructed = service(aeron, properties);
        reconstructed.place(request(), validation());

        ArgumentCaptor<UUID> commandIds = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<byte[]> payloads = ArgumentCaptor.forClass(byte[].class);
        verify(aeron, times(2)).commandOutcome(eq(CoreMessageType.PLACE_ORDER), commandIds.capture(), eq(1001L),
                payloads.capture());
        assertThat(commandIds.getAllValues().get(0)).isEqualTo(commandIds.getAllValues().get(1));
        assertThat(TradingCommandCodec.decodePlaceOrder(payloads.getAllValues().get(0)).orderId())
                .isEqualTo(TradingCommandCodec.decodePlaceOrder(payloads.getAllValues().get(1)).orderId());
    }

    private static AeronOrderCommandService service(OrderAeronGateway aeron, TradingOrderProperties properties) {
        return new AeronOrderCommandService(aeron, properties);
    }

    private static PlaceOrderRequest request() {
        return new PlaceOrderRequest(1001, "stable-client", "BTC-USDT", OrderSide.BUY, OrderType.LIMIT,
                TimeInForce.GTC, 60_000, 2, MarginMode.CROSS, PositionSide.NET, false, false);
    }

    private static ValidationResult validation() {
        return ValidationResult.ok(7, InstrumentType.PERPETUAL, ContractType.LINEAR_PERPETUAL);
    }

    private static CoreResponse commandResponse(PlaceOrderCommand command, String clientOrderId) {
        CoreOrderStateView order = new CoreOrderStateView(command.orderId(), ProductLine.LINEAR_PERPETUAL,
                1001, command.symbol(), command.instrumentVersion(), command.side(), command.limitPriceTicks(),
                command.quantitySteps(), 0, command.quantitySteps(), command.reduceOnly(), command.marginMode(),
                command.positionSide(), command.orderType(), command.timeInForce(), command.postOnly(), clientOrderId,
                UUID.randomUUID(), -10, 25, 0, 1_000, 1_000, 1,
                "OPEN", 1);
        return new CoreResponse(ResponseStatus.APPLIED, ResponseStatus.APPLIED, CoreResultCode.NONE,
                1, 1, CoreCommandResultCodec.encode(new CoreCommandResultView(1,
                        UUID.fromString("20000000-0000-0000-0000-000000000001"), command.orderId(),
                        command.instrumentVersion(), 1, 41, 43, List.of(order), List.of())));
    }

}
