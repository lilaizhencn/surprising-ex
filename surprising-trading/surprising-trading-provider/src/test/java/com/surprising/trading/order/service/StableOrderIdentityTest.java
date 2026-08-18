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
import com.surprising.trading.order.model.InstrumentRule;
import com.surprising.trading.order.model.InstrumentRuleLookup;
import com.surprising.trading.order.model.MarkPriceLookup;
import com.surprising.trading.order.model.OrderFeeSnapshot;
import com.surprising.trading.order.model.ValidationResult;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
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
        InstrumentRuleLookup rules = Mockito.mock(InstrumentRuleLookup.class);
        MarkPriceLookup marks = Mockito.mock(MarkPriceLookup.class);
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getAeron().setNodeId(3);
        when(rules.currentRule("BTC-USDT")).thenReturn(Optional.of(perpetualRule()));
        when(marks.latestMarkPriceTicks("BTC-USDT", 7, 5_000)).thenReturn(OptionalLong.of(60_000));
        when(aeron.commandOutcome(eq(CoreMessageType.PLACE_ORDER), any(UUID.class), eq(1001L), any(byte[].class)))
                .thenAnswer(invocation -> {
                    PlaceOrderCommand command = TradingCommandCodec.decodePlaceOrder(invocation.getArgument(3));
                    return new CoreCommandOutcome.Terminal(commandResponse(command, "stable-client"));
                });

        AeronOrderCommandService first = service(aeron, rules, marks, properties);
        first.place(request(), validation(), fee());
        long firstTimestamp = System.currentTimeMillis();
        while (System.currentTimeMillis() == firstTimestamp) {
            Thread.onSpinWait();
        }
        AeronOrderCommandService reconstructed = service(aeron, rules, marks, properties);
        reconstructed.place(request(), validation(), fee());

        ArgumentCaptor<UUID> commandIds = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<byte[]> payloads = ArgumentCaptor.forClass(byte[].class);
        verify(aeron, times(2)).commandOutcome(eq(CoreMessageType.PLACE_ORDER), commandIds.capture(), eq(1001L),
                payloads.capture());
        assertThat(commandIds.getAllValues().get(0)).isEqualTo(commandIds.getAllValues().get(1));
        assertThat(TradingCommandCodec.decodePlaceOrder(payloads.getAllValues().get(0)).orderId())
                .isEqualTo(TradingCommandCodec.decodePlaceOrder(payloads.getAllValues().get(1)).orderId());
    }

    private static AeronOrderCommandService service(OrderAeronGateway aeron, InstrumentRuleLookup rules,
                                                    MarkPriceLookup marks, TradingOrderProperties properties) {
        return new AeronOrderCommandService(aeron, rules, marks, properties);
    }

    private static PlaceOrderRequest request() {
        return new PlaceOrderRequest(1001, "stable-client", "BTC-USDT", OrderSide.BUY, OrderType.LIMIT,
                TimeInForce.GTC, 60_000, 2, MarginMode.CROSS, PositionSide.NET, false, false);
    }

    private static ValidationResult validation() {
        return ValidationResult.ok(7, InstrumentType.PERPETUAL, ContractType.LINEAR_PERPETUAL);
    }

    private static OrderFeeSnapshot fee() {
        return new OrderFeeSnapshot(ProductLine.LINEAR_PERPETUAL, -10, 25, "test");
    }

    private static CoreResponse commandResponse(PlaceOrderCommand command, String clientOrderId) {
        CoreOrderStateView order = new CoreOrderStateView(command.orderId(), ProductLine.LINEAR_PERPETUAL,
                1001, command.symbol(), command.instrumentVersion(), command.side(), command.priceTicks(),
                command.quantitySteps(), 0, command.quantitySteps(), command.reduceOnly(), command.marginMode(),
                command.positionSide(), command.orderType(), command.timeInForce(), command.postOnly(), clientOrderId,
                UUID.randomUUID(), command.makerFeeRatePpm(), command.takerFeeRatePpm(), 1_000, 1_000, 1,
                "OPEN", 1);
        return new CoreResponse(ResponseStatus.APPLIED, ResponseStatus.APPLIED, CoreResultCode.NONE,
                1, 1, CoreCommandResultCodec.encode(new CoreCommandResultView(List.of(order), List.of())));
    }

    private static InstrumentRule perpetualRule() {
        return new InstrumentRule("BTC-USDT", 7, "TRADING", InstrumentType.PERPETUAL,
                ContractType.LINEAR_PERPETUAL, "BTC", "USDT", "USDT", Set.of("LIMIT", "MARKET"),
                Set.of("GTC", "IOC", "FOK", "GTX"), true, true, true, 1, 1, 1_000_000, 1,
                Long.MAX_VALUE, 1, 100_000_000, 10_000);
    }
}
