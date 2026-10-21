package com.surprising.trading.trigger.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.surprising.aeron.protocol.CoreTriggerOrderStateView;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.trigger.config.TriggerProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class TriggerLifecycleDrainTest {
    private final TriggerOrderAeronGateway core = mock(TriggerOrderAeronGateway.class);
    private final TriggerProperties properties = new TriggerProperties();
    private TriggerOrderService service() { properties.setProductLine(ProductLine.LINEAR_DELIVERY); return new TriggerOrderService(properties,core, null, null); }
    @Test void cancelsBoundedCorePageAndChecksCurrentOpenState() {
        var order=mock(CoreTriggerOrderStateView.class);
        when(order.productLine()).thenReturn(ProductLine.LINEAR_DELIVERY);
        when(order.instrumentId()).thenReturn("73"); when(order.userId()).thenReturn(5L); when(order.triggerOrderId()).thenReturn(17L);
        when(core.openOrders(0,"73",0,1000)).thenReturn(List.of(order));
        when(core.openOrders(0,"73",0,1)).thenReturn(List.of(order),List.of());
        var service=service();
        assertThat(service.cancelLifecycleOrders("73",1000)).isEqualTo(1);
        verify(core).cancel(5,17);
        assertThat(service.hasLifecycleActiveOrders("73")).isTrue();
        assertThat(service.hasLifecycleActiveOrders("73")).isFalse();
    }
    @Test void rejectsCrossProductStateBeforeCancel() {
        var order=mock(CoreTriggerOrderStateView.class);
        when(order.productLine()).thenReturn(ProductLine.INVERSE_DELIVERY);
        when(core.openOrders(0,"73",0,1000)).thenReturn(List.of(order));
        assertThatThrownBy(()->service().cancelLifecycleOrders("73",1000)).hasMessageContaining("identity mismatch");
        verify(core,never()).cancel(anyLong(),anyLong());
    }
    @Test void rejectsUnboundedPages() {
        assertThatThrownBy(()->service().cancelLifecycleOrders("73",1001)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(core);
    }
}
