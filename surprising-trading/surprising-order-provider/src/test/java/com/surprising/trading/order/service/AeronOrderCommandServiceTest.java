package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderStateView;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.ReplaceOrderCommand;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
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
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AeronOrderCommandServiceTest {

    @Mock
    private OrderAeronGateway aeron;
    @Mock
    private InstrumentRuleLookup instrumentRules;
    @Mock
    private MarkPriceLookup markPrices;

    private AeronOrderCommandService service;

    @BeforeEach
    void setUp() {
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getAeron().setNodeId(3);
        service = new AeronOrderCommandService(aeron, new AeronOrderIdGenerator(properties), instrumentRules,
                markPrices, properties);
    }

    @Test
    void placeMapsMarketProtectionFeesAndDerivativeReservation() {
        PlaceOrderRequest request = new PlaceOrderRequest(1001, "client-1", "BTC-USDT", OrderSide.BUY,
                OrderType.MARKET, TimeInForce.IOC, 0, 7, MarginMode.ISOLATED, PositionSide.LONG,
                false, false);
        when(instrumentRules.currentRule("BTC-USDT")).thenReturn(Optional.of(perpetualRule()));
        when(markPrices.latestMarkPriceTicks("BTC-USDT", 7, 5_000)).thenReturn(OptionalLong.of(60_000L));
        when(aeron.order(eq(1001L), anyLong())).thenAnswer(invocation -> orderView(invocation.getArgument(1), request));

        assertThat(service.place(request,
                ValidationResult.ok(7, InstrumentType.PERPETUAL, ContractType.LINEAR_PERPETUAL),
                new OrderFeeSnapshot(ProductLine.LINEAR_PERPETUAL, -10, 25, "test")).status())
                .isEqualTo(OrderStatus.ACCEPTED);

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(aeron).command(eq(CoreMessageType.PLACE_ORDER), org.mockito.ArgumentMatchers.any(UUID.class),
                eq(1001L), payload.capture());
        PlaceOrderCommand command = TradingCommandCodec.decodePlaceOrder(payload.getValue());
        assertThat(command.orderType()).isEqualTo(CoreOrderType.MARKET);
        assertThat(command.timeInForce()).isEqualTo(CoreTimeInForce.IOC);
        assertThat(command.priceTicks()).isZero();
        assertThat(command.matchingPriceTicks()).isGreaterThan(60_000L);
        assertThat(command.reservationKind()).isEqualTo(ReservationKind.DERIVATIVE_MARGIN);
        assertThat(command.reservationAsset()).isEqualTo("USDT");
        assertThat(command.reservedUnits()).isZero();
        assertThat(command.clientOrderId()).isEqualTo("client-1");
        assertThat(command.makerFeeRatePpm()).isEqualTo(-10);
        assertThat(command.takerFeeRatePpm()).isEqualTo(25);
    }

    @Test
    void cancelMapsOrderIdentityAndReturnsAuthoritativeState() {
        PlaceOrderRequest request = new PlaceOrderRequest(1001, "client-2", "BTC-USDT", OrderSide.SELL,
                OrderType.LIMIT, TimeInForce.GTC, 61_000, 3, MarginMode.CROSS, PositionSide.NET,
                false, false);
        when(aeron.order(1001, 99)).thenReturn(orderView(99, request));

        assertThat(service.cancel(1001, 99).orderId()).isEqualTo(99);

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(aeron).command(eq(CoreMessageType.CANCEL_ORDER), org.mockito.ArgumentMatchers.any(UUID.class),
                eq(1001L), payload.capture());
        assertThat(TradingCommandCodec.decodeCancelOrder(payload.getValue()).orderId()).isEqualTo(99);
    }

    @Test
    void replaceCarriesCompleteReplacementInOneCoreCommand() {
        PlaceOrderRequest originalRequest = new PlaceOrderRequest(1001, "old", "BTC-USDT", OrderSide.BUY,
                OrderType.LIMIT, TimeInForce.GTC, 60_000, 5, MarginMode.CROSS, PositionSide.NET,
                false, false);
        PlaceOrderRequest replacementRequest = new PlaceOrderRequest(1001, "new", "BTC-USDT", OrderSide.BUY,
                OrderType.LIMIT, TimeInForce.GTX, 59_000, 4, MarginMode.CROSS, PositionSide.NET,
                false, true);
        com.surprising.trading.api.model.OrderResponse original = new com.surprising.trading.api.model.OrderResponse(
                77, 1001, "old", "BTC-USDT", 7, OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC,
                60_000, 5, 0, 5, MarginMode.CROSS, PositionSide.NET, -10, 25,
                false, false, OrderStatus.ACCEPTED, null,
                java.time.Instant.ofEpochMilli(1_000), java.time.Instant.ofEpochMilli(1_000));
        when(instrumentRules.currentRule("BTC-USDT")).thenReturn(Optional.of(perpetualRule()));
        when(aeron.order(eq(1001L), anyLong())).thenAnswer(invocation -> {
            long orderId = invocation.getArgument(1);
            return orderId == 77 ? orderView(orderId, originalRequest) : orderView(orderId, replacementRequest);
        });

        assertThat(service.replace(original, replacementRequest,
                ValidationResult.ok(7, InstrumentType.PERPETUAL, ContractType.LINEAR_PERPETUAL),
                new OrderFeeSnapshot(ProductLine.LINEAR_PERPETUAL, -10, 25, "test"))
                .replacementOrder().clientOrderId()).isEqualTo("new");

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(aeron).command(eq(CoreMessageType.REPLACE_ORDER), org.mockito.ArgumentMatchers.any(UUID.class),
                eq(1001L), payload.capture());
        ReplaceOrderCommand command = TradingCommandCodec.decodeReplaceOrder(payload.getValue());
        assertThat(command.originalOrderId()).isEqualTo(77);
        assertThat(command.replacement().clientOrderId()).isEqualTo("new");
        assertThat(command.replacement().quantitySteps()).isEqualTo(4);
        assertThat(command.replacement().timeInForce()).isEqualTo(CoreTimeInForce.GTX);
        assertThat(command.replacement().postOnly()).isTrue();
        assertThat(command.replacement().reservedUnits()).isZero();
    }

    private static InstrumentRule perpetualRule() {
        return new InstrumentRule("BTC-USDT", 7, "TRADING", InstrumentType.PERPETUAL,
                ContractType.LINEAR_PERPETUAL, "BTC", "USDT", "USDT",
                Set.of("LIMIT", "MARKET"), Set.of("GTC", "IOC", "FOK", "GTX"),
                true, true, true, 1, 1, 1_000_000, 1, Long.MAX_VALUE, 1, 100_000_000, 10_000);
    }

    private static CoreOrderStateView orderView(long orderId, PlaceOrderRequest request) {
        return new CoreOrderStateView(orderId, ProductLine.LINEAR_PERPETUAL, request.userId(), request.symbol(), 7,
                CoreOrderSide.valueOf(request.side().name()), request.priceTicks(), request.quantitySteps(),
                0, request.quantitySteps(), request.reduceOnly(), CoreMarginMode.valueOf(request.marginMode().name()),
                CorePositionSide.valueOf(request.positionSide().name()), CoreOrderType.valueOf(request.orderType().name()),
                CoreTimeInForce.valueOf(request.timeInForce().name()), request.postOnly(), request.clientOrderId(),
                UUID.randomUUID(), -10, 25, 1_000, 1_000, 10, "OPEN", 1);
    }
}
