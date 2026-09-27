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
    private SubscriptionTopic topic(String symbol, ProductLine line) {
        return new SubscriptionTopic(WsChannel.OPEN_INTEREST, symbol, null, null, line);
    }
    private OpenInterestPublisher publisher() {
        properties.getKafka().setProductLine(product);
        return new OpenInterestPublisher(registry, snapshots, properties);
    }
    @Test void noSubscribersDoesNotQueryCore() {
        when(registry.topics(WsChannel.OPEN_INTEREST)).thenReturn(List.of());
        publisher().publish();
        verifyNoInteractions(snapshots);
    }
    @Test void publishesSingleSidedTotalsAndAuthoritativeZeroFromOneQuery() {
        var btc = topic("BTC-USDT-SWAP", product);
        var eth = topic("ETH-USDT-SWAP", product);
        var other = topic("BTC-USDT-SWAP", ProductLine.INVERSE_PERPETUAL);
        when(registry.topics(WsChannel.OPEN_INTEREST)).thenReturn(List.of(btc, eth, other));
        Instant now = Instant.now();
        when(snapshots.snapshot(product)).thenReturn(new OpenInterestSnapshotResponse(product, 7, now,
                List.of(new OpenInterestShardSnapshot(product, btc.symbol(), 0, 5, 5, 7, now),
                        new OpenInterestShardSnapshot(product, btc.symbol(), 1, 3, 3, 7, now))));
        publisher().publish();
        verify(snapshots, times(1)).snapshot(product);
        verify(registry).publish(btc, Map.of("symbol", btc.symbol(), "status", "READY", "openInterestSteps", "8", "sequence", "7"), now);
        verify(registry).publish(eth, Map.of("symbol", eth.symbol(), "status", "READY", "openInterestSteps", "0", "sequence", "7"), now);
        verify(registry, never()).publish(eq(other), any(), any());
    }
    @Test void unavailableIsNotZero() {
        var btc = topic("BTC-USDT-SWAP", product);
        when(registry.topics(WsChannel.OPEN_INTEREST)).thenReturn(List.of(btc));
        when(snapshots.snapshot(product)).thenThrow(new IllegalStateException("recovering"));
        publisher().publish();
        verify(registry).publish(eq(btc), eq(Map.of("symbol", btc.symbol(), "status", "UNAVAILABLE")), any());
    }
}
