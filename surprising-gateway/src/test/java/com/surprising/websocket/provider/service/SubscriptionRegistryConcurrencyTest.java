package com.surprising.websocket.provider.service;

import com.surprising.aeron.protocol.CoreOrderBookView;
import com.surprising.product.api.ProductLine;
import com.surprising.websocket.api.model.SubscriptionTopic;
import com.surprising.websocket.api.model.WsChannel;
import com.surprising.websocket.provider.config.WebSocketProperties;
import com.surprising.websocket.provider.session.ClientConnection;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SubscriptionRegistryConcurrencyTest {
    private final SubscriptionRegistry registry = new SubscriptionRegistry(new ObjectMapper(), new WebSocketProperties());
    private final SubscriptionTopic book = new SubscriptionTopic(WsChannel.DEPTH, "1", null, null, ProductLine.SPOT);

    private ClientConnection connection(String id) {
        var connection = mock(ClientConnection.class);
        when(connection.id()).thenReturn(id);
        when(connection.send(anyString())).thenReturn(true);
        var cleanup = new java.util.concurrent.atomic.AtomicReference<Runnable>();
        var open = new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(call -> { cleanup.set(call.getArgument(0)); return null; }).when(connection).onClosed(any());
        doAnswer(call -> { if (open.compareAndSet(true, false)) Thread.ofVirtual().start(cleanup.get()); return null; }).when(connection).close();
        registry.add(connection);
        return connection;
    }

    @Test void slowRegistrationDoesNotBlockOtherDepthAndDisconnectBalancesMembership() throws Exception {
        var active = connection("active");
        registry.subscribe(active, book);
        var pending = connection("pending");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var lifecycle = mock(SubscriptionRegistry.RouteLifecycle.class);
        registry.routeLifecycle(lifecycle);
        doAnswer(call -> { entered.countDown(); assertThat(release.await(3, TimeUnit.SECONDS)).isTrue(); return null; })
                .when(lifecycle).subscribed(book);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var subscribing = pool.submit(() -> registry.subscribe(pending, book));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            var publishing = pool.submit(() -> registry.publishDepth(book, new CoreOrderBookView(1, List.of()), "v1", "book", Instant.now()));
            publishing.get(1, TimeUnit.SECONDS);
            var removing = pool.submit(() -> registry.remove(pending.id()));
            release.countDown();
            subscribing.get(2, TimeUnit.SECONDS);
            removing.get(2, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        verify(active).send(contains("SNAPSHOT"));
        verify(lifecycle, times(1)).subscribed(book);
        verify(lifecycle, times(1)).unsubscribed(book);
        assertThat(registry.subscriberCount(book)).isEqualTo(1);
    }

    @Test void synchronousFirstBaselineIsDeliveredAndFailedRegistrationResetsIt() {
        var connection = connection("first");
        var lifecycle = mock(SubscriptionRegistry.RouteLifecycle.class);
        registry.routeLifecycle(lifecycle);
        doAnswer(call -> {
            registry.publishDepth(book, new CoreOrderBookView(1, List.of()), "v1", "book", Instant.now());
            throw new IllegalStateException("registration response lost");
        }).when(lifecycle).subscribed(book);
        assertThatThrownBy(() -> registry.subscribe(connection, book)).isInstanceOf(IllegalStateException.class);
        assertThat(registry.subscriberCount(book)).isZero();
        assertThat(registry.totalSubscriptionCount()).isZero();
        doNothing().when(lifecycle).subscribed(book);
        registry.subscribe(connection, book);
        registry.publishDepth(book, new CoreOrderBookView(2, List.of()), "v2", "book", Instant.now());
        verify(connection, times(2)).send(contains("SNAPSHOT"));
        verify(connection, never()).send(contains("DELTA"));
    }

    @Test void slowLeaseRemovalDoesNotKeepGlobalDepthLock() throws Exception {
        var failed = connection("failed");
        var healthy = connection("healthy");
        registry.subscribe(failed, book);
        registry.subscribe(healthy, book);
        when(failed.send(anyString())).thenReturn(false);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var lifecycle = mock(SubscriptionRegistry.RouteLifecycle.class);
        registry.routeLifecycle(lifecycle);
        doAnswer(call -> { entered.countDown(); assertThat(release.await(3, TimeUnit.SECONDS)).isTrue(); return null; })
                .when(lifecycle).unsubscribed(book);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> registry.publishDepth(book, new CoreOrderBookView(1, List.of()), "v1", "book", Instant.now()));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            var next = pool.submit(() -> registry.publishDepth(book, new CoreOrderBookView(2, List.of()), "v2", "book", Instant.now()));
            next.get(1, TimeUnit.SECONDS);
            release.countDown();
            first.get(2, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        verify(healthy).send(contains("DELTA"));
    }
}
