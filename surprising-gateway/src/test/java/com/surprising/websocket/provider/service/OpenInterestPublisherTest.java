package com.surprising.websocket.provider.service;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.surprising.account.api.model.*;
import com.surprising.account.provider.service.AccountOpenInterestSnapshotService;
import com.surprising.product.api.ProductLine;
import com.surprising.websocket.api.model.*;
import com.surprising.websocket.provider.config.WebSocketProperties;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenInterestPublisherTest {
    private final SubscriptionRegistry registry = mock(SubscriptionRegistry.class);
    private final AccountOpenInterestSnapshotService snapshots = mock(AccountOpenInterestSnapshotService.class);
    private final WebSocketProperties properties = new WebSocketProperties();
    private final ProductLine product = ProductLine.LINEAR_PERPETUAL;
    private SubscriptionTopic topic(String instrumentId, ProductLine line) {
        return new SubscriptionTopic(WsChannel.OPEN_INTEREST, instrumentId, null, null, line);
    }
    private OpenInterestPublisher publisher() {
        properties.getKafka().setProductLine(product);
        var products = mock(com.surprising.gateway.provider.product.GatewayProductServices.class);
        when(products.enabled()).thenReturn(List.of(product));
        when(products.service(product, AccountOpenInterestSnapshotService.class)).thenReturn(snapshots);
        return new OpenInterestPublisher(registry, products);
    }
    @Test void noSubscribersDoesNotQueryCore() {
        when(registry.topics(WsChannel.OPEN_INTEREST)).thenReturn(List.of());
        publisher().publish();
        verifyNoInteractions(snapshots);
    }
    @Test void publishesSingleSidedTotalsAndAuthoritativeZeroFromOneQuery() {
        var btc = topic("49", product);
        var eth = topic("50", product);
        var other = topic("49", ProductLine.INVERSE_PERPETUAL);
        when(registry.topics(WsChannel.OPEN_INTEREST)).thenReturn(List.of(btc, eth, other));
        Instant now = Instant.now();
        when(snapshots.snapshot(product)).thenReturn(new OpenInterestSnapshotResponse(product, 7, now,
                List.of(new OpenInterestShardSnapshot(product, btc.instrumentId(), 0, 5, 5, 7, now),
                        new OpenInterestShardSnapshot(product, btc.instrumentId(), 1, 3, 3, 7, now))));
        publisher().publish();
        verify(snapshots, times(1)).snapshot(product);
        verify(registry).publish(btc, Map.of("instrumentId", btc.instrumentId(), "status", "READY", "openInterestSteps", "8", "sequence", "7"), now);
        verify(registry).publish(eth, Map.of("instrumentId", eth.instrumentId(), "status", "READY", "openInterestSteps", "0", "sequence", "7"), now);
        verify(registry, never()).publish(eq(other), any(), any());
    }
    @Test void failureInOneProductDoesNotHideTheOtherProduct() {
        var products = mock(com.surprising.gateway.provider.product.GatewayProductServices.class);
        var inverse = mock(AccountOpenInterestSnapshotService.class);
        var other = ProductLine.INVERSE_PERPETUAL;
        when(products.enabled()).thenReturn(List.of(product, other));
        when(products.service(product, AccountOpenInterestSnapshotService.class)).thenReturn(snapshots);
        when(products.service(other, AccountOpenInterestSnapshotService.class)).thenReturn(inverse);
        var first = topic("49", product);
        var second = topic("49", other);
        when(registry.topics(WsChannel.OPEN_INTEREST)).thenReturn(List.of(first, second));
        when(snapshots.snapshot(product)).thenThrow(new IllegalStateException("Core unavailable"));
        Instant now = Instant.now();
        when(inverse.snapshot(other)).thenReturn(new OpenInterestSnapshotResponse(other, 9, now,
                List.of(new OpenInterestShardSnapshot(other, "49", 0, 3, 3, 9, now))));
        new OpenInterestPublisher(registry, products).publish();
        verify(registry).publish(eq(first), eq(Map.of("instrumentId", "49", "status", "UNAVAILABLE")), any());
        verify(registry).publish(second, Map.of("instrumentId", "49", "status", "READY",
                "openInterestSteps", "3", "sequence", "9"), now);
    }
    @Test void unavailableIsNotZero() {
        var btc = topic("49", product);
        when(registry.topics(WsChannel.OPEN_INTEREST)).thenReturn(List.of(btc));
        when(snapshots.snapshot(product)).thenThrow(new IllegalStateException("recovering"));
        publisher().publish();
        verify(registry).publish(eq(btc), eq(Map.of("instrumentId", btc.instrumentId(), "status", "UNAVAILABLE")), any());
    }
}
