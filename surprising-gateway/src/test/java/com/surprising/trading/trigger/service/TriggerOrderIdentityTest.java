package com.surprising.trading.trigger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.surprising.aeron.protocol.CoreTriggerOrderStateView;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PlaceTriggerOrderRequest;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.api.model.TriggerOrderType;
import com.surprising.trading.trigger.config.TriggerProperties;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TriggerOrderIdentityTest {

    @Test
    void privateTriggerQueryDoesNotReturnToCoreOnAReadViewMiss() {
        var gateway=mock(TriggerOrderAeronGateway.class);var service=new TriggerOrderService(properties(),gateway);
        var queries=mock(com.surprising.realtime.api.ValkeyUserQueries.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"realtimeQueries",queries);
        when(queries.require(ProductLine.LINEAR_PERPETUAL,1001L,null)).thenReturn(
            new com.surprising.realtime.api.UserReadView("READY","v",1,null,java.util.List.of(),java.util.List.of(),1,java.util.List.of()));
        assertThatThrownBy(()->service.get(1001L,99L)).isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.verifyNoInteractions(gateway);
    }

    @Test
    void placementSurvivesProviderReconstructionWithStableTemplate() {
        TriggerProperties properties = properties();
        TriggerOrderAeronGateway gateway = mock(TriggerOrderAeronGateway.class);
        when(gateway.place(any(UUID.class), eq(1001L), any(CoreTriggerOrderStateView.class)))
                .thenAnswer(invocation -> invocation.<CoreTriggerOrderStateView>getArgument(2)
                        .materializeCreation(1_700_000_000_000L));

        var first = new TriggerOrderService(properties, gateway).place(request("trigger-client"));
        var reconstructed = new TriggerOrderService(properties, gateway).place(request("trigger-client"));

        assertThat(reconstructed.triggerOrderId()).isEqualTo(first.triggerOrderId());
        assertThat(reconstructed.traceId()).isEqualTo(first.traceId());
        assertThat(reconstructed.createdAt()).isEqualTo(first.createdAt());
    }

    @Test
    void rejectsMissingClientTriggerOrderIdBeforeCoreSubmission() {
        TriggerOrderService service = new TriggerOrderService(properties(), mock(TriggerOrderAeronGateway.class));

        assertThatThrownBy(() -> service.place(request(" ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clientTriggerOrderId is required");
    }

    private static TriggerProperties properties() {
        TriggerProperties properties = new TriggerProperties();
        properties.setProductLine(ProductLine.LINEAR_PERPETUAL);
        return properties;
    }

    private static PlaceTriggerOrderRequest request(String clientId) {
        return new PlaceTriggerOrderRequest(1001L, clientId, null, "BTC-USDT", OrderSide.SELL,
                TriggerOrderType.TAKE_PROFIT, 70_000L, OrderType.MARKET, TimeInForce.IOC, 0L, 10L,
                MarginMode.CROSS, PositionSide.NET, null);
    }
}
