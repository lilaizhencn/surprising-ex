package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.AdminBatchCancelOrdersRequest;
import com.surprising.trading.api.model.CancelOrderRequest;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.PositionMode;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.OrderFeeSnapshot;
import com.surprising.trading.order.model.OrderRecord;
import com.surprising.trading.order.model.ValidationResult;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** 订单入口只编排 JVM 用户事实流，不允许通过数据库仓储回退。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderServiceTest {

    @Mock
    private OrderValidator orderValidator;
    @Mock
    private ReduceOnlyValidator reduceOnlyValidator;
    @Mock
    private OrderPlacementStateService placementStateService;
    @Mock
    private OrderMarginCalculator orderMarginCalculator;
    @Mock
    private SpotReservationCalculator spotReservationCalculator;
    @Mock
    private OrderFeeSnapshotLookup feeSnapshotLookup;
    @Mock
    private AeronOrderCommandService aeronOrders;

    @Test
    void placeFailsClosedWithoutAeronGateway() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL);
        PlaceOrderRequest request = request("client-1");
        assertThatThrownBy(() -> service.place(request)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Aeron order gateway");
        verifyNoInteractions(aeronOrders);
    }

    @Test
    void missingFeeSnapshotFailsClosedBeforeAppendingOrderFact() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL, aeronOrders);
        when(aeronOrders.find(1001L, "no-fee")).thenReturn(null);
        when(feeSnapshotLookup.lookup(any(), anyLong(), anyString(), anyLong(), any()))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.place(request("no-fee"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancelAndReadCommandsDelegateToLocalUserPartition() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL);
        assertThatThrownBy(() -> service.cancel(new CancelOrderRequest(1001L, 902L)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Aeron order gateway");
    }

    @Test
    void adminCancelUsesLocalPartitionAndProductLine() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL);
        assertThatThrownBy(() -> service.adminCancelOrders(new AdminBatchCancelOrdersRequest(
                1001L, "BTC-USDT", 10, "risk"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void optionProductLineUsesTheLocalAccountFactStream() {
        OrderService service = service(ProductLine.OPTION);
        PlaceOrderRequest optionRequest = new PlaceOrderRequest(1001L, "option-1", "BTC-USDT",
                OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC, 60_000L, 10L,
                MarginMode.CROSS, PositionSide.NET, false, false);

        assertThatThrownBy(() -> service.place(optionRequest)).isInstanceOf(IllegalStateException.class);
    }

    private OrderService service(ProductLine productLine) {
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(productLine);
        properties.getKafka().setProductTopicsEnabled(true);
        return service(productLine, null);
    }

    private OrderService service(ProductLine productLine, AeronOrderCommandService aeron) {
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(productLine);
        properties.getKafka().setProductTopicsEnabled(true);
        when(placementStateService.positionMode(productLine, 1001L)).thenReturn(PositionMode.ONE_WAY);
        when(placementStateService.positionMarginModeConflict(productLine, 1001L, "BTC-USDT",
                MarginMode.CROSS)).thenReturn(false);
        when(orderValidator.validate(any())).thenReturn(ValidationResult.ok(7L));
        when(reduceOnlyValidator.validate(any())).thenReturn(ValidationResult.ok(7L));
        when(feeSnapshotLookup.lookup(any(), anyLong(), anyString(), anyLong(), any()))
                .thenReturn(Optional.of(new OrderFeeSnapshot(ProductLine.LINEAR_PERPETUAL, 100L, 200L, "JVM")));
        return new OrderService(properties, orderValidator, reduceOnlyValidator, placementStateService,
                orderMarginCalculator, spotReservationCalculator, feeSnapshotLookup, aeron, null);
    }

    private PlaceOrderRequest request(String clientOrderId) {
        return new PlaceOrderRequest(1001L, clientOrderId, "BTC-USDT", OrderSide.BUY, OrderType.LIMIT,
                TimeInForce.GTC, 60_000L, 10L, MarginMode.CROSS, PositionSide.NET, true, false);
    }

    private OrderResponse response(long orderId, String clientOrderId, OrderStatus status) {
        Instant now = Instant.parse("2026-08-02T00:00:00Z");
        return new OrderResponse(orderId, 1001L, clientOrderId, "BTC-USDT", 7L, OrderSide.BUY,
                OrderType.LIMIT, TimeInForce.GTC, 60_000L, 10L, 0L, 10L, MarginMode.CROSS,
                PositionSide.NET, 100L, 200L, true, false, status, null, now, now);
    }
}
