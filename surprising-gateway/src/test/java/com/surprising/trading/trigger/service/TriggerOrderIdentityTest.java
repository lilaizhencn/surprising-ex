package com.surprising.trading.trigger.service;

import com.surprising.trading.trigger.repository.TriggerOrderProjectionRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.surprising.aeron.protocol.CoreTriggerOrderStateView;
import com.surprising.aeron.protocol.PlaceTriggerOrderCommand;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PlaceTriggerOrderRequest;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.api.model.TriggerOrderType;
import com.surprising.trading.api.model.TriggerPriceSource;
import com.surprising.trading.trigger.config.TriggerProperties;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TriggerOrderIdentityTest {

    @Test
    void ocoLookupUsesTheExistingProductUserAndOrderedLegIdentity() {
        var core = mock(TriggerOrderAeronGateway.class);
        var option = properties(); option.setProductLine(ProductLine.OPTION);
        var service = new TriggerOrderService(option, core, null, null);
        var id = service.ocoCommandId(1001, "tp", "sl");
        assertThat(id).isEqualTo(UUID.nameUUIDFromBytes("OPTION:OCO:1001:tp:sl".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(service.ocoCommandId(1002, "tp", "sl")).isNotEqualTo(id);
        assertThat(service.ocoCommandId(1001, "sl", "tp")).isNotEqualTo(id);
        assertThat(new TriggerOrderService(properties(), core, null, null).ocoCommandId(1001, "tp", "sl")).isNotEqualTo(id);
        org.mockito.Mockito.verifyNoInteractions(core);
    }

    @Test
    void privateTriggerQueryDoesNotReturnToCoreOnAReadViewMiss() {
        var gateway = mock(TriggerOrderAeronGateway.class);
        var queries = mock(TriggerOrderProjectionRepository.class);
        var service = new TriggerOrderService(properties(), gateway, null, queries);
        when(queries.get(ProductLine.LINEAR_PERPETUAL,1001L,99L)).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(()->service.get(1001L,99L)).isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.verifyNoInteractions(gateway);
    }

    @Test
    void missingProjectionIsUnavailableForUserAdminAndTimelineWithoutCoreFallback() {
        var gateway = mock(TriggerOrderAeronGateway.class);
        var service = new TriggerOrderService(properties(), gateway, null, null);
        assertThatThrownBy(() -> service.get(1001L, 99L)).isInstanceOf(com.surprising.realtime.api.exception.ReadViewUnavailableException.class);
        assertThatThrownBy(() -> service.get(99L, ProductLine.LINEAR_PERPETUAL)).isInstanceOf(com.surprising.realtime.api.exception.ReadViewUnavailableException.class);
        assertThatThrownBy(() -> service.adminOrders(null, null, null, null, 10, null, false, null)).isInstanceOf(com.surprising.realtime.api.exception.ReadViewUnavailableException.class);
        assertThatThrownBy(() -> service.adminTimeline(99L)).isInstanceOf(com.surprising.realtime.api.exception.ReadViewUnavailableException.class);
        org.mockito.Mockito.verifyNoInteractions(gateway);
    }

    @Test
    void terminalDetailsAndActualPriceSourceComeFromProjectionWithoutCoreReads() {
        var core = mock(TriggerOrderAeronGateway.class);
        var projection = mock(TriggerOrderProjectionRepository.class);
        var service = new TriggerOrderService(properties(), core, null, projection);
        var state = new CoreTriggerOrderStateView(Long.MAX_VALUE, ProductLine.LINEAR_PERPETUAL, 1001,
                "client", "oco", "1", com.surprising.aeron.protocol.CoreOrderSide.SELL,
                com.surprising.aeron.protocol.CoreTriggerOrderType.TAKE_PROFIT,
                com.surprising.aeron.protocol.CoreTriggerCondition.GREATER_OR_EQUAL, 120, 0, 0, 0, 0, 0,
                com.surprising.aeron.protocol.CoreOrderType.MARKET, com.surprising.aeron.protocol.CoreTimeInForce.IOC,
                0, 1, com.surprising.aeron.protocol.CoreMarginMode.CROSS, com.surprising.aeron.protocol.CorePositionSide.NET,
                com.surprising.aeron.protocol.CoreTriggerOrderStatus.TRIGGERED, 77, 9, 121, "", 0, 1100, 1000, 1200, 3, 0, 0,
                com.surprising.aeron.protocol.CoreTriggerPriceSource.LAST);
        when(projection.get(ProductLine.LINEAR_PERPETUAL, 1001L, Long.MAX_VALUE)).thenReturn(java.util.Optional.of(state));
        when(projection.get(ProductLine.LINEAR_PERPETUAL, null, Long.MAX_VALUE)).thenReturn(java.util.Optional.of(state));
        assertThat(service.get(1001, Long.MAX_VALUE).triggerOrderId()).isEqualTo(Long.MAX_VALUE);
        var timeline = service.adminTimeline(Long.MAX_VALUE);
        assertThat(timeline.order().priceSource()).isEqualTo(TriggerPriceSource.LAST);
        assertThat(timeline.events()).extracting(com.surprising.trading.api.model.AdminTriggerOrderTimelineEvent::eventType)
                .containsExactly("CREATED", "TRIGGERED_LAST", "EXECUTION_PLACED");
        org.mockito.Mockito.verifyNoInteractions(core);
    }

    @Test
    void placementSurvivesProviderReconstructionWithStableTemplate() {
        TriggerProperties properties = properties();
        TriggerOrderAeronGateway gateway = mock(TriggerOrderAeronGateway.class);
        when(gateway.place(any(UUID.class), eq(1001L), any(PlaceTriggerOrderCommand.class)))
                .thenAnswer(invocation -> invocation.<PlaceTriggerOrderCommand>getArgument(2)
                        .materialize(ProductLine.LINEAR_PERPETUAL, 1001, 1_700_000_000_000L));

        var first = new TriggerOrderService(properties, gateway, null, null).place(request("trigger-client"));
        var reconstructed = new TriggerOrderService(properties, gateway, null, null).place(request("trigger-client"));

        assertThat(reconstructed.triggerOrderId()).isEqualTo(first.triggerOrderId());
        assertThat(reconstructed.createdAt()).isEqualTo(first.createdAt());
    }

    @Test
    void selectedIndexSourceReachesCoreAndReturnsToClient() {
        var gateway = mock(TriggerOrderAeronGateway.class);
        when(gateway.place(any(UUID.class), eq(1001L), any(PlaceTriggerOrderCommand.class)))
                .thenAnswer(call -> call.<PlaceTriggerOrderCommand>getArgument(2)
                        .materialize(ProductLine.LINEAR_PERPETUAL, 1001, 1_700_000_000_000L));
        var request = new PlaceTriggerOrderRequest(1001L, "index-source", null, "1",
                OrderSide.SELL, TriggerOrderType.TAKE_PROFIT, 70_000L, null, null,
                OrderType.MARKET, TimeInForce.IOC, 0L, 10L, MarginMode.CROSS,
                PositionSide.NET, null, TriggerPriceSource.INDEX);

        assertThat(new TriggerOrderService(properties(), gateway, null, null).place(request).priceSource())
                .isEqualTo(TriggerPriceSource.INDEX);
        org.mockito.Mockito.verify(gateway).place(any(UUID.class), eq(1001L),
                org.mockito.ArgumentMatchers.argThat(view ->
                        view.priceSource() == com.surprising.aeron.protocol.CoreTriggerPriceSource.INDEX));
    }

    @Test
    void rejectsMissingClientTriggerOrderIdBeforeCoreSubmission() {
        TriggerOrderService service = new TriggerOrderService(properties(), mock(TriggerOrderAeronGateway.class), null, null);

        assertThatThrownBy(() -> service.place(request(" ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clientTriggerOrderId is required");
    }

    @Test
    void atomicPairUsesOneCoreCommandAndPreservesRequestedLegOrder() {
        var gateway = mock(TriggerOrderAeronGateway.class);
        when(gateway.placeOcoPair(any(UUID.class), eq(1001L), any()))
                .thenAnswer(call -> call.<java.util.List<PlaceTriggerOrderCommand>>getArgument(2).stream()
                        .map(view -> view.materialize(ProductLine.LINEAR_PERPETUAL, 1001, 1_700_000_000_000L)).toList());
        var service = new TriggerOrderService(properties(), gateway, null, null);
        var result = service.placeBatch(new com.surprising.trading.api.model.BatchPlaceTriggerOrderRequest(
                java.util.List.of(leg("sl", TriggerOrderType.STOP_LOSS, 50_000),
                        leg("tp", TriggerOrderType.TAKE_PROFIT, 70_000)), true));
        assertThat(result.completed()).isEqualTo(2);
        assertThat(result.failed()).isZero();
        assertThat(result.results().get(0).order().clientTriggerOrderId()).isEqualTo("sl");
        assertThat(result.results().get(1).order().clientTriggerOrderId()).isEqualTo("tp");
        org.mockito.Mockito.verify(gateway).placeOcoPair(any(UUID.class), eq(1001L), any());
        org.mockito.Mockito.verifyNoMoreInteractions(gateway);
    }

    @Test
    void malformedAtomicPairIsRejectedBeforeSendingEitherLeg() {
        var gateway = mock(TriggerOrderAeronGateway.class);
        var service = new TriggerOrderService(properties(), gateway, null, null);
        assertThatThrownBy(() -> service.placeBatch(new com.surprising.trading.api.model.BatchPlaceTriggerOrderRequest(
                java.util.List.of(leg("sl", TriggerOrderType.STOP_LOSS, 70_000),
                        leg("tp", TriggerOrderType.TAKE_PROFIT, 50_000)), true)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("OCO pair");
        org.mockito.Mockito.verifyNoInteractions(gateway);
    }

    private static PlaceTriggerOrderRequest leg(String client, TriggerOrderType type, long triggerPrice) {
        return new PlaceTriggerOrderRequest(1001L, client, "pair", "1", OrderSide.SELL,
                type, triggerPrice, OrderType.MARKET, TimeInForce.IOC, 0L, 10L,
                MarginMode.CROSS, PositionSide.NET, null);
    }

    private static TriggerProperties properties() {
        TriggerProperties properties = new TriggerProperties();
        properties.setProductLine(ProductLine.LINEAR_PERPETUAL);
        return properties;
    }

    private static PlaceTriggerOrderRequest request(String clientId) {
        return new PlaceTriggerOrderRequest(1001L, clientId, null, "1", OrderSide.SELL,
                TriggerOrderType.TAKE_PROFIT, 70_000L, OrderType.MARKET, TimeInForce.IOC, 0L, 10L,
                MarginMode.CROSS, PositionSide.NET, null);
    }
}
