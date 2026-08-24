package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.client.CoreCommandOutcome;
import com.surprising.aeron.protocol.AmendOrderCommand;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreCommandResultCodec;
import com.surprising.aeron.protocol.CoreCommandResultView;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderPreflightView;
import com.surprising.aeron.protocol.CoreOrderStateView;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.ReplaceOrderCommand;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.AmendOrderRequest;
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
import java.util.List;
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
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        service = new AeronOrderCommandService(aeron, instrumentRules, markPrices, properties);
        lenient().when(aeron.preflight(anyLong(), any(PlaceOrderCommand.class)))
                .thenReturn(new OrderAeronGateway.PreflightResult(CoreResultCode.NONE,
                        new CoreOrderPreflightView("USDT", 1L)));
        lenient().when(markPrices.latestMarkPriceTicks(any(), anyLong(), anyLong()))
                .thenReturn(OptionalLong.of(60_000L));
    }

    @Test
    void placeMapsMarketProtectionFeesAndDerivativeReservation() {
        PlaceOrderRequest request = new PlaceOrderRequest(1001, "client-1", "BTC-USDT", OrderSide.BUY,
                OrderType.MARKET, TimeInForce.IOC, 0, 7, MarginMode.ISOLATED, PositionSide.LONG,
                false, false);
        when(instrumentRules.currentRule("BTC-USDT")).thenReturn(Optional.of(perpetualRule()));
        when(markPrices.latestMarkPriceTicks("BTC-USDT", 7, 5_000)).thenReturn(OptionalLong.of(60_000L));
        when(aeron.commandOutcome(eq(CoreMessageType.PLACE_ORDER), org.mockito.ArgumentMatchers.any(UUID.class),
                eq(1001L), org.mockito.ArgumentMatchers.any(byte[].class))).thenAnswer(invocation -> {
            PlaceOrderCommand command = TradingCommandCodec.decodePlaceOrder(invocation.getArgument(3));
            return new CoreCommandOutcome.Terminal(commandResponse(orderView(command.orderId(), request), List.of()));
        });

        assertThat(service.place(request,
                ValidationResult.ok(7, InstrumentType.PERPETUAL, ContractType.LINEAR_PERPETUAL),
                new OrderFeeSnapshot(ProductLine.LINEAR_PERPETUAL, -10, 25, "test")).status())
                .isEqualTo(OrderStatus.ACCEPTED);

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(aeron).commandOutcome(eq(CoreMessageType.PLACE_ORDER), org.mockito.ArgumentMatchers.any(UUID.class),
                eq(1001L), payload.capture());
        PlaceOrderCommand command = TradingCommandCodec.decodePlaceOrder(payload.getValue());
        ArgumentCaptor<PlaceOrderCommand> preflight = ArgumentCaptor.forClass(PlaceOrderCommand.class);
        verify(aeron).preflight(eq(1001L), preflight.capture());
        assertThat(preflight.getValue()).isEqualTo(command);
        assertThat(command.orderType()).isEqualTo(CoreOrderType.MARKET);
        assertThat(command.timeInForce()).isEqualTo(CoreTimeInForce.IOC);
        assertThat(command.limitPriceTicks()).isEqualTo(60_600L);
        assertThat(command.executionPriceTicks()).isEqualTo(60_600L);
        assertThat(command.reservationPriceTicks()).isEqualTo(60_600L);
        assertThat(command.markPriceTicks()).isEqualTo(60_000L);
        assertThat(command.reservationKind()).isEqualTo(ReservationKind.DERIVATIVE_MARGIN);
        assertThat(command.reservationAsset()).isEqualTo("USDT");
        assertThat(command.reservedUnits()).isZero();
        assertThat(command.clientOrderId()).isEqualTo("client-1");
        assertThat(command.makerFeeRatePpm()).isEqualTo(-10);
        assertThat(command.takerFeeRatePpm()).isEqualTo(25);
    }

    @Test
    void placeUsesAuthoritativeCommandResponseWithoutOrderQuery() {
        PlaceOrderRequest request = new PlaceOrderRequest(1001, "client-response", "BTC-USDT", OrderSide.BUY,
                OrderType.LIMIT, TimeInForce.GTC, 60_000, 2, MarginMode.CROSS, PositionSide.NET,
                false, false);
        when(instrumentRules.currentRule("BTC-USDT")).thenReturn(Optional.of(perpetualRule()));
        when(aeron.commandOutcome(eq(CoreMessageType.PLACE_ORDER), org.mockito.ArgumentMatchers.any(UUID.class),
                eq(1001L), org.mockito.ArgumentMatchers.any(byte[].class))).thenAnswer(invocation -> {
            PlaceOrderCommand command = TradingCommandCodec.decodePlaceOrder(invocation.getArgument(3));
            return new CoreCommandOutcome.Terminal(commandResponse(orderView(command.orderId(), request), List.of()));
        });

        assertThat(service.place(request,
                ValidationResult.ok(7, InstrumentType.PERPETUAL, ContractType.LINEAR_PERPETUAL),
                new OrderFeeSnapshot(ProductLine.LINEAR_PERPETUAL, -10, 25, "test")).orderId())
                .isPositive();

        verify(aeron, times(1)).commandOutcome(eq(CoreMessageType.PLACE_ORDER),
                org.mockito.ArgumentMatchers.any(UUID.class), eq(1001L),
                org.mockito.ArgumentMatchers.any(byte[].class));
    }

    @Test
    void preflightRejectionStopsPlaceCommandBeforeSubmittingToCore() {
        PlaceOrderRequest request = new PlaceOrderRequest(1001, "client-no-funds", "BTC-USDT", OrderSide.BUY,
                OrderType.LIMIT, TimeInForce.GTC, 60_000, 2, MarginMode.CROSS, PositionSide.NET,
                false, false);
        when(instrumentRules.currentRule("BTC-USDT")).thenReturn(Optional.of(perpetualRule()));
        when(aeron.preflight(eq(1001L), org.mockito.ArgumentMatchers.any(PlaceOrderCommand.class)))
                .thenReturn(new OrderAeronGateway.PreflightResult(CoreResultCode.INSUFFICIENT_AVAILABLE_BALANCE, null));

        AeronOrderCommandService.CommandExecution execution = service.placeCommand(request,
                ValidationResult.ok(7, InstrumentType.PERPETUAL, ContractType.LINEAR_PERPETUAL),
                new OrderFeeSnapshot(ProductLine.LINEAR_PERPETUAL, -10, 25, "test"));

        assertThat(execution.outcome()).isInstanceOf(CoreCommandOutcome.Terminal.class);
        assertThat(((CoreCommandOutcome.Terminal) execution.outcome()).response().resultCode())
                .isEqualTo(CoreResultCode.INSUFFICIENT_AVAILABLE_BALANCE);
        verify(aeron, never()).commandOutcome(eq(CoreMessageType.PLACE_ORDER), any(UUID.class), eq(1001L),
                org.mockito.ArgumentMatchers.any(byte[].class));
    }

    @Test
    void clientOrderIdCommandIdentityIsStableAcrossChangedPayloads() {
        PlaceOrderRequest request = new PlaceOrderRequest(1001, "client-identity", "BTC-USDT", OrderSide.BUY,
                OrderType.LIMIT, TimeInForce.GTC, 60_000, 2, MarginMode.CROSS, PositionSide.NET,
                false, false);
        PlaceOrderRequest changed = new PlaceOrderRequest(1001, "client-identity", "BTC-USDT", OrderSide.BUY,
                OrderType.LIMIT, TimeInForce.GTC, 60_000, 3, MarginMode.CROSS, PositionSide.NET,
                false, false);
        when(instrumentRules.currentRule("BTC-USDT")).thenReturn(Optional.of(perpetualRule()));
        when(aeron.commandOutcome(eq(CoreMessageType.PLACE_ORDER), org.mockito.ArgumentMatchers.any(UUID.class),
                eq(1001L), org.mockito.ArgumentMatchers.any(byte[].class))).thenAnswer(invocation -> {
            PlaceOrderCommand command = TradingCommandCodec.decodePlaceOrder(invocation.getArgument(3));
            PlaceOrderRequest viewRequest = command.quantitySteps() == request.quantitySteps() ? request : changed;
            return new CoreCommandOutcome.Terminal(commandResponse(orderView(command.orderId(), viewRequest), List.of()));
        });

        ValidationResult validation = ValidationResult.ok(7, InstrumentType.PERPETUAL, ContractType.LINEAR_PERPETUAL);
        OrderFeeSnapshot fee = new OrderFeeSnapshot(ProductLine.LINEAR_PERPETUAL, -10, 25, "test");
        service.place(request, validation, fee);
        service.place(request, validation, fee);
        service.place(changed, validation, fee);

        ArgumentCaptor<UUID> commandIds = ArgumentCaptor.forClass(UUID.class);
        verify(aeron, times(3)).commandOutcome(eq(CoreMessageType.PLACE_ORDER), commandIds.capture(), eq(1001L),
                org.mockito.ArgumentMatchers.any(byte[].class));
        assertThat(commandIds.getAllValues().get(0)).isEqualTo(commandIds.getAllValues().get(1));
        assertThat(commandIds.getAllValues().get(0)).isEqualTo(commandIds.getAllValues().get(2));
    }

    @Test
    void cancelMapsOrderIdentityAndReturnsAuthoritativeState() {
        PlaceOrderRequest request = new PlaceOrderRequest(1001, "client-2", "BTC-USDT", OrderSide.SELL,
                OrderType.LIMIT, TimeInForce.GTC, 61_000, 3, MarginMode.CROSS, PositionSide.NET,
                false, false);
        when(aeron.commandOutcome(eq(CoreMessageType.CANCEL_ORDER), org.mockito.ArgumentMatchers.any(UUID.class),
                eq(1001L), org.mockito.ArgumentMatchers.any(byte[].class)))
                .thenReturn(new CoreCommandOutcome.Terminal(commandResponse(orderView(99, request), List.of())));

        assertThat(service.cancel(1001, 99).orderId()).isEqualTo(99);

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(aeron).commandOutcome(eq(CoreMessageType.CANCEL_ORDER), org.mockito.ArgumentMatchers.any(UUID.class),
                eq(1001L), payload.capture());
        assertThat(TradingCommandCodec.decodeCancelOrder(payload.getValue()).orderId()).isEqualTo(99);
    }

    @Test
    void lifecycleOpenOrdersUsesAuthorityQueryAndRejectsCrossProductLineResults() {
        when(aeron.lifecycleOpenOrders("BTC-USDT", 1000))
                .thenReturn(List.of(orderView(91, new PlaceOrderRequest(1001, "client-91", "BTC-USDT",
                        OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC, 60_000, 2, MarginMode.CROSS,
                        PositionSide.NET, false, false))));

        assertThat(service.lifecycleOpenOrders("BTC-USDT", 1000)).hasSize(1);
        verify(aeron).lifecycleOpenOrders("BTC-USDT", 1000);

        when(aeron.lifecycleOpenOrders("BTC-USDT", 1))
                .thenReturn(List.of(orderView(ProductLine.OPTION, 92, new PlaceOrderRequest(1001, "client-92",
                        "BTC-USDT", OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC, 60_000, 2,
                        MarginMode.CROSS, PositionSide.NET, false, false))));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.lifecycleOpenOrders("BTC-USDT", 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("product line");
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
        when(aeron.commandOutcome(eq(CoreMessageType.REPLACE_ORDER), org.mockito.ArgumentMatchers.any(UUID.class),
                eq(1001L), org.mockito.ArgumentMatchers.any(byte[].class))).thenAnswer(invocation -> {
            ReplaceOrderCommand command = TradingCommandCodec.decodeReplaceOrder(invocation.getArgument(3));
            return new CoreCommandOutcome.Terminal(new CoreResponse(ResponseStatus.APPLIED, ResponseStatus.APPLIED, CoreResultCode.NONE,
                    1, 1, CoreCommandResultCodec.encode(new CoreCommandResultView(1,
                            UUID.fromString("10000000-0000-0000-0000-000000000001"),
                            command.replacement().orderId(), 7, 1, 17, 19, List.of(
                            orderView(77, originalRequest),
                            orderView(command.replacement().orderId(), replacementRequest)), List.of()))));
        });

        assertThat(service.replace(original, replacementRequest,
                ValidationResult.ok(7, InstrumentType.PERPETUAL, ContractType.LINEAR_PERPETUAL),
                new OrderFeeSnapshot(ProductLine.LINEAR_PERPETUAL, -10, 25, "test"))
                .replacementOrder().clientOrderId()).isEqualTo("new");

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(aeron).commandOutcome(eq(CoreMessageType.REPLACE_ORDER), org.mockito.ArgumentMatchers.any(UUID.class),
                eq(1001L), payload.capture());
        ReplaceOrderCommand command = TradingCommandCodec.decodeReplaceOrder(payload.getValue());
        assertThat(command.originalOrderId()).isEqualTo(77);
        assertThat(command.replacement().clientOrderId()).isEqualTo("new");
        assertThat(command.replacement().quantitySteps()).isEqualTo(4);
        assertThat(command.replacement().timeInForce()).isEqualTo(CoreTimeInForce.GTX);
        assertThat(command.replacement().postOnly()).isTrue();
        assertThat(command.replacement().reservedUnits()).isZero();
    }

    @Test
    void amendCarriesOnlyPatchAndDoesNotReadOriginalOrder() {
        AmendOrderRequest request = new AmendOrderRequest(1001, 77, "new", 59_000L, 4L,
                TimeInForce.GTX, true);
        PlaceOrderRequest originalRequest = new PlaceOrderRequest(1001, "old", "BTC-USDT", OrderSide.BUY,
                OrderType.LIMIT, TimeInForce.GTC, 60_000, 5, MarginMode.CROSS, PositionSide.NET,
                false, false);
        PlaceOrderRequest replacementRequest = new PlaceOrderRequest(1001, "new", "BTC-USDT", OrderSide.BUY,
                OrderType.LIMIT, TimeInForce.GTX, 59_000, 4, MarginMode.CROSS, PositionSide.NET,
                false, true);
        when(aeron.commandOutcome(eq(CoreMessageType.AMEND_ORDER), org.mockito.ArgumentMatchers.any(UUID.class),
                eq(1001L), org.mockito.ArgumentMatchers.any(byte[].class))).thenAnswer(invocation -> {
            AmendOrderCommand command = TradingCommandCodec.decodeAmendOrder(invocation.getArgument(3));
            return new CoreCommandOutcome.Terminal(new CoreResponse(ResponseStatus.APPLIED, ResponseStatus.APPLIED, CoreResultCode.NONE,
                    1, 1, CoreCommandResultCodec.encode(new CoreCommandResultView(1,
                            UUID.fromString("10000000-0000-0000-0000-000000000002"),
                            command.replacementOrderId(), 7, 1, 23, 29, List.of(
                            orderView(77, originalRequest), orderView(command.replacementOrderId(), replacementRequest)),
                            List.of()))));
        });

        assertThat(service.replace(request).replacementOrder().clientOrderId()).isEqualTo("new");

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(aeron).commandOutcome(eq(CoreMessageType.AMEND_ORDER), org.mockito.ArgumentMatchers.any(UUID.class),
                eq(1001L), payload.capture());
        AmendOrderCommand command = TradingCommandCodec.decodeAmendOrder(payload.getValue());
        assertThat(command.originalOrderId()).isEqualTo(77);
        assertThat(command.priceTicks()).isEqualTo(59_000L);
        assertThat(command.quantitySteps()).isEqualTo(4L);
        assertThat(command.timeInForce()).isEqualTo(CoreTimeInForce.GTX);
        assertThat(command.postOnly()).isTrue();
        verify(aeron, times(1)).commandOutcome(eq(CoreMessageType.AMEND_ORDER),
                org.mockito.ArgumentMatchers.any(UUID.class), eq(1001L),
                org.mockito.ArgumentMatchers.any(byte[].class));
    }

    private static InstrumentRule perpetualRule() {
        return new InstrumentRule("BTC-USDT", 7, "TRADING", InstrumentType.PERPETUAL,
                ContractType.LINEAR_PERPETUAL, "BTC", "USDT", "USDT",
                Set.of("LIMIT", "MARKET"), Set.of("GTC", "IOC", "FOK", "GTX"),
                true, true, true, 1, 1, 1_000_000, 1, Long.MAX_VALUE, 1, 100_000_000, 10_000);
    }

    private static CoreOrderStateView orderView(long orderId, PlaceOrderRequest request) {
        return orderView(ProductLine.LINEAR_PERPETUAL, orderId, request);
    }

    private static CoreOrderStateView orderView(ProductLine productLine, long orderId, PlaceOrderRequest request) {
        return new CoreOrderStateView(orderId, productLine, request.userId(), request.symbol(), 7,
                CoreOrderSide.valueOf(request.side().name()), request.priceTicks(), request.quantitySteps(),
                0, request.quantitySteps(), request.reduceOnly(), CoreMarginMode.valueOf(request.marginMode().name()),
                CorePositionSide.valueOf(request.positionSide().name()), CoreOrderType.valueOf(request.orderType().name()),
                CoreTimeInForce.valueOf(request.timeInForce().name()), request.postOnly(), request.clientOrderId(),
                UUID.randomUUID(), -10, 25, 1_000, 1_000, 10, "OPEN", 1);
    }

    private static CoreResponse commandResponse(CoreOrderStateView order,
                                                List<com.surprising.aeron.protocol.CoreExecutionView> executions) {
        return new CoreResponse(ResponseStatus.APPLIED, ResponseStatus.APPLIED, CoreResultCode.NONE,
                1, 1, CoreCommandResultCodec.encode(new CoreCommandResultView(1,
                        UUID.fromString("10000000-0000-0000-0000-000000000003"),
                        order.orderId(), order.instrumentVersion(), 1, 31, 37, List.of(order), executions)));
    }
}
